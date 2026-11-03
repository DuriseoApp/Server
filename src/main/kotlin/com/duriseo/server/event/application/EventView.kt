package com.duriseo.server.event.application

import com.duriseo.server.event.domain.Event
import com.duriseo.server.event.domain.EventReminder
import com.duriseo.server.event.domain.ReminderOption
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 일정 하나의 내용 (API.md 3.3 `Event`). 시간은 종일이면 날짜 둘, 아니면 일시 둘만 값이 있다.
 * [reminder]는 **요청자 자신의** 알림만이다 (R-07).
 */
data class EventView(
	val id: UUID,
	val title: String,
	val note: String?,
	val isAllDay: Boolean,
	val startAt: Instant?,
	val endAt: Instant?,
	val startDate: LocalDate?,
	val endDate: LocalDate?,
	val isTogether: Boolean,
	val authorId: UUID,
	val updatedBy: UUID?,
	val reminder: EventReminderView?,
	val createdAt: Instant,
	val updatedAt: Instant,
) {
	companion object {
		fun of(event: Event, reminder: EventReminder?) = EventView(
			id = event.requireId(),
			title = event.title,
			note = event.note,
			isAllDay = event.isAllDay,
			startAt = event.startAt,
			endAt = event.endAt,
			startDate = event.startDate,
			endDate = event.endDate,
			isTogether = event.isTogether,
			authorId = event.authorId,
			updatedBy = event.updatedBy,
			reminder = reminder?.let { EventReminderView(it.reminderOption, it.fireAt) },
			createdAt = event.createdAt,
			updatedAt = event.updatedAt,
		)
	}
}

/** API.md 3.3 `EventReminder`. `sent_at`은 내보내지 않는다 */
data class EventReminderView(val option: ReminderOption, val fireAt: Instant)
