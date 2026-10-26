package com.duriseo.server.space.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface SpaceRepository : JpaRepository<Space, UUID> {

	/**
	 * 사용자가 속했던 UNLINKED 커플 공간의 `purge_at`을 [purgeAt]으로 늦춘다. 이미 그보다 늦으면 그대로 둔다 (ERD 4.3 2단계, D-36).
	 * 바뀐 행 수를 돌려준다.
	 */
	@Modifying(flushAutomatically = true)
	@Query(
		"update Space s set s.purgeAt = :purgeAt, s.updatedAt = :now " +
			"where s.type = :type and s.status = :status and (s.purgeAt is null or s.purgeAt < :purgeAt) " +
			"and s.id in (select m.spaceId from SpaceMember m where m.userId = :userId)",
	)
	fun extendPurgeAtOfSpacesOf(
		@Param("userId") userId: UUID,
		@Param("type") type: SpaceType,
		@Param("status") status: SpaceStatus,
		@Param("purgeAt") purgeAt: Instant,
		@Param("now") now: Instant,
	): Int

	/**
	 * 사용자가 속했던(떠난 소속 포함) [type] 공간을 지운다. 소속·콘텐츠·공간 알림은 DB가 CASCADE로 지운다 (ERD 4.3 3단계, 4.5).
	 * 바뀐 행 수를 돌려준다.
	 */
	@Modifying(flushAutomatically = true)
	@Query("delete from Space s where s.type = :type and s.id in (select m.spaceId from SpaceMember m where m.userId = :userId)")
	fun deleteAllOfTypeBelongingTo(@Param("userId") userId: UUID, @Param("type") type: SpaceType): Int
}
