package com.duriseo.server.event.domain

import com.duriseo.server.common.persistence.UuidEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 사용자별 일정 알림 (ERD 3.3 event_reminders). 일정당 사용자별 행 하나(`uq_event_reminders_event_user`), "없음"은 행이 없는 것이다.
 * 만들기·바꾸기·지우기·다시 계산은 `EventReminderService`가 한다 (API.md 3.3, D-90, D-91). `sent_at`은 보냈거나,
 * 저장할 때 이미 지난 시각이라 보내지 않기로 한 시각이다.
 */
@Entity
@Table(name = "event_reminders")
class EventReminder(
	eventId: UUID,
	userId: UUID,
	reminderOption: ReminderOption,
	fireAt: Instant,
	sentAt: Instant?,
	now: Instant,
) : UuidEntity() {

	@Column(name = "event_id", nullable = false, updatable = false)
	var eventId: UUID = eventId

	@Column(name = "user_id", nullable = false, updatable = false)
	var userId: UUID = userId

	/** 컬럼 이름은 `option`이다. HQL 예약어와 겹치지 않게 프로퍼티 이름만 다르게 쓴다 */
	@Enumerated(EnumType.STRING)
	@Column(name = "option", nullable = false, length = 20)
	var reminderOption: ReminderOption = reminderOption

	@Column(name = "fire_at", nullable = false)
	var fireAt: Instant = fireAt

	@Column(name = "sent_at")
	var sentAt: Instant? = sentAt

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant = now

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = now

	/** 옵션을 바꾸거나 시각을 다시 정한다. [sentAt]은 호출한 쪽이 D-90 규칙(지났으면 지금, 미래면 null)으로 정해 넘긴다 */
	fun reschedule(reminderOption: ReminderOption, fireAt: Instant, sentAt: Instant?, now: Instant) {
		this.reminderOption = reminderOption
		this.fireAt = fireAt
		this.sentAt = sentAt
		this.updatedAt = now
	}
}
