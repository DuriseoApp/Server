package com.duriseo.server.memo.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface MemoRepository : JpaRepository<Memo, UUID> {

	/** 메모가 속한 공간 ID만 읽는다 (R-01 검사용, 이어서 잠그고 다시 읽는다). 없는 메모면 null */
	@Query("select m.spaceId from Memo m where m.id = :id")
	fun findSpaceIdById(@Param("id") id: UUID): UUID?

	/** 메모 행을 잠그고(SELECT … FOR UPDATE) 읽는다. 수정·삭제·고정이 겹치지 않게 한다. 공간·사용자 행은 잠그지 않는다 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from Memo m where m.id = :id")
	fun findByIdForUpdate(@Param("id") id: UUID): Memo?

	/** [spaceIds] 공간들의 메모 수 (혼자 쓴 기록이 있는지, 가져오기 대기 공간의 `importCounts`) */
	fun countBySpaceIdIn(spaceIds: Collection<UUID>): Long

	/** [fromSpaceId]의 메모를 모두 [toSpaceId]로 옮긴다 (`space_id`만 바꾼다. 작성자·시각·고정 상태는 그대로, API.md 4.4 9단계). 옮긴 행 수 */
	@Modifying(flushAutomatically = true)
	@Query("update Memo m set m.spaceId = :toSpaceId where m.spaceId = :fromSpaceId")
	fun moveSpace(@Param("fromSpaceId") fromSpaceId: UUID, @Param("toSpaceId") toSpaceId: UUID): Int

	// ── GET /memos: 고정되지 않은 메모 (D-130). id 비교와 정렬은 PostgreSQL uuid 비교다 ──────

	/** `view = newest` 첫 페이지: (`created_at`, `id`) 내림차순 */
	@Query(
		"select m.* from memos m where m.space_id = :spaceId and m.pinned_at is null " +
			"order by m.created_at desc, m.id desc limit :limit",
		nativeQuery = true,
	)
	fun findNewestFirstPage(@Param("spaceId") spaceId: UUID, @Param("limit") limit: Int): List<Memo>

	/** `view = newest` 커서 다음: (`created_at`, `id`)가 커서보다 **작은** 것 */
	@Query(
		"select m.* from memos m where m.space_id = :spaceId and m.pinned_at is null " +
			"and (m.created_at, m.id) < (:cursorAt, :cursorId) " +
			"order by m.created_at desc, m.id desc limit :limit",
		nativeQuery = true,
	)
	fun findNewestAfter(
		@Param("spaceId") spaceId: UUID,
		@Param("cursorAt") cursorAt: Instant,
		@Param("cursorId") cursorId: UUID,
		@Param("limit") limit: Int,
	): List<Memo>

	/** `view = oldest` 첫 페이지: (`created_at`, `id`) 오름차순 */
	@Query(
		"select m.* from memos m where m.space_id = :spaceId and m.pinned_at is null " +
			"order by m.created_at asc, m.id asc limit :limit",
		nativeQuery = true,
	)
	fun findOldestFirstPage(@Param("spaceId") spaceId: UUID, @Param("limit") limit: Int): List<Memo>

	/** `view = oldest` 커서 다음: (`created_at`, `id`)가 커서보다 **큰** 것 */
	@Query(
		"select m.* from memos m where m.space_id = :spaceId and m.pinned_at is null " +
			"and (m.created_at, m.id) > (:cursorAt, :cursorId) " +
			"order by m.created_at asc, m.id asc limit :limit",
		nativeQuery = true,
	)
	fun findOldestAfter(
		@Param("spaceId") spaceId: UUID,
		@Param("cursorAt") cursorAt: Instant,
		@Param("cursorId") cursorId: UUID,
		@Param("limit") limit: Int,
	): List<Memo>

	/** `view = favorites` 첫 페이지: [userId]가 즐겨찾기한 고정되지 않은 메모, 메모의 `created_at` 내림차순 (R-06) */
	@Query(
		"select m.* from memos m join memo_favorites f on f.memo_id = m.id and f.user_id = :userId " +
			"where m.space_id = :spaceId and m.pinned_at is null " +
			"order by m.created_at desc, m.id desc limit :limit",
		nativeQuery = true,
	)
	fun findFavoritesFirstPage(@Param("spaceId") spaceId: UUID, @Param("userId") userId: UUID, @Param("limit") limit: Int): List<Memo>

	/** `view = favorites` 커서 다음: (`created_at`, `id`)가 커서보다 **작은** 것 */
	@Query(
		"select m.* from memos m join memo_favorites f on f.memo_id = m.id and f.user_id = :userId " +
			"where m.space_id = :spaceId and m.pinned_at is null " +
			"and (m.created_at, m.id) < (:cursorAt, :cursorId) " +
			"order by m.created_at desc, m.id desc limit :limit",
		nativeQuery = true,
	)
	fun findFavoritesAfter(
		@Param("spaceId") spaceId: UUID,
		@Param("userId") userId: UUID,
		@Param("cursorAt") cursorAt: Instant,
		@Param("cursorId") cursorId: UUID,
		@Param("limit") limit: Int,
	): List<Memo>

	/** `totalCount`(`newest`·`oldest`): 고정되지 않은 메모 수 */
	@Query("select count(m) from Memo m where m.spaceId = :spaceId and m.pinnedAt is null")
	fun countUnpinned(@Param("spaceId") spaceId: UUID): Long

	/** `favoriteCount`(`favorites`의 `totalCount`도 같다): [userId]의 즐겨찾기 중 고정되지 않은 메모 수 (R-06) */
	@Query(
		"select count(m) from Memo m where m.spaceId = :spaceId and m.pinnedAt is null " +
			"and exists (select 1 from MemoFavorite f where f.memoId = m.id and f.userId = :userId)",
	)
	fun countUnpinnedFavorites(@Param("spaceId") spaceId: UUID, @Param("userId") userId: UUID): Long

	/** `GET /memos/pinned`: 고정 메모 전체, `pinned_at` 내림차순 → `id` 내림차순 */
	@Query(
		"select m.* from memos m where m.space_id = :spaceId and m.pinned_at is not null order by m.pinned_at desc, m.id desc",
		nativeQuery = true,
	)
	fun findPinned(@Param("spaceId") spaceId: UUID): List<Memo>

	/** `GET /memos/dated`: [from]~[to](양 끝 포함)에 `memo_date`가 있는 메모. 고정 여부와 관계없다. `memo_date` 오름차순 → `created_at` 내림차순 → `id` 내림차순 */
	@Query(
		"select m.* from memos m where m.space_id = :spaceId and m.memo_date between :from and :to " +
			"order by m.memo_date asc, m.created_at desc, m.id desc",
		nativeQuery = true,
	)
	fun findDated(@Param("spaceId") spaceId: UUID, @Param("from") from: LocalDate, @Param("to") to: LocalDate): List<Memo>
}
