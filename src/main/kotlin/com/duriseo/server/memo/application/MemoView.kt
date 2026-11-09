package com.duriseo.server.memo.application

import com.duriseo.server.memo.domain.Memo
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** API.md 3.4 `Memo`. [isFavorite]는 **요청자 기준**이다 (R-06). 일시는 초 단위로 내보낸다 (API.md 1장) */
data class MemoView(
	val id: UUID,
	val title: String?,
	val body: String?,
	val memoDate: LocalDate?,
	val authorId: UUID,
	val isPinned: Boolean,
	val pinnedAt: Instant?,
	val isFavorite: Boolean,
	val createdAt: Instant,
	val updatedAt: Instant,
) {
	companion object {
		fun of(memo: Memo, isFavorite: Boolean) = MemoView(
			id = memo.requireId(),
			title = memo.title,
			body = memo.body,
			memoDate = memo.memoDate,
			authorId = memo.authorId,
			isPinned = memo.pinnedAt != null,
			pinnedAt = memo.pinnedAt?.truncatedTo(ChronoUnit.SECONDS),
			isFavorite = isFavorite,
			createdAt = memo.createdAt.truncatedTo(ChronoUnit.SECONDS),
			updatedAt = memo.updatedAt.truncatedTo(ChronoUnit.SECONDS),
		)
	}
}
