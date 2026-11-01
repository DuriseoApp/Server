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
import com.duriseo.server.user.application.UserService
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * 일정 조회·작성·수정·삭제 (API.md 3.3, FR-CAL-01·02·03, R-01·02·07·10·13·15).
 * 권한은 모두 [SpaceAccessPolicy]가 검사하고(다른 공간의 일정은 403이 아니라 404, DB-05) 이 서비스는 따로 검사하지 않는다.
 *
 * **잠금:** 일정 쓰기는 사용자·공간 행을 잠그지 않는다. 공간 행을 잠근 채 알림 행(`PARTNER_CREATED`, 2부)을 넣으면 알림의 외래 키 검사가
 * 상대 `users` 행에 `FOR KEY SHARE`를 요구하고, 그 행을 `FOR UPDATE`로 잡은 상대의 탈퇴가 공간 행을 기다리다 교착하기 때문이다
 * (ARCHITECTURE 4장). 수정·삭제는 그 일정 행만 `FOR UPDATE`로 잠그고 R-01·R-02·R-13·R-10을 잠근 값으로 다시 확인한다.
 * 함께 일정의 동시 수정은 마지막 저장이 이긴다 (NFR-SYNC-03, 낙관적 락 없음).
 *
 * 일정 알림(`PUT`·`DELETE /events/{id}/reminder`), 일시 변경 때의 알림 다시 계산(D-91), 상대 알림 행(FR-NOTI-04)과
 * 일정 변경 도메인 이벤트는 2부에서 이 서비스에 더한다.
 */
@Service
class EventService(
	private val policy: SpaceAccessPolicy,
	private val eventRepository: EventRepository,
	private val reminderRepository: EventReminderRepository,
	private val userService: UserService,
	private val clock: Clock,
) {

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
	 * `POST /events` (FR-CAL-02, R-10, R-15). 요청자의 활성 공간에 일정을 만든다. 작성자는 요청자이고 `updated_by`는 null이다.
	 * 일정 알림(`event_reminders`)은 만들지 않는다 (D-59). 검사 순서: R-10 → 값 검사 → 날짜 범위.
	 */
	@Transactional
	fun create(requesterId: UUID, body: Map<String, Any?>): EventView {
		val spaceId = checkNotNull(policy.activeSpaceIdOf(requesterId)) { "user has no active space" }
		policy.requireEventTargetAllowed(requesterId, body[EventWriteRules.IS_TOGETHER] as? Boolean)
		val draft = EventWriteRules(zoneOf(requesterId)).parseCreate(body)

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
				now = clock.nowInSeconds(),
			),
		)
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
	 * 수정은 상대에게 알리지 않는다. 일시가 바뀔 때의 알림 다시 계산(D-91)은 2부.
	 */
	@Transactional
	fun update(requesterId: UUID, eventId: UUID, body: Map<String, Any?>): EventView {
		val event = lockAndAuthorize(requesterId, eventId)
		policy.requireEventTargetChangeByAuthor(requesterId, event.authorId, event.isTogether, body[EventWriteRules.IS_TOGETHER] as? Boolean)
		policy.requireEventTargetAllowed(requesterId, body[EventWriteRules.IS_TOGETHER] as? Boolean)
		val patch = EventWriteRules(zoneOf(requesterId)).parsePatch(body, event)

		patch.title?.let { event.title = it }
		if (patch.noteProvided) event.note = patch.note
		patch.isTogether?.let { event.isTogether = it }
		patch.time?.let { event.changeTime(it.isAllDay, it.startAt, it.endAt, it.startDate, it.endDate) }
		event.updatedBy = requesterId
		event.updatedAt = clock.nowInSeconds()
		return EventView.of(event, reminderRepository.findByEventIdAndUserId(eventId, requesterId))
	}

	/** `DELETE /events/{id}` (FR-CAL-03, R-01 → R-02). 일정 알림은 DB가 CASCADE로 지우고, 그 일정을 가리키는 `notifications` 행은 지우지 않는다 (D-43) */
	@Transactional
	fun delete(requesterId: UUID, eventId: UUID) {
		val event = lockAndAuthorize(requesterId, eventId)
		eventRepository.delete(event)
	}

	/**
	 * R-01(없거나 다른 공간이면 404) → 그 일정 행을 `FOR UPDATE`로 잠금 → 잠근 값으로 R-01·R-02(403)를 다시 확인한다.
	 * 처음 R-01은 일정 엔티티를 읽지 않고 공간 ID만 읽어, 다른 공간의 일정 행을 잠그지 않는다.
	 */
	private fun lockAndAuthorize(requesterId: UUID, eventId: UUID): Event {
		policy.requireInActiveSpace(requesterId, eventRepository.findSpaceIdById(eventId))
		val event = eventRepository.findByIdForUpdate(eventId)
		policy.requireInActiveSpace(requesterId, event?.spaceId)
		checkNotNull(event)
		policy.requireEventAuthorOrTogether(requesterId, event.authorId, event.isTogether)
		return event
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
		return try {
			ZoneId.of(user.timeZone)
		} catch (e: DateTimeException) {
			ZoneId.of("UTC")
		}
	}

	private companion object {
		/** 조회 범위 한도: `to`는 `from` + 3개월(달력 월)까지 (API.md 3.3 "조회 규칙") */
		const val RANGE_MONTHS = 3L

		val QUERY_DATE_FORMAT = Regex("""\d{4}-\d{2}-\d{2}""")
	}
}
