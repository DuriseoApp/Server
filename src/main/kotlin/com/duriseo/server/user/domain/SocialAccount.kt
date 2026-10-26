package com.duriseo.server.user.domain

import com.duriseo.server.common.persistence.UuidEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** 회원의 소셜 로그인 수단 (ERD 3.1 social_accounts). 이메일 등은 저장하지 않는다 (NFR-PRIV-01) */
@Entity
@Table(name = "social_accounts")
class SocialAccount(userId: UUID, provider: SocialProvider, providerUserId: String, now: Instant) : UuidEntity() {

	@Column(name = "user_id", nullable = false, updatable = false)
	var userId: UUID = userId

	@Enumerated(EnumType.STRING)
	@Column(name = "provider", nullable = false, length = 10)
	var provider: SocialProvider = provider

	@Column(name = "provider_user_id", nullable = false, length = 100)
	var providerUserId: String = providerUserId

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant = now
}
