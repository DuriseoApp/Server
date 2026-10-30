package com.duriseo.server.space.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface SpaceMemberRepository : JpaRepository<SpaceMember, UUID> {

	/** 사용자의 활성 소속 (`left_at IS NULL`). 부분 UNIQUE라 최대 하나 */
	fun findByUserIdAndLeftAtIsNull(userId: UUID): SpaceMember?

	fun findAllBySpaceIdAndLeftAtIsNull(spaceId: UUID): List<SpaceMember>

	/** 소속에 `left_at`이 있는 [type] 공간의 수. PERSONAL이면 가져오기 대기 중인 개인 공간 (API.md 4.4 10단계) */
	@Query(
		"select count(m) from SpaceMember m, Space s " +
			"where m.spaceId = s.id and m.userId = :userId and m.leftAt is not null and s.type = :type",
	)
	fun countLeftSpacesOfType(@Param("userId") userId: UUID, @Param("type") type: SpaceType): Long

	/** 사용자의 활성 공간 종류. 엔티티를 읽어 영속성 컨텍스트에 남기지 않으려고 값만 읽는다. 활성 소속이 없으면 null */
	@Query(
		"select s.type from SpaceMember m, Space s " +
			"where m.spaceId = s.id and m.userId = :userId and m.leftAt is null",
	)
	fun findActiveSpaceType(@Param("userId") userId: UUID): SpaceType?

	/**
	 * 사용자가 [spaceId]에서 활성 소속을 떠난다 (`left_at` 기록). 바뀐 행 수를 돌려준다.
	 * 연결 때 기록이 있는 개인 공간을 가져오기 대기로 남기는 데 쓴다 (API.md 4.4 5단계). 영속성 컨텍스트를 비운다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update SpaceMember m set m.leftAt = :now where m.userId = :userId and m.spaceId = :spaceId and m.leftAt is null")
	fun closeActiveMembership(
		@Param("userId") userId: UUID,
		@Param("spaceId") spaceId: UUID,
		@Param("now") now: Instant,
	): Int

	/** [spaceId]의 모든 소속을 다시 활성으로 돌린다 (`left_at` 비움, FR-COUPLE-06). 영속성 컨텍스트를 비운다 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update SpaceMember m set m.leftAt = null where m.spaceId = :spaceId")
	fun reopenAllOf(@Param("spaceId") spaceId: UUID): Int
}
