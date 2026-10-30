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

	/** 사용자의 커플 공간 상대(같은 활성 [type] 공간의 다른 활성 구성원). 잠그지 않고 값만 읽는다. 커플이 아니면 null */
	@Query(
		"select p.userId from SpaceMember m, SpaceMember p, Space s " +
			"where m.userId = :userId and m.leftAt is null and s.id = m.spaceId and s.type = :type " +
			"and p.spaceId = m.spaceId and p.leftAt is null and p.userId <> :userId",
	)
	fun findActivePartnerId(@Param("userId") userId: UUID, @Param("type") type: SpaceType): UUID?

	/** [spaceId]의 활성 소속을 모두 떠난다 (`left_at` 기록, API.md 4.5 2단계). 영속성 컨텍스트를 비운다 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update SpaceMember m set m.leftAt = :now where m.spaceId = :spaceId and m.leftAt is null")
	fun closeAllActive(@Param("spaceId") spaceId: UUID, @Param("now") now: Instant): Int

	/**
	 * 사용자의 가져오기 대기 중인 [type] 개인 공간 소속(소속에 `left_at`이 있는 PERSONAL)을 다시 활성으로 돌린다 (API.md 4.5 4단계, D-78).
	 * 커플 공간 소속의 `left_at`을 먼저 기록한 뒤에 불러야 `uq_space_members_active_user`를 지킨다. 영속성 컨텍스트를 비운다.
	 * 되살린 소속 수를 돌려준다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(
		"update SpaceMember m set m.leftAt = null where m.userId = :userId and m.leftAt is not null " +
			"and m.spaceId in (select s.id from Space s where s.type = :type)",
	)
	fun reopenLeftSpacesOfType(@Param("userId") userId: UUID, @Param("type") type: SpaceType): Int
}
