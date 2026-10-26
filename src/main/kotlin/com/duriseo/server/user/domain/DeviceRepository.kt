package com.duriseo.server.user.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface DeviceRepository : JpaRepository<Device, UUID> {

	fun findByFcmToken(fcmToken: String): Device?

	fun findAllByUserId(userId: UUID): List<Device>

	/** 로그인 3종·로그아웃·탈퇴가 그 사용자의 기기를 모두 지운다 (DB-09) */
	@Modifying(flushAutomatically = true)
	@Query("delete from Device d where d.userId = :userId")
	fun deleteAllOfUser(@Param("userId") userId: UUID): Int
}
