package com.duriseo.server.couple.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface InviteCodeRepository : JpaRepository<InviteCode, UUID> {

	/**
	 * 코드를 만든 사람의 ID. 행을 잠그지 않고 값만 읽는다. 연결이 만든 사람 A를 알아야 사용자 행을 UUID 순서로 잠글 수 있기 때문이다.
	 * 엔티티를 읽어 두면 잠금 뒤 다시 읽을 때 오래된 값을 볼 수 있어서 엔티티가 아니라 값만 읽는다.
	 */
	@Query("select c.creatorUserId from InviteCode c where c.code = :code")
	fun findCreatorIdByCode(@Param("code") code: String): UUID?

	/** 코드 행을 잠그고(SELECT … FOR UPDATE) 읽는다. 사용자 행을 잠근 뒤에 부른다 (잠금 순서 users → invite_codes → spaces) */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from InviteCode c where c.code = :code")
	fun findByCodeForUpdate(@Param("code") code: String): InviteCode?

	/** 그 사용자가 만든 코드 중 가장 최근 `created_at` (사용·만료·무효 포함). 재요청 60초 간격 계산에 쓴다 */
	@Query("select max(c.createdAt) from InviteCode c where c.creatorUserId = :userId")
	fun findLatestCreatedAt(@Param("userId") userId: UUID): Instant?

	/** 그 사용자의 유효한 코드. 부분 UNIQUE라 최대 하나 */
	@Query(
		"select c from InviteCode c where c.creatorUserId = :userId and c.usedAt is null and c.revokedAt is null " +
			"and c.expiresAt > :now",
	)
	fun findValidOf(@Param("userId") userId: UUID, @Param("now") now: Instant): InviteCode?

	/**
	 * 그 사용자의 사용되지 않은 이전 코드 모두에 `revoked_at`을 기록한다. 시간이 지나 만료됐어도 `revoked_at`이 null이면 포함한다
	 * (`uq_invite_codes_active_creator`를 지키려고, API.md 4.4 1단계). 바뀐 행 수를 돌려준다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update InviteCode c set c.revokedAt = :now where c.creatorUserId = :userId and c.usedAt is null and c.revokedAt is null")
	fun revokeUnusedOf(@Param("userId") userId: UUID, @Param("now") now: Instant): Int

	/** 입력한 코드를 사용 처리한다 (`used_at`, `used_by_user_id`) */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update InviteCode c set c.usedAt = :now, c.usedByUserId = :usedBy where c.id = :id")
	fun markUsed(@Param("id") id: UUID, @Param("usedBy") usedBy: UUID, @Param("now") now: Instant): Int

	/**
	 * 연결한 두 사람의 다른 유효한 코드(`used_at IS NULL AND revoked_at IS NULL`, 만료됐어도 포함)에 `revoked_at`을 기록한다 (DB-16).
	 * 입력한 코드는 이미 사용 처리돼 포함되지 않는다. 바뀐 행 수를 돌려준다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(
		"update InviteCode c set c.revokedAt = :now " +
			"where c.creatorUserId in :userIds and c.usedAt is null and c.revokedAt is null",
	)
	fun revokeValidOfUsers(@Param("userIds") userIds: Collection<UUID>, @Param("now") now: Instant): Int

	/** 그 사용자의 유효한 코드(사용되지 않았고 무효화되지 않은 코드, 만료돼도 포함)를 지운다 (탈퇴 익명화, ERD 4.3 4단계). 지운 행 수를 돌려준다 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from InviteCode c where c.creatorUserId = :userId and c.usedAt is null and c.revokedAt is null")
	fun deleteValidOf(@Param("userId") userId: UUID): Int
}
