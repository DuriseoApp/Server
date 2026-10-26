package com.duriseo.server.space.domain

import com.duriseo.server.common.persistence.UuidEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

/** 개인 공간 또는 커플 공간 (ERD 3.2 spaces). 커플 전용 컬럼은 단위 2가 쓴다 */
@Entity
@Table(name = "spaces")
class Space(type: SpaceType, now: Instant) : UuidEntity() {

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, length = 10)
	var type: SpaceType = type

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 10)
	var status: SpaceStatus = SpaceStatus.ACTIVE

	@Column(name = "start_date")
	var startDate: LocalDate? = null

	@Column(name = "together_color", length = 20)
	var togetherColor: String? = null

	@Column(name = "unlinked_at")
	var unlinkedAt: Instant? = null

	@Column(name = "purge_at")
	var purgeAt: Instant? = null

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant = now

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = now
}
