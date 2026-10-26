package com.duriseo.server.auth.application

import com.duriseo.server.auth.domain.AuthSessionRepository
import com.duriseo.server.auth.domain.SessionRevokeReason
import com.duriseo.server.common.time.nowInSeconds
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** 다른 도메인이 세션을 다룰 때 쓰는 입구 (ARCHITECTURE 2장) */
@Service
class SessionService(
	private val sessions: AuthSessionRepository,
	private val clock: Clock,
) {

	/**
	 * 탈퇴 (ERD 4.3 4단계, 4.6): 그 사용자의 유효한 세션을 `WITHDRAWN`으로 무효화한 뒤 같은 트랜잭션에서 세션 행을 모두 지운다.
	 * 그래서 탈퇴 뒤에 남는 세션 행은 없고, 그 사용자의 access 토큰은 `sid` 행이 없어 401 SESSION_INVALID다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun revokeAndDeleteAllForWithdrawal(userId: UUID) {
		sessions.revokeValidSessions(userId, clock.nowInSeconds(), SessionRevokeReason.WITHDRAWN)
		sessions.deleteAllOfUser(userId)
	}
}
