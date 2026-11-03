package com.duriseo.server.event.application

import com.duriseo.server.common.access.SpaceAccessPolicy
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.FieldError
import com.duriseo.server.common.api.FieldErrorDetails
import com.duriseo.server.common.api.FieldErrorReason
import com.duriseo.server.common.time.nowInSeconds
import com.duriseo.server.event.domain.Event
import com.duriseo.server.event.domain.EventReminderRepository
import com.duriseo.server.event.domain.EventRepository
import com.duriseo.server.notification.application.NotificationService
import com.duriseo.server.space.application.SpaceService
import com.duriseo.server.user.application.PartnerChangedException
import com.duriseo.server.user.application.PartnerLockRetry
import com.duriseo.server.user.application.UserService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * 일정 조회·작성·수정·삭제 (API.md 3.3, FR-CAL-01·02·03, R-01·02·07·10·13·15).
 * 권한은 모두 [SpaceAccessPolicy]가 검사하고(다른 공간의 일정은 403이 아니라 404, DB-05) 이 서비스는 따로 검사하지 않는다.
 *
 * **잠금:** 일정 쓰기는 공간 행을 잠그지 않는다. 공간 행을 잠근 채 알림 행(`PARTNER_CREATED`)을 넣으면 알림의 외래 키 검사가
 * 상대 `users` 행에 `FOR SHARE`를 요구하고, 그 행을 `FOR UPDATE`로 잡은 상대의 탈퇴가 공간 행을 기다리다 교착하기 때문이다
 * (ARCHITECTURE 4장). 수정·삭제는 그 일정 행만 `FOR UPDATE`로 잠그고 R-01·R-02·R-13·R-10을 잠근 값으로 다시 확인한다.
 * 만들기는 일정·알림 행을 넣기 전에 요청자와 상대의 `users` 행을 ID 오름차순으로 `FOR SHARE`로 먼저 잡는다: 일정의 외래 키 검사가
 * 공간 행에 `FOR SHARE`를 잡은 채 사용자 행을 기다리면, 사용자 행 → 공간 행 순서로 잠그는 탈퇴·연결 해제와 교착하기 때문이다.
 * 함께 일정의 동시 수정은 마지막 저장이 이긴다 (NFR-SYNC-03, 낙관적 락 없음).
 *
 * 일정 알림(`PUT`·`DELETE /events/{id}/reminder`)과 다시 계산의 계산식은 [EventReminderService]가 한다.
 * 일정 변경은 커밋 뒤 동기화 신호를 위해 [EventCreated]·[EventUpdated]·[EventDeleted]를 발행한다 (발송은 단위 6).
 */
@Service
class EventService(
	private val policy: SpaceAccessPolicy,
	private val eventRepository: EventRepository,
	private val reminderRepository: EventReminderRepository,
	private val userService: UserService,
	private val spaceService: SpaceService,
	private val notifications: NotificationService,
	private val reminderService: EventReminderService,
	private val locker: EventLocker,
	private val events: ApplicationEventPublisher,
	private val clock: Clock,
	transactionManager: PlatformTransactionManager,
) {

	private val tx = TransactionTemplate(transactionManager)

	/**
	 * `GET /events?from=&to=` (FR-CAL-01 AC2, R-01, R-07, R-15). 요청자의 활성 공간 일정 중 범위에 하루라도 걸치는 것을 돌려준다.
	 * [rawFrom]·[rawTo]는 쿼리 문자열 그대로다. 검사(필수·형식·순서·3개월)는 요청받은 값 그대로 먼저 하고,
	 * 그 뒤 1950-01-01 ~ 2050-12-31로 잘라낸다. 잘라낸 구간이 비면 빈 목록이다.
	 */
	@Transactional(readOnly = true)
	fun list(requesterId: UUID, rawFrom: String?, rawTo: String?): List<EventView> {
		val (from, to) = parseRange(rawFrom, rawTo)
		val spaceId = checkNotNull(policy.activeSpaceIdOf(requesterId)) { "user has no active space" }
		// LocalDate는 Comparable<LocalDate>가 아니라서 maxOf·minOf를 쓰지 않는다
		val clampedFrom = if (from.isBefore(EventWriteRules.MIN_DATE)) EventWriteRules.MIN_DATE else from
		val clampedTo = if (to.isAfter(EventWriteRules.MAX_DATE)) EventWriteRules.MAX_DATE else to
		if (clampedFrom.isAfter(clampedTo)) return emptyList()

		val zone = zoneOf(requesterId)
		val found = eventRepository.findOverlapping(
			spaceId = spaceId,
			fromDate = clampedFrom,
			toDate = clampedTo,
			fromInclusive = clampedFrom.atStartOfDay(zone).toInstant(),
			toExclusive = clampedTo.plusDays(1).atStartOfDay(zone).toInstant(),
		)
		if (found.isEmpty()) return emptyList()

		val reminders = reminderRepository.findAllByEventIdInAndUserId(found.map { it.requireId() }, requesterId)
			.associateBy { it.eventId }
		// 시작 날짜(시간 일정은 요청자 시간대의 날짜) → 종일 먼저 → 시작 일시 → ID
		return found
			.sortedWith(
				compareBy<Event>(
					{ startDateOf(it, zone) },
					{ if (it.isAllDay) 0 else 1 },
					{ it.startAt ?: Instant.MIN },
					{ it.requireId() },
				),
			)
			.map { EventView.of(it, reminders[it.requireId()]) }
	}

	/**
	 * `POST /events` (FR-CAL-02, FR-NOTI-04, R-10, R-15). 요청자의 활성 공간에 일정을 만든다. 작성자는 요청자이고 `updated_by`는 null이다.
	 * 일정 알림(`event_reminders`)은 만들지 않는다 (D-59). 검사 순서: R-10 → 값 검사 → 날짜 범위.
	 * 커플 공간에서 `notifyPartner`가 true면 같은 트랜잭션에서 상대의 `PARTNER_CREATED` 알림 행을 만든다 (D-92). 상대의 `partner_created`
	 * 설정과 관계없이 행은 만들고 설정은 푸시만 막는다(단위 6, API.md 7장).
	 *
	 * 사용자 행을 `FOR SHARE`로 잡은 뒤 활성 공간과 상대를 다시 읽는다. 잡으려고 기다리는 사이 연결 해제가 커밋됐다면 해제된 공간에
	 * 일정과 알림 행이 남기 때문이다. 바뀌었으면 [PartnerLockRetry] 안에서는 롤백하고 처음부터 다시 하고, 다시 시도할 수 없으면
	 * 다시 읽은 값(새 공간·새 상대)으로 이어서 쓴다.
	 */
	fun create(requesterId: UUID, body: Map<String, Any?>): EventView =
		checkNotNull(PartnerLockRetry.run { tx.execute { createInTransaction(requesterId, body) } })

	private fun createInTransaction(requesterId: UUID, body: Map<String, Any?>): EventView {
		// 행을 넣기 전에 사용자 행을 먼저 잡는다 (클래스 머리말 "잠금")
		val firstSpaceId = policy.activeSpaceIdOf(requesterId)
		var partnerId = spaceService.activePartnerIdOf(requesterId)
		userService.lockShare(listOfNotNull(requesterId, partnerId))
		val spaceId = checkNotNull(policy.activeSpaceIdOf(requesterId)) { "user has no active space" }
		val currentPartnerId = spaceService.activePartnerIdOf(requesterId)
		if (currentPartnerId != partnerId || spaceId != firstSpaceId) {
			if (PartnerLockRetry.canRetry()) throw PartnerChangedException()
			partnerId = currentPartnerId
			userService.lockShare(listOfNotNull(partnerId))
		}
		policy.requireEventTargetAllowed(requesterId, body[EventWriteRules.IS_TOGETHER] as? Boolean)
		val draft = EventWriteRules(zoneOf(requesterId)).parseCreate(body)

		val now = clock.nowInSeconds()
		val event = eventRepository.save(
			Event(
				spaceId = spaceId,
				authorId = requesterId,
				isTogether = draft.isTogether,
				title = draft.title,
				note = draft.note,
				isAllDay = draft.isAllDay,
				startAt = draft.startAt,
				endAt = draft.endAt,
				startDate = draft.startDate,
				endDate = draft.endDate,
				now = now,
			),
		)
		val notified = if (draft.notifyPartner && partnerId != null) notifyPartner(event, requesterId, partnerId, now) else null
		events.publishEvent(EventCreated(spaceId, event.requireId(), requesterId, notified?.first, notified?.second))
		return EventView.of(event, null)
	}

	/** `GET /events/{id}` (R-01, R-07). 다른 공간·없는 일정은 같은 404 `NOT_FOUND` */
	@Transactional(readOnly = true)
	fun get(requesterId: UUID, eventId: UUID): EventView {
		policy.requireInActiveSpace(requesterId, eventRepository.findSpaceIdById(eventId))
		val event = eventRepository.findByIdOrNull(eventId)
		policy.requireInActiveSpace(requesterId, event?.spaceId)
		checkNotNull(event)
		return EventView.of(event, reminderRepository.findByEventIdAndUserId(eventId, requesterId))
	}

	/**
	 * `PATCH /events/{id}` (FR-CAL-03, R-01 → R-02 → R-13 → R-10 → 값 검사 → 날짜 범위). 부분 수정이다.
	 * 일정 행을 잠근 뒤 권한 규칙을 잠근 값으로 확인한다. 거부되면 아무것도 바꾸지 않는다.
	 * 수정은 상대에게 알림 행을 만들지 않는다. 일시가 바뀌면 같은 트랜잭션에서 그 일정에 알림을 건 모든 사용자의 알림을 다시 처리한다 (D-91):
	 * 종일 ↔ 시간이 바뀌면 모두 지우고, 종류는 그대로 `start`가 바뀌면 `fire_at`·`sent_at`을 다시 정한다. `end`·제목·메모·대상만 바뀌면 그대로다.
	 * 저장된 값과 같은 값만 보낸 요청(빈 본문 포함)은 아무것도 바꾸지 않는다: `updated_by`·`updated_at` 그대로, `EventUpdated`도 없다.
	 */
	@Transactional
	fun update(requesterId: UUID, eventId: UUID, body: Map<String, Any?>): EventView {
		val event = lockAndAuthorize(requesterId, eventId)
		policy.requireEventTargetChangeByAuthor(requesterId, event.authorId, event.isTogether, body[EventWriteRules.IS_TOGETHER] as? Boolean)
		policy.requireEventTargetAllowed(requesterId, body[EventWriteRules.IS_TOGETHER] as? Boolean)
		val patch = EventWriteRules(zoneOf(requesterId)).parsePatch(body, event)
		val view = { EventView.of(event, reminderRepository.findByEventIdAndUserId(eventId, requesterId)) }
		// 값이 하나도 바뀌지 않으면(빈 본문 포함) 수정자·수정 시각을 바꾸지 않고 이벤트도 발행하지 않는다. 200과 지금의 Event는 그대로 돌려준다
		val time = patch.time
		val titleChanged = patch.title != null && patch.title != event.title
		val noteChanged = patch.noteProvided && patch.note != event.note
		val togetherChanged = patch.isTogether != null && patch.isTogether != event.isTogether
		val timeChanged = time != null && (
			time.isAllDay != event.isAllDay || time.startAt != event.startAt || time.endAt != event.endAt ||
				time.startDate != event.startDate || time.endDate != event.endDate
			)
		if (!titleChanged && !noteChanged && !togetherChanged && !timeChanged) return view()

		val now = clock.nowInSeconds()
		if (titleChanged) event.title = checkNotNull(patch.title)
		if (noteChanged) event.note = patch.note
		if (togetherChanged) event.isTogether = checkNotNull(patch.isTogether)
		if (time != null && timeChanged) {
			event.changeTime(time.isAllDay, time.startAt, time.endAt, time.startDate, time.endDate)
			if (time.allDayChanged) {
				reminderService.deleteAllOfEvent(eventId)
			} else if (time.startChanged) {
				reminderService.recalculateAllOfEvent(event, now)
			}
		}
		event.updatedBy = requesterId
		event.updatedAt = now
		events.publishEvent(EventUpdated(event.spaceId, eventId, requesterId))
		return view()
	}

	/** `DELETE /events/{id}` (FR-CAL-03, R-01 → R-02). 일정 알림은 DB가 CASCADE로 지우고, 그 일정을 가리키는 `notifications` 행은 지우지 않는다 (D-43) */
	@Transactional
	fun delete(requesterId: UUID, eventId: UUID) {
		val event = lockAndAuthorize(requesterId, eventId)
		eventRepository.delete(event)
		events.publishEvent(EventDeleted(event.spaceId, eventId, requesterId))
	}

	/**
	 * R-01(없거나 다른 공간이면 404) → 그 일정 행을 `FOR UPDATE`로 잠금 → 잠근 값으로 R-01·R-02(403)를 다시 확인한다 ([EventLocker]).
	 */
	private fun lockAndAuthorize(requesterId: UUID, eventId: UUID): Event {
		val event = locker.lockInActiveSpace(requesterId, eventId)
		policy.requireEventAuthorOrTogether(requesterId, event.authorId, event.isTogether)
		return event
	}

	/**
	 * 상대에게 `PARTNER_CREATED` 알림 행을 만든다 (D-92). 날짜는 일정의 시작 날짜다: 종일은 `start_date`, 시간 일정은 상대의 시간대로 바꾼 날짜.
	 * 상대나 작성자를 읽을 수 없으면 만들지 않는다. 상대 ID와 알림 ID를 돌려준다.
	 */
	private fun notifyPartner(event: Event, authorId: UUID, partnerId: UUID, now: Instant): Pair<UUID, UUID>? {
		val author = userService.findActive(authorId) ?: return null
		val partner = userService.findActive(partnerId) ?: return null
		val startDate = event.startDate ?: checkNotNull(event.startAt).atZone(UserZones.parseOrUtc(partner.timeZone)).toLocalDate()
		val notificationId = notifications.createPartnerCreated(
			recipientUserId = partnerId,
			authorNickname = author.nickname,
			startDate = startDate,
			eventTitle = event.title,
			spaceId = event.spaceId,
			eventId = event.requireId(),
			now = now,
		)
		return partnerId to notificationId
	}

	// ── GET /events 쿼리 ────────────────────────────────

	/**
	 * `from`·`to`는 모두 필수 `YYYY-MM-DD`이고 `to`를 포함한다. 없으면 `REQUIRED`, 날짜 형식이 아니면 `INVALID`,
	 * `from` > `to`이거나 `to` > `from` + 3개월(달력 월로 더함)이면 (`to`, `INVALID`). 틀린 필드를 모두 담는다.
	 */
	private fun parseRange(rawFrom: String?, rawTo: String?): Pair<LocalDate, LocalDate> {
		val errors = mutableListOf<FieldError>()
		val from = parseQueryDate("from", rawFrom, errors)
		val to = parseQueryDate("to", rawTo, errors)
		if (from != null && to != null) {
			if (from.isAfter(to) || to.isAfter(from.plusMonths(RANGE_MONTHS))) errors += FieldError("to", FieldErrorReason.INVALID)
		}
		if (errors.isNotEmpty()) {
			throw ApiException(
				ErrorCode.VALIDATION_FAILED,
				errors.joinToString { "${it.field}: ${it.reason}" },
				FieldErrorDetails(errors),
			)
		}
		return checkNotNull(from) to checkNotNull(to)
	}

	private fun parseQueryDate(field: String, raw: String?, errors: MutableList<FieldError>): LocalDate? {
		if (raw == null) {
			errors += FieldError(field, FieldErrorReason.REQUIRED)
			return null
		}
		if (!QUERY_DATE_FORMAT.matches(raw)) {
			errors += FieldError(field, FieldErrorReason.INVALID)
			return null
		}
		return try {
			LocalDate.parse(raw)
		} catch (e: DateTimeParseException) {
			errors += FieldError(field, FieldErrorReason.INVALID)
			null
		}
	}

	private fun startDateOf(event: Event, zone: ZoneId): LocalDate =
		event.startDate ?: checkNotNull(event.startAt).atZone(zone).toLocalDate()

	/** 요청자의 시간대. 저장된 값을 해석할 수 없으면 UTC (SpaceSettingsService와 같다). 서버 시간대는 쓰지 않는다 (NFR-TIME-01) */
	private fun zoneOf(userId: UUID): ZoneId {
		val user = userService.findActive(userId) ?: throw ApiException(ErrorCode.SESSION_INVALID, "user not found")
		return UserZones.parseOrUtc(user.timeZone)
	}

	private companion object {
		/** 조회 범위 한도: `to`는 `from` + 3개월(달력 월)까지 (API.md 3.3 "조회 규칙") */
		const val RANGE_MONTHS = 3L

		val QUERY_DATE_FORMAT = Regex("""\d{4}-\d{2}-\d{2}""")
	}
}
