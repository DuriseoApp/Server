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

	/** "지난 기념일" (FR-ANNIV-04 AC3, D-71): `repeats_yearly = false`이고 `date`가 [today]보다 앞인 직접 기념일. 날짜 최근 순, 같은 날이면 ID 내림차순 */
	@Query(
		"select a from Anniversary a where a.spaceId = :spaceId and a.repeatsYearly = false and a.date < :today " +
			"order by a.date desc, a.id desc",
	)
	fun findPast(@Param("spaceId") spaceId: UUID, @Param("today") today: LocalDate): List<Anniversary>
}
