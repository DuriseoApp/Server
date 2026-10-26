package com.duriseo.server.space.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
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
}
