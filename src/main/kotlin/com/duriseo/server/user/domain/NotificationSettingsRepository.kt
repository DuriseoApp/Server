package com.duriseo.server.user.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface NotificationSettingsRepository : JpaRepository<NotificationSettings, UUID> {

	/** 행을 잠그고(SELECT … FOR UPDATE) 읽는다. 두 `PATCH`가 겹쳐도 뒤 요청이 앞 요청의 변경을 옛 값으로 덮지 않게 한다 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from NotificationSettings s where s.userId = :userId")
	fun findByUserIdForUpdate(@Param("userId") userId: UUID): NotificationSettings?
}
