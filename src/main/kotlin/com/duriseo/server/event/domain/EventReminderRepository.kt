package com.duriseo.server.event.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface EventReminderRepository : JpaRepository<EventReminder, UUID> {

	/** 일정 하나에 대한 한 사용자의 알림. 없으면 null ("없음") */
	fun findByEventIdAndUserId(eventId: UUID, userId: UUID): EventReminder?

	/** 일정 여러 개에 대한 한 사용자의 알림 (`GET /events`). 다른 사용자의 알림은 읽지 않는다 (R-07) */
	fun findAllByEventIdInAndUserId(eventIds: Collection<UUID>, userId: UUID): List<EventReminder>
}
