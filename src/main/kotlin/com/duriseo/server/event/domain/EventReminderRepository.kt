package com.duriseo.server.event.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface EventReminderRepository : JpaRepository<EventReminder, UUID> {

	/** 일정 하나에 대한 한 사용자의 알림. 없으면 null ("없음") */
	fun findByEventIdAndUserId(eventId: UUID, userId: UUID): EventReminder?

	/** 일정 여러 개에 대한 한 사용자의 알림 (`GET /events`). 다른 사용자의 알림은 읽지 않는다 (R-07) */
	fun findAllByEventIdInAndUserId(eventIds: Collection<UUID>, userId: UUID): List<EventReminder>

	/** 일정 하나에 알림을 건 모든 사용자의 행 (일시가 바뀔 때 다시 계산, D-91) */
	fun findAllByEventId(eventId: UUID): List<EventReminder>

	/** 한 사용자의 아직 보내지 않은(`sent_at IS NULL`) [options] 알림 (사용자 시간대가 바뀔 때 다시 계산, D-91) */
	fun findAllByUserIdAndReminderOptionInAndSentAtIsNull(userId: UUID, options: Collection<ReminderOption>): List<EventReminder>

	/** 한 사용자의 한 일정 알림을 지운다 (`DELETE /events/{id}/reminder`, R-07). 없어도 오류가 아니다. 지운 행 수를 돌려준다 */
	@Modifying(flushAutomatically = true)
	@Query("delete from EventReminder r where r.eventId = :eventId and r.userId = :userId")
	fun deleteOfEventAndUser(@Param("eventId") eventId: UUID, @Param("userId") userId: UUID): Int

	/** 일정 하나의 알림을 모든 사용자 것 지운다 (종일 ↔ 시간 일정으로 바뀔 때, D-91) */
	@Modifying(flushAutomatically = true)
	@Query("delete from EventReminder r where r.eventId = :eventId")
	fun deleteOfEvent(@Param("eventId") eventId: UUID): Int

	/** 한 사용자의 알림을 모두 지운다 (탈퇴 익명화, ERD 4.3 4단계) */
	@Modifying(flushAutomatically = true)
	@Query("delete from EventReminder r where r.userId = :userId")
	fun deleteOfUser(@Param("userId") userId: UUID): Int

	/** 공간 일정의 알림을 모두 지운다 (연결 해제, API.md 4.5 3단계) */
	@Modifying(flushAutomatically = true)
	@Query("delete from EventReminder r where r.eventId in (select e.id from Event e where e.spaceId = :spaceId)")
	fun deleteOfSpace(@Param("spaceId") spaceId: UUID): Int
}
