package com.duriseo.server.memo.application

import com.duriseo.server.common.access.SpaceAccessPolicy
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.DateRangeQuery
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.memo.domain.Memo
import com.duriseo.server.memo.domain.MemoFavoriteRepository
import com.duriseo.server.memo.domain.MemoRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** `GET /memos` 한 페이지. [totalCount]는 요청한 `view`의 전체 개수(고정 제외), [favoriteCount]는 내 즐겨찾기 중 고정되지 않은 것의 개수 */
data class MemoPage(val items: List<MemoView>, val nextCursor: String?, val totalCount: Long, val favoriteCount: Long)

/**
 * 메모 조회 (FR-MEMO-01·03, FR-CAL-01 AC2, R-01·R-06·R-15). 요청자의 활성 공간 것만 읽으므로 다른 공간의 메모는 나오지 않는다.
 * `isFavorite`는 요청자 본인의 즐겨찾기만 본다 (R-06).
 */
@Service
class MemoQueryService(
	private val policy: SpaceAccessPolicy,
	private val memoRepository: MemoRepository,
	private val favoriteRepository: MemoFavoriteRepository,
) {

	/**
	 * `GET /memos?view=&cursor=&size=` (FR-MEMO-01, D-130). 고정되지 않은 메모만 준다(고정 메모는 어느 `view`에도 없다).
	 * 정렬 키는 (`created_at`, `id`)이고 커서는 앞 페이지 마지막 항목의 키다: `newest`·`favorites`는 커서보다 작은 것, `oldest`는 큰 것.
	 * 같은 `created_at`이 페이지 경계에 걸려도 빠지거나 겹치지 않는다.
	 */
	@Transactional(readOnly = true)
	fun list(requesterId: UUID, rawView: String?, rawCursor: String?, rawSize: String?): MemoPage {
		val query = MemoCursor.parse(rawView, rawCursor, rawSize)
		val spaceId = activeSpaceId(requesterId)
		val limit = query.size + 1
		val cursor = query.cursor
		val found = when (query.view) {
			MemoListView.NEWEST ->
				if (cursor == null) {
					memoRepository.findNewestFirstPage(spaceId, limit)
				} else {
					memoRepository.findNewestAfter(spaceId, cursor.createdAt, cursor.id, limit)
				}
			MemoListView.OLDEST ->
				if (cursor == null) {
					memoRepository.findOldestFirstPage(spaceId, limit)
				} else {
					memoRepository.findOldestAfter(spaceId, cursor.createdAt, cursor.id, limit)
				}
			MemoListView.FAVORITES ->
				if (cursor == null) {
					memoRepository.findFavoritesFirstPage(spaceId, requesterId, limit)
				} else {
					memoRepository.findFavoritesAfter(spaceId, requesterId, cursor.createdAt, cursor.id, limit)
				}
		}
		val page = found.take(query.size)
		val next = if (found.size > page.size) page.last().let { MemoCursor.encode(query.view, MemoCursorKey(it.createdAt, it.requireId())) } else null
		val favoriteCount = memoRepository.countUnpinnedFavorites(spaceId, requesterId)
		val totalCount = if (query.view == MemoListView.FAVORITES) favoriteCount else memoRepository.countUnpinned(spaceId)
		return MemoPage(viewsOf(requesterId, page), next, totalCount, favoriteCount)
	}

	/** `GET /memos/pinned` (FR-MEMO-05 AC2·AC3): 고정 메모 전체를 `pinned_at` 내림차순 → `id` 내림차순으로. 페이지 없음 */
	@Transactional(readOnly = true)
	fun pinned(requesterId: UUID): List<MemoView> = viewsOf(requesterId, memoRepository.findPinned(activeSpaceId(requesterId)))

	/**
	 * `GET /memos/dated?from=&to=` (캘린더용, FR-CAL-01 AC2·AC6, R-01, R-15). 범위에 `memo_date`가 있는 메모를 고정 여부와 관계없이
	 * `memo_date` 오름차순 → `created_at` 내림차순 → `id` 내림차순으로. 검사(필수·형식·순서·3개월)는 요청받은 값 그대로 먼저 하고,
	 * 그 뒤 1950-01-01 ~ 2050-12-31로 잘라낸다. 잘라낸 구간이 비면 빈 목록이다.
	 */
	@Transactional(readOnly = true)
	fun dated(requesterId: UUID, rawFrom: String?, rawTo: String?): List<MemoView> {
		val range = DateRangeQuery.parseAndClamp(rawFrom, rawTo, MemoWriteRules.MIN_DATE, MemoWriteRules.MAX_DATE)
		val spaceId = activeSpaceId(requesterId)
		if (range == null) return emptyList()
		return viewsOf(requesterId, memoRepository.findDated(spaceId, range.first, range.second))
	}

	/** `GET /memos/{id}` (FR-MEMO-03, R-01, R-06). 다른 공간·없는 메모는 같은 404 `NOT_FOUND` */
	@Transactional(readOnly = true)
	fun get(requesterId: UUID, memoId: UUID): MemoView {
		policy.requireInActiveSpace(requesterId, memoRepository.findSpaceIdById(memoId))
		val memo = memoRepository.findByIdOrNull(memoId)
		policy.requireInActiveSpace(requesterId, memo?.spaceId)
		checkNotNull(memo)
		return viewsOf(requesterId, listOf(memo)).single()
	}

	private fun viewsOf(requesterId: UUID, memos: List<Memo>): List<MemoView> {
		if (memos.isEmpty()) return emptyList()
		val favorites = favoriteRepository.findFavoriteMemoIds(requesterId, memos.map { it.requireId() }).toSet()
		return memos.map { MemoView.of(it, it.requireId() in favorites) }
	}

	/** 활성 공간이 없는 사용자는 인증된 사용자가 아니므로 401 SESSION_INVALID로 처리한다 (500이 나지 않게, 기념일 `past`와 같다) */
	private fun activeSpaceId(requesterId: UUID): UUID =
		policy.activeSpaceIdOf(requesterId) ?: throw ApiException(ErrorCode.SESSION_INVALID, "user has no active space")
}
