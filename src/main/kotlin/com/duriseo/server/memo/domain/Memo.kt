package com.duriseo.server.memo.domain

import com.duriseo.server.common.persistence.UuidEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 메모 (ERD 3.3 memos, FR-MEMO). 제목은 앞뒤 공백을 지운 값, 본문은 공백·줄바꿈을 보존하되 공백뿐이면 null이다 (D-132).
 * 제목·본문 중 하나 이상이 있어야 하는 규칙과 `memo_date` 범위(1950-01-01 ~ 2050-12-31)는 DB가 아니라 서비스가 검사한다 (DB-13).
 * `pinned_at`·`pinned_by`는 미고정에서 고정으로 바뀔 때만 함께 기록한다 (D-134).
 */
@Entity
@Table(name = "memos")
class Memo(
	spaceId: UUID,
	authorId: UUID,
	title: String?,
	body: String?,
	memoDate: LocalDate?,
	now: Instant,
) : UuidEntity() {

	@Column(name = "space_id", nullable = false)
	var spaceId: UUID = spaceId

	@Column(name = "author_id", nullable = false, updatable = false)
	var authorId: UUID = authorId

	@Column(name = "title", length = 50)
	var title: String? = title

	@Column(name = "body", length = 5000)
	var body: String? = body

	@Column(name = "memo_date")
	var memoDate: LocalDate? = memoDate

	@Column(name = "pinned_at")
	var pinnedAt: Instant? = null

	@Column(name = "pinned_by")
	var pinnedBy: UUID? = null

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant = now

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = now
}
