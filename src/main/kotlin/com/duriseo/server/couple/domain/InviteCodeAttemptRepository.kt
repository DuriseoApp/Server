package com.duriseo.server.couple.domain

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface InviteCodeAttemptRepository : JpaRepository<InviteCodeAttempt, UUID> {

	/**
	 * [since]보다 뒤(`attempted_at > since`)인 그 사용자의 실패 시각을 최근 순으로 읽는다. 오입력 제한은 `PageRequest.of(9, 1)`로
	 * 10번째로 최근 실패 하나를 읽는다 (있으면 최근 1시간 실패가 10번 이상, API.md 4.4 2단계). `ix_invite_code_attempts_user_attempted`를 쓴다.
	 */
	@Query(
		"select a.attemptedAt from InviteCodeAttempt a " +
			"where a.userId = :userId and a.succeeded = false and a.attemptedAt > :since order by a.attemptedAt desc",
	)
	fun findFailureTimesSince(@Param("userId") userId: UUID, @Param("since") since: Instant, pageable: Pageable): List<Instant>
}
