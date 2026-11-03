package com.duriseo.server.event.application

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.FieldError
import com.duriseo.server.common.api.FieldErrorDetails
import com.duriseo.server.common.api.FieldErrorReason
import com.duriseo.server.common.time.nowInSeconds
import com.duriseo.server.event.domain.Event
import com.duriseo.server.event.domain.EventReminder
import com.duriseo.server.event.domain.EventReminderRepository
import com.duriseo.server.event.domain.EventRepository
import com.duriseo.server.event.domain.ReminderOption
import com.duriseo.server.user.application.UserService
import com.duriseo.server.user.application.UserTimeZoneChanged
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * 일정 알림 (API.md 3.3, FR-CAL-05, R-01·R-07, PRD D-16·D-59·D-90·D-91).
 *
 * - `PUT /events/{id}/reminder`: 요청자 자신의 알림만 upsert 한다 (R-07). 일정 작성자와 무관하게 활성 공간의 아무 일정에나 건다.
 * - `DELETE /events/{id}/reminder`: 요청자 자신의 알림만 지운다. 알림이 없어도 성공이다(멱등).
 * - 일정 수정과 사용자 시간대 변경에서 다시 계산한다 ("다시 계산"). 모두 그 요청의 트랜잭션 안에서 한다.
 *
 * 알림 변경은 동기화 신호가 없다(API.md 6장). 발송은 단위 6의 스케줄러다.
 */
@Service
class EventReminderService(
	private val locker: EventLocker,
	private val reminders: EventReminderRepository,
	private val eventRepository: EventRepository,
	private val userService: UserService,
	private val clock: Clock,
) {

	/**
	 * `PUT /events/{id}/reminder` (200 `Event`). 검사 순서: R-01 → 값 검사(`option`). 일정 행을 잠가 같은 일정의 `PATCH`와 겹치지 않게 한다.
	 * 계산한 `fire_at`이 지금 이전이면 행은 저장하고 `sent_at` = 지금이다(끝난 일정 포함, D-90). 같은 요청을 다시 보내도 같은 결과다.
	 */
	@Transactional
	fun put(requesterId: UUID, eventId: UUID, body: Map<String, Any?>): EventView {
		lockRequester(requesterId)
		val event = locker.lockInActiveSpace(requesterId, eventId)
		val option = parseOption(body, event)
		val now = clock.nowInSeconds()
		val fireAt = EventReminderCalculator.fireAt(option, event.startAt, event.startDate, ownerZone(requesterId))
		val sentAt = EventReminderCalculator.sentAtFor(fireAt, now)

		val existing = reminders.findByEventIdAndUserId(eventId, requesterId)
		val saved = if (existing != null) {
			existing.reschedule(option, fireAt, sentAt, now)
			existing
		} else {
			reminders.save(EventReminder(eventId, requesterId, option, fireAt, sentAt, now))
		}
		return EventView.of(event, saved)
	}

	/** `DELETE /events/{id}/reminder` (204). R-01만 본다. 알림이 없어도 성공이고, 상대의 알림은 건드리지 않는다 (R-07) */
	@Transactional
	fun delete(requesterId: UUID, eventId: UUID) {
		lockRequester(requesterId)
		locker.lockInActiveSpace(requesterId, eventId)
		reminders.deleteOfEventAndUser(eventId, requesterId)
	}

	/**
	 * R-01 전에 요청자의 사용자 행을 `FOR SHARE`로 잡는다. 연결 해제는 두 사용자 행을 `FOR UPDATE`로 잡은 채 공간의 알림을 지우므로,
	 * 해제가 진행 중이면 여기서 끝나기를 기다리고, 해제가 커밋된 뒤의 R-01은 활성 공간이 바뀌어 404가 된다. 알림 행이 해제 정리 뒤에 남지 않는다.
	 */
	private fun lockRequester(requesterId: UUID) {
		userService.lockShare(listOf(requesterId))
	}

	/** 종일 ↔ 시간 일정으로 바뀌었다: 그 일정의 알림을 **모든 사용자 것** 지운다 (D-91). 일정 수정과 같은 트랜잭션이다 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun deleteAllOfEvent(eventId: UUID) {
		reminders.deleteOfEvent(eventId)
	}

	/**
	 * 종류는 그대로 일정의 `start`가 바뀌었다: 그 일정의 모든 행의 `fire_at`을 새 `start`로 다시 계산하고 `sent_at`을 D-90 규칙
	 * (지났으면 [now], 미래면 null)으로 다시 정한다. 이미 보낸 알림도 새 시각이 미래면 다시 보낸다 (D-91). 종일 옵션은 각 행 주인의 시간대로 계산한다.
	 * [event]는 새 시작 값이 이미 반영돼 있어야 한다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun recalculateAllOfEvent(event: Event, now: Instant) {
		reminders.findAllByEventId(event.requireId()).forEach { reminder ->
			val fireAt = EventReminderCalculator.fireAt(
				reminder.reminderOption,
				event.startAt,
				event.startDate,
				ownerZone(reminder.userId),
			)
			reminder.reschedule(reminder.reminderOption, fireAt, EventReminderCalculator.sentAtFor(fireAt, now), now)
		}
	}

	/**
	 * 사용자의 시간대가 바뀌었다 (`PATCH /me`, 단위 1의 [UserTimeZoneChanged], FR-CAL-05 AC6, D-91). 그 사용자의 행 중 종일 옵션
	 * (`ALLDAY_9AM`·`ALLDAY_PREV_9AM`)이고 `sent_at IS NULL`인 것만 새 시간대로 다시 계산한다. 지났으면 `sent_at` = 지금.
	 * 시간 일정 알림과 이미 `sent_at`이 찍힌 행은 그대로다. `PATCH /me`와 같은 트랜잭션에서 실행한다.
	 */
	@EventListener
	@Transactional(propagation = Propagation.MANDATORY)
	fun onUserTimeZoneChanged(changed: UserTimeZoneChanged) {
		val rows = reminders.findAllByUserIdAndReminderOptionInAndSentAtIsNull(changed.userId, ALL_DAY_OPTIONS)
		if (rows.isEmpty()) return
		val zone = UserZones.parseOrUtc(changed.timeZone)
		val now = clock.nowInSeconds()
		val eventsById = eventRepository.findAllById(rows.map { it.eventId }).associateBy { it.requireId() }
		rows.forEach { reminder ->
			val startDate = eventsById[reminder.eventId]?.startDate ?: return@forEach
			val fireAt = EventReminderCalculator.fireAt(reminder.reminderOption, null, startDate, zone)
			reminder.reschedule(reminder.reminderOption, fireAt, EventReminderCalculator.sentAtFor(fireAt, now), now)
		}
	}

	/**
	 * `option`은 ERD 값 6개 중 하나이고 일정 종류에 맞아야 한다 (3.3 "일정 알림 `option`"). 없거나 `null`이면 (`option`, `REQUIRED`),
	 * 문자열이 아니거나 모르는 값이거나 일정 종류와 맞지 않으면 (`option`, `INVALID`).
	 */
	private fun parseOption(body: Map<String, Any?>, event: Event): ReminderOption {
		val raw = body[OPTION] ?: throw invalid(FieldErrorReason.REQUIRED)
		if (raw !is String) throw invalid(FieldErrorReason.INVALID)
		val option = ReminderOption.entries.firstOrNull { it.name == raw } ?: throw invalid(FieldErrorReason.INVALID)
		if (option.forAllDay != event.isAllDay) throw invalid(FieldErrorReason.INVALID)
		return option
	}

	private fun invalid(reason: FieldErrorReason) = ApiException(
		ErrorCode.VALIDATION_FAILED,
		"$OPTION: $reason",
		FieldErrorDetails(listOf(FieldError(OPTION, reason))),
	)

	/** 알림 주인의 시간대. 읽을 수 없으면 UTC다 (`users.time_zone`을 해석하지 못할 때와 같다) */
	private fun ownerZone(userId: UUID): ZoneId = UserZones.parseOrUtc(userService.findActive(userId)?.timeZone)

	private companion object {
		const val OPTION = "option"

		val ALL_DAY_OPTIONS: List<ReminderOption> = ReminderOption.entries.filter { it.forAllDay }
	}
}
