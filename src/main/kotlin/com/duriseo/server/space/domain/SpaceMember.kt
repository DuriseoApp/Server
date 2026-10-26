package com.duriseo.server.space.domain

import com.duriseo.server.common.persistence.UuidEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** 사용자와 공간의 소속 (ERD 3.2 space_members). 활성 소속은 `left_at IS NULL`이고 사용자당 하나 */
@Entity
@Table(name = "space_members")
class SpaceMember(spaceId: UUID, userId: UUID, color: String, joinedAt: Instant) : UuidEntity() {

	@Column(name = "space_id", nullable = false, updatable = false)
	var spaceId: UUID = spaceId

	@Column(name = "user_id", nullable = false, updatable = false)
	var userId: UUID = userId

	@Column(name = "color", nullable = false, length = 20)
	var color: String = color

	@Column(name = "joined_at", nullable = false)
	var joinedAt: Instant = joinedAt

	@Column(name = "left_at")
	var leftAt: Instant? = null
}
