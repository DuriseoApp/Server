package com.duriseo.server.couple.domain

import com.duriseo.server.common.persistence.UuidEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** 초대코드 입력 시도 한 번 (ERD 3.2 invite_code_attempts, NFR-SEC-03). 오입력 제한이 최근 1시간 실패 수를 센다 */
@Entity
@Table(name = "invite_code_attempts")
class InviteCodeAttempt(userId: UUID, succeeded: Boolean, attemptedAt: Instant) : UuidEntity() {

	@Column(name = "user_id", nullable = false, updatable = false)
	var userId: UUID = userId

	@Column(name = "succeeded", nullable = false, updatable = false)
	var succeeded: Boolean = succeeded

	@Column(name = "attempted_at", nullable = false, updatable = false)
	var attemptedAt: Instant = attemptedAt
}
