package com.duriseo.server.memo.api

import com.duriseo.server.memo.application.MemoPage
import com.duriseo.server.memo.application.MemoView
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * API.md 3.4 `Memo`: `GET /memos`·`/memos/pinned`·`/memos/dated`의 `items`와 상세·생성·수정 응답이 같은 모양이다.
 * 값이 없는 필드도 `null`로 보낸다 (1.1).
 */
data class MemoResponse(
	val id: UUID,
	val title: String?,
	val body: String?,
	val memoDate: LocalDate?,
	val authorId: UUID,
	// Kotlin의 `isXxx` Boolean이 JSON에서 `xxx`로 바뀌지 않게 이름을 고정한다
	@get:JsonProperty("isPinned") val isPinned: Boolean,
	val pinnedAt: Instant?,
	@get:JsonProperty("isFavorite") val isFavorite: Boolean,
	val createdAt: Instant,
	val updatedAt: Instant,
) {
	companion object {
		fun from(view: MemoView) = MemoResponse(
			view.id, view.title, view.body, view.memoDate, view.authorId, view.isPinned, view.pinnedAt, view.isFavorite,
			view.createdAt, view.updatedAt,
		)
	}
}

/** `GET /memos`: `{items, nextCursor, totalCount, favoriteCount}` */
data class MemoPageResponse(val items: List<MemoResponse>, val nextCursor: String?, val totalCount: Long, val favoriteCount: Long) {
	companion object {
		fun from(page: MemoPage) =
			MemoPageResponse(page.items.map { MemoResponse.from(it) }, page.nextCursor, page.totalCount, page.favoriteCount)
	}
}
