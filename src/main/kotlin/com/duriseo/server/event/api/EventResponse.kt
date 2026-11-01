package com.duriseo.server.event.api

import com.duriseo.server.event.application.EventReminderView
import com.duriseo.server.event.application.EventView
import com.duriseo.server.event.domain.ReminderOption
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * API.md 3.3 `Event`. `GET /events`(`items`), `POST /events`, `GET`·`PATCH /events/{id}`, `PUT /events/{id}/reminder`의 응답 본문.
 * `start`·`end`는 종일이면 `YYYY-MM-DD`, 아니면 ISO 8601 UTC 일시다 (문자열로 내보낸다).
 */
data class EventResponse(
	val id: UUID,
	val title: String,
	val note: String?,
	// Kotlin의 `isXxx` Boolean이 JSON에서 `xxx`로 바뀌지 않게 이름을 고정한다
	@get:JsonProperty("isAllDay") val isAllDay: Boolean,
	val start: String,
	val end: String,
	@get:JsonProperty("isTogether") val isTogether: Boolean,
	val authorId: UUID,
	val updatedBy: UUID?,
	val reminder: EventReminderResponse?,
	val createdAt: Instant,
	val updatedAt: Instant,
) {
	companion object {
		fun from(view: EventView) = EventResponse(
			id = view.id,
			title = view.title,
			note = view.note,
			isAllDay = view.isAllDay,
			start = (view.startDate ?: checkNotNull(view.startAt)).toString(),
			end = (view.endDate ?: checkNotNull(view.endAt)).toString(),
			isTogether = view.isTogether,
			authorId = view.authorId,
			updatedBy = view.updatedBy,
			reminder = view.reminder?.let { EventReminderResponse.from(it) },
			createdAt = view.createdAt,
			updatedAt = view.updatedAt,
		)
	}
}

/** API.md 3.3 `EventReminder`: 요청자 자신의 알림만 (R-07). `sent_at`은 내보내지 않는다 */
data class EventReminderResponse(val option: ReminderOption, val fireAt: Instant) {
	companion object {
		fun from(view: EventReminderView) = EventReminderResponse(view.option, view.fireAt)
	}
}
