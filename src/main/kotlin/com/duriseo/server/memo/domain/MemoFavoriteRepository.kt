package com.duriseo.server.memo.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface MemoFavoriteRepository : JpaRepository<MemoFavorite, MemoFavoriteId> {

	/** [memoIds] 중 [userId]가 즐겨찾기한 메모 ID (R-06: 요청자 본인의 행만 본다) */
	@Query("select f.memoId from MemoFavorite f where f.userId = :userId and f.memoId in :memoIds")
	fun findFavoriteMemoIds(@Param("userId") userId: UUID, @Param("memoIds") memoIds: Collection<UUID>): List<UUID>

	/** 즐겨찾기 추가. 이미 있으면 아무것도 바꾸지 않는다 (멱등, D-134). 넣은 행 수(0 또는 1)를 돌려준다 */
	@Modifying(flushAutomatically = true)
	@Query(
		"insert into memo_favorites (memo_id, user_id, created_at) values (:memoId, :userId, :now) on conflict do nothing",
		nativeQuery = true,
	)
	fun insertIfAbsent(@Param("memoId") memoId: UUID, @Param("userId") userId: UUID, @Param("now") now: Instant): Int

	/** 즐겨찾기 해제. 없으면 0줄이고 오류가 아니다 (멱등, D-134) */
	@Modifying(flushAutomatically = true)
	@Query("delete from MemoFavorite f where f.memoId = :memoId and f.userId = :userId")
	fun deleteOne(@Param("memoId") memoId: UUID, @Param("userId") userId: UUID): Int

	/** 탈퇴하는 사용자의 즐겨찾기를 모두 지운다 (ERD 4.3 4단계) */
	@Modifying(flushAutomatically = true)
	@Query("delete from MemoFavorite f where f.userId = :userId")
	fun deleteAllByUser(@Param("userId") userId: UUID): Int
}
