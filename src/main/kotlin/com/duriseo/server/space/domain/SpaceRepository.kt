package com.duriseo.server.space.domain

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
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

	/** 공간 행을 잠그고(SELECT … FOR UPDATE) 읽는다. 처음 만난 날·색 변경이 해제와 겹치지 않게 한다 (ARCHITECTURE 4장) */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Space s where s.id = :id")
	fun findByIdForUpdate(@Param("id") id: UUID): Space?

	/**
	 * 복구할 수 있는 이전 커플 공간 (FR-COUPLE-06, API.md 4.4 4단계): UNLINKED, `purge_at`이 지나지 않았고,
	 * 소속이 정확히 [userA]와 [userB] 두 행인 공간. 가장 최근에 해제된 것부터. 찾은 행은 잠근다.
	 * 한 건만 필요하므로 `Pageable`로 개수를 줄여 부른다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(
		"select s from Space s where s.type = :type and s.status = :status and s.purgeAt > :now " +
			"and (select count(m1.id) from SpaceMember m1 where m1.spaceId = s.id) = 2 " +
			"and exists (select m2.id from SpaceMember m2 where m2.spaceId = s.id and m2.userId = :userA) " +
			"and exists (select m3.id from SpaceMember m3 where m3.spaceId = s.id and m3.userId = :userB) " +
			"order by s.unlinkedAt desc, s.id desc",
	)
	fun findRestorableForUpdate(
		@Param("userA") userA: UUID,
		@Param("userB") userB: UUID,
		@Param("type") type: SpaceType,
		@Param("status") status: SpaceStatus,
		@Param("now") now: Instant,
		pageable: Pageable,
	): List<Space>

	/** 공간 하나를 지운다. 소속·콘텐츠·공간 알림은 DB가 CASCADE로 지운다. 영속성 컨텍스트를 비운다 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Space s where s.id = :id")
	fun deleteSpaceById(@Param("id") id: UUID): Int

	/** UNLINKED 공간을 다시 [status]로 돌린다: `unlinked_at`, `purge_at`을 비운다 (FR-COUPLE-06). 영속성 컨텍스트를 비운다 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Space s set s.status = :status, s.unlinkedAt = null, s.purgeAt = null, s.updatedAt = :now where s.id = :id")
	fun reactivate(@Param("id") id: UUID, @Param("status") status: SpaceStatus, @Param("now") now: Instant): Int

	/**
	 * 활성 커플 공간을 UNLINKED로 바꾼다: `unlinked_at` = [now], `purge_at` = [purgeAt] (API.md 4.5 2단계, ERD 4.2).
	 * `ck_spaces_unlinked_columns` 때문에 세 값을 한 문장에서 같이 바꾼다. 영속성 컨텍스트를 비운다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(
		"update Space s set s.status = :status, s.unlinkedAt = :now, s.purgeAt = :purgeAt, s.updatedAt = :now " +
			"where s.id = :id",
	)
	fun markUnlinked(
		@Param("id") id: UUID,
		@Param("status") status: SpaceStatus,
		@Param("now") now: Instant,
		@Param("purgeAt") purgeAt: Instant,
	): Int
}
