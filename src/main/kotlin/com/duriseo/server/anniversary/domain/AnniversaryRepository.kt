package com.duriseo.server.anniversary.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface AnniversaryRepository : JpaRepository<Anniversary, UUID> {

	/** 기념일이 속한 공간 ID만 읽는다 (R-01 검사용, 이어서 잠그고 다시 읽는다). 없는 기념일이면 null */
	@Query("select a.spaceId from Anniversary a where a.id = :id")
	fun findSpaceIdById(@Param("id") id: UUID): UUID?

	/** [spaceIds] 공간들의 직접 기념일 수 (혼자 쓴 기록이 있는지, 가져오기 대기 공간의 `importCounts`) */
	fun countBySpaceIdIn(spaceIds: Collection<UUID>): Long

	/** [fromSpaceId]의 직접 기념일을 모두 [toSpaceId]로 옮긴다 (`space_id`만 바꾼다. 작성자·시각은 그대로, API.md 4.4 9단계). 옮긴 행 수를 돌려준다 */
	@Modifying(flushAutomatically = true)
	@Query("update Anniversary a set a.spaceId = :toSpaceId where a.spaceId = :fromSpaceId")
	fun moveSpace(@Param("fromSpaceId") fromSpaceId: UUID, @Param("toSpaceId") toSpaceId: UUID): Int

	/** 기념일 행을 잠그고(SELECT … FOR UPDATE) 읽는다. 수정·삭제가 겹치지 않게 한다. 공간·사용자 행은 잠그지 않는다 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from Anniversary a where a.id = :id")
	fun findByIdForUpdate(@Param("id") id: UUID): Anniversary?

	/** 공간의 직접 기념일 전체 (목록의 발생일 계산용. 공간당 수가 작다) */
	fun findAllBySpaceId(spaceId: UUID): List<Anniversary>

	/**
	 * "지난 기념일" 첫 페이지 (FR-ANNIV-04 AC3, D-71): `repeats_yearly = false`이고 `date`가 [today]보다 앞인 직접 기념일.
	 * `date` 내림차순 → `id` 내림차순, 최대 [limit]개. `id` 비교와 정렬은 PostgreSQL `uuid` 비교(부호 없는 바이트 순)다 (API.md 3.3 리드 보충)
	 */
	@Query(
		"select a.* from anniversaries a where a.space_id = :spaceId and a.repeats_yearly = false and a.date < :today " +
			"order by a.date desc, a.id desc limit :limit",
		nativeQuery = true,
	)
	fun findPastFirstPage(
		@Param("spaceId") spaceId: UUID,
		@Param("today") today: LocalDate,
		@Param("limit") limit: Int,
	): List<Anniversary>

	/** "지난 기념일"의 커서 다음 페이지: (`date`, `id`)가 커서보다 **작은** 것 (내림차순 방향, API.md 3.3 "커서 정렬 키") */
	@Query(
		"select a.* from anniversaries a where a.space_id = :spaceId and a.repeats_yearly = false and a.date < :today " +
			"and (a.date < :cursorDate or (a.date = :cursorDate and a.id < :cursorId)) " +
			"order by a.date desc, a.id desc limit :limit",
		nativeQuery = true,
	)
	fun findPastAfter(
		@Param("spaceId") spaceId: UUID,
		@Param("today") today: LocalDate,
		@Param("cursorDate") cursorDate: LocalDate,
		@Param("cursorId") cursorId: UUID,
		@Param("limit") limit: Int,
	): List<Anniversary>

	/** "지난 기념일" 전체 개수 (`totalCount`, 페이지와 관계없음, D-71) */
	@Query("select count(a) from Anniversary a where a.spaceId = :spaceId and a.repeatsYearly = false and a.date < :today")
	fun countPast(@Param("spaceId") spaceId: UUID, @Param("today") today: LocalDate): Long
}
