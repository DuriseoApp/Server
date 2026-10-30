package com.duriseo.server.couple.domain

import com.duriseo.server.common.persistence.UuidEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * 커플 연결용 초대코드 (ERD 3.2 invite_codes). 코드는 대문자 6자리(31자 집합)로 저장한다.
 * 유효한 코드 = `used_at IS NULL AND revoked_at IS NULL AND expires_at > now`. 새 코드는 `InviteCodeService`가
 * 네이티브 INSERT … ON CONFLICT로 넣으므로(코드 충돌 재시도) 이 클래스는 읽기와 잠금에 쓴다.
 */
@Entity
@Table(name = "invite_codes")
class InviteCode(code: String, creatorUserId: UUID, expiresAt: Instant, createdAt: Instant) : UuidEntity() {

	// DB 컬럼이 char(6)라 스키마 검증이 CHAR 타입을 기대한다
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "code", nullable = false, length = 6)
	var code: String = code

	@Column(name = "creator_user_id", nullable = false, updatable = false)
	var creatorUserId: UUID = creatorUserId

	@Column(name = "expires_at", nullable = false)
	var expiresAt: Instant = expiresAt

	@Column(name = "used_at")
	var usedAt: Instant? = null

	@Column(name = "used_by_user_id")
	var usedByUserId: UUID? = null

	@Column(name = "revoked_at")
	var revokedAt: Instant? = null

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant = createdAt
}
