package com.duriseo.server.memo.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/** `memo_favorites`의 기본 키 (`memo_id`, `user_id`) */
data class MemoFavoriteId(var memoId: UUID? = null, var userId: UUID? = null) : Serializable

/** 사용자별 즐겨찾기 (ERD 3.3 memo_favorites, D-24). 본인의 행만 만들고 지운다 (R-06) */
@Entity
@Table(name = "memo_favorites")
@IdClass(MemoFavoriteId::class)
class MemoFavorite(
	memoId: UUID,
	userId: UUID,
	now: Instant,
) {

	@Id
	@Column(name = "memo_id", nullable = false, updatable = false)
	var memoId: UUID = memoId

	@Id
	@Column(name = "user_id", nullable = false, updatable = false)
	var userId: UUID = userId

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant = now
}
