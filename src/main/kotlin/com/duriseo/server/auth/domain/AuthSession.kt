package com.duriseo.server.auth.domain

import com.duriseo.server.common.persistence.UuidEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * 서버가 발급한 로그인 세션 (ERD 3.1 auth_sessions). `id`가 access 토큰의 `sid`다.
 * refresh 토큰은 원문을 저장하지 않고 SHA-256 16진수 64자만 저장한다 (D-81).
 * 유효한 세션 = `revoked_at IS NULL AND expires_at > now`. `revoked_at`과 `revoked_reason`은 항상 같이 기록한다.
 */
@Entity
@Table(name = "auth_sessions")
class AuthSession(userId: UUID, refreshTokenHash: String, expiresAt: Instant, now: Instant) : UuidEntity() {

	@Column(name = "user_id", nullable = false, updatable = false)
	var userId: UUID = userId

	// DB 컬럼이 char(64)라 스키마 검증이 CHAR 타입을 기대한다
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "refresh_token_hash", nullable = false, length = 64)
	var refreshTokenHash: String = refreshTokenHash

	@Column(name = "expires_at", nullable = false)
	var expiresAt: Instant = expiresAt

	@Column(name = "last_used_at", nullable = false)
	var lastUsedAt: Instant = now

	@Column(name = "revoked_at")
	var revokedAt: Instant? = null

	@Enumerated(EnumType.STRING)
	@Column(name = "revoked_reason", length = 10)
	var revokedReason: SessionRevokeReason? = null

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant = now

	fun isValidAt(now: Instant): Boolean = revokedAt == null && expiresAt.isAfter(now)

	/** refresh 교체: 새 행을 만들지 않고 해시·만료·last_used_at만 바꾼다. `sid`(= id)는 그대로 (DB-02) */
	fun rotate(newRefreshTokenHash: String, newExpiresAt: Instant, now: Instant) {
		refreshTokenHash = newRefreshTokenHash
		expiresAt = newExpiresAt
		lastUsedAt = now
	}

	fun revoke(reason: SessionRevokeReason, now: Instant) {
		revokedAt = now
		revokedReason = reason
	}
}
