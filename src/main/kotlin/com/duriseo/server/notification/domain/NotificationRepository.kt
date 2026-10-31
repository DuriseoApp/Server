package com.duriseo.server.notification.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface NotificationRepository : JpaRepository<Notification, UUID> {

	/** `space_id`가 [spaceId]인 알림을 모두 지운다 (두 사람 모두, API.md 4.5 3단계, D-45). 지운 행 수를 돌려준다 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Notification n where n.spaceId = :spaceId")
	fun deleteAllBySpaceId(@Param("spaceId") spaceId: UUID): Int

	/** 사용자가 받은 알림을 모두 지운다 (탈퇴 익명화, ERD 4.3 4단계). 지운 행 수를 돌려준다 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Notification n where n.userId = :userId")
	fun deleteAllByUserId(@Param("userId") userId: UUID): Int
}
