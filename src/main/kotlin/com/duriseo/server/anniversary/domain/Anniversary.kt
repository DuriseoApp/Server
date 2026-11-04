package com.duriseo.server.anniversary.domain

import com.duriseo.server.common.persistence.UuidEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 직접 기념일 (ERD 3.3 anniversaries, FR-ANNIV-03). 자동 기념일은 저장하지 않는다 (DB-01).
 * `date`의 범위(1950-01-01 ~ 2050-12-31)는 DB가 아니라 서비스가 검사한다 (DB-13).
 */
@Entity
@Table(name = "anniversaries")
class Anniversary(
	spaceId: UUID,
	authorId: UUID,
	name: String,
	date: LocalDate,
	repeatsYearly: Boolean,
	now: Instant,
) : UuidEntity() {

	@Column(name = "space_id", nullable = false)
	var spaceId: UUID = spaceId

	@Column(name = "author_id", nullable = false, updatable = false)
	var authorId: UUID = authorId

	@Column(name = "name", nullable = false, length = 30)
	var name: String = name

	@Column(name = "date", nullable = false)
	var date: LocalDate = date

	@Column(name = "repeats_yearly", nullable = false)
	var repeatsYearly: Boolean = repeatsYearly

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant = now

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = now
}
