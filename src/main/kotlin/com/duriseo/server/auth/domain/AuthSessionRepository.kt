package com.duriseo.server.auth.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface AuthSessionRepository : JpaRepository<AuthSession, UUID> {

	/** refresh 교체 때 같은 토큰으로 겹친 요청이 둘 다 성공하지 않도록 행을 잠그고 읽는다 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from AuthSession s where s.refreshTokenHash = :hash")
	fun findByRefreshTokenHashForUpdate(@Param("hash") hash: String): AuthSession?

	/** 그 사용자의 유효한 세션을 모두 [reason]으로 무효화한다. 바뀐 행 수를 돌려준다 */
	@Modifying(flushAutomatically = true)
	@Query(
		"update AuthSession s set s.revokedAt = :now, s.revokedReason = :reason " +
			"where s.userId = :userId and s.revokedAt is null and s.expiresAt > :now",
	)
	fun revokeValidSessions(
		@Param("userId") userId: UUID,
		@Param("now") now: Instant,
		@Param("reason") reason: SessionRevokeReason,
	): Int

	/** 그 사용자의 세션 행을 모두 지운다. 탈퇴가 `WITHDRAWN`으로 무효화한 뒤에 부른다 (ERD 4.3) */
	@Modifying(flushAutomatically = true)
	@Query("delete from AuthSession s where s.userId = :userId")
	fun deleteAllOfUser(@Param("userId") userId: UUID): Int

	/**
	 * 유효한 세션의 수 (`revoked_at IS NULL AND expires_at > now`, ERD DB-14).
	 * 단위 6의 푸시 판단(R-16)이 0보다 큰지 본다.
	 */
	@Query("select count(s) from AuthSession s where s.userId = :userId and s.revokedAt is null and s.expiresAt > :now")
	fun countValidSessions(@Param("userId") userId: UUID, @Param("now") now: Instant): Long
}
