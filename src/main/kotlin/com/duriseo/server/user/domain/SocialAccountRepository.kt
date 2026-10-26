package com.duriseo.server.user.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface SocialAccountRepository : JpaRepository<SocialAccount, UUID> {

	fun findAllByUserId(userId: UUID): List<SocialAccount>

	/** 그 소셜 계정을 쓰는 사용자 ID. 없으면 null */
	@Query("select s.userId from SocialAccount s where s.provider = :provider and s.providerUserId = :providerUserId")
	fun findUserIdByProvider(
		@Param("provider") provider: SocialProvider,
		@Param("providerUserId") providerUserId: String,
	): UUID?
}
