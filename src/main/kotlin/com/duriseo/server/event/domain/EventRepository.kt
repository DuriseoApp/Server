package com.duriseo.server.event.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface EventRepository : JpaRepository<Event, UUID> {

	/**
	 * 일정이 속한 공간 ID만 읽는다. 엔티티를 영속성 컨텍스트에 올리지 않으려고 값만 읽는다 (R-01 검사용, 이어서 잠그고 다시 읽는다).
	 * 없는 일정이면 null.
	 */
	@Query("select e.spaceId from Event e where e.id = :id")
	fun findSpaceIdById(@Param("id") id: UUID): UUID?

	/** [spaceIds] 공간들의 일정 수 (가져오기 대기 공간의 `importCounts`, 혼자 쓴 기록이 있는지) */
	fun countBySpaceIdIn(spaceIds: Collection<UUID>): Long

	/** [fromSpaceId]의 일정을 모두 [toSpaceId]로 옮긴다 (`space_id`만 바꾼다. 작성자·시각은 그대로, API.md 4.4 9단계). 옮긴 행 수를 돌려준다 */
	@Modifying(flushAutomatically = true)
	@Query("update Event e set e.spaceId = :toSpaceId where e.spaceId = :fromSpaceId")
	fun moveSpace(@Param("fromSpaceId") fromSpaceId: UUID, @Param("toSpaceId") toSpaceId: UUID): Int

	/** 일정 행을 잠그고(SELECT … FOR UPDATE) 읽는다. 수정·삭제가 겹치지 않게 한다 (NFR-SYNC-03). 공간·사용자 행은 잠그지 않는다 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from Event e where e.id = :id")
	fun findByIdForUpdate(@Param("id") id: UUID): Event?

	/**
	 * 범위에 하루라도 걸치는 일정 (API.md 3.3 "조회 규칙"). 종일: `start_date <= to AND end_date >= from`.
	 * 시간 일정: `start_at < toExclusive AND end_at >= fromInclusive` (요청자 시간대의 `from` 00:00 ~ `to` 다음 날 00:00을 UTC로 바꾼 값).
	 */
	@Query(
		"select e from Event e where e.spaceId = :spaceId and (" +
			"(e.isAllDay = true and e.startDate <= :toDate and e.endDate >= :fromDate) or " +
			"(e.isAllDay = false and e.startAt < :toExclusive and e.endAt >= :fromInclusive))",
	)
	fun findOverlapping(
		@Param("spaceId") spaceId: UUID,
		@Param("fromDate") fromDate: LocalDate,
		@Param("toDate") toDate: LocalDate,
		@Param("fromInclusive") fromInclusive: Instant,
		@Param("toExclusive") toExclusive: Instant,
	): List<Event>
}
