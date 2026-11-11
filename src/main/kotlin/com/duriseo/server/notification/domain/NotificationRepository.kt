package com.duriseo.server.notification.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface NotificationRepository : JpaRepository<Notification, UUID> {

	/** `space_id`가 [spaceId]인 알림을 모두 지운다 (두 사람 모두, API.md 4.5 3단계, D-45). 지운 행 수를 돌려준다 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Notification n where n.spaceId = :spaceId")
	fun deleteAllBySpaceId(@Param("spaceId") spaceId: UUID): Int

	/** 사용자가 받은 알림을 모두 지운다 (탈퇴 익명화, ERD 4.3 4단계). 지운 행 수를 돌려준다 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Notification n where n.userId = :userId")
	fun deleteAllByUserId(@Param("userId") userId: UUID): Int

	// ── 목록·읽음 (API.md 3.5). 보이는 알림은 `created_at > :cutoff`(= now − 30일)인 것뿐이다 (D-141, DB-19) ──

	/** 첫 페이지: (`created_at`, `id`) 내림차순. id 비교와 정렬은 PostgreSQL uuid 비교다 */
	@Query(
		"select n.* from notifications n where n.user_id = :userId and n.created_at > :cutoff " +
			"order by n.created_at desc, n.id desc limit :limit",
		nativeQuery = true,
	)
	fun findVisibleFirstPage(
		@Param("userId") userId: UUID,
		@Param("cutoff") cutoff: Instant,
		@Param("limit") limit: Int,
	): List<Notification>

	/** 커서 다음: (`created_at`, `id`)가 커서보다 **작은** 것 */
	@Query(
		"select n.* from notifications n where n.user_id = :userId and n.created_at > :cutoff " +
			"and (n.created_at, n.id) < (:cursorAt, :cursorId) " +
			"order by n.created_at desc, n.id desc limit :limit",
		nativeQuery = true,
	)
	fun findVisibleAfter(
		@Param("userId") userId: UUID,
		@Param("cutoff") cutoff: Instant,
		@Param("cursorAt") cursorAt: Instant,
		@Param("cursorId") cursorId: UUID,
		@Param("limit") limit: Int,
	): List<Notification>

	@Query(
		"select count(*) from notifications n where n.user_id = :userId and n.created_at > :cutoff and n.read_at is null",
		nativeQuery = true,
	)
	fun countVisibleUnread(@Param("userId") userId: UUID, @Param("cutoff") cutoff: Instant): Long

	/** 보이는 알림의 받는 사람. 없거나 30일이 지났으면 null (R-12 검사용) */
	@Query("select n.userId from Notification n where n.id = :id and n.createdAt > :cutoff")
	fun findVisibleOwnerId(@Param("id") id: UUID, @Param("cutoff") cutoff: Instant): UUID?

	/** 안 읽은 알림만 읽음으로 바꾼다. 이미 읽었으면 0행이고 `read_at`은 그대로다 (멱등) */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Notification n set n.readAt = :now where n.id = :id and n.readAt is null")
	fun markRead(@Param("id") id: UUID, @Param("now") now: Instant): Int
}
