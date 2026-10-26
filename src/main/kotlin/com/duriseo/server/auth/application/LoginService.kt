package com.duriseo.server.auth.application

import com.duriseo.server.auth.domain.AuthSession
import com.duriseo.server.auth.domain.AuthSessionRepository
import com.duriseo.server.auth.domain.SessionRevokeReason
import com.duriseo.server.common.time.nowInSeconds
import com.duriseo.server.user.application.DeviceService
import com.duriseo.server.user.application.UserService
import com.duriseo.server.user.domain.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * 로그인 3종(게스트 시작, 소셜 로그인, 게스트 → 기존 계정 로그인)이 공통으로 쓰는 세션 처리 (API.md 4.2 4단계, ERD 4.6, DB-09, DB-12).
 * 게스트 연동(`/auth/link`)은 같은 기기라 이 코드를 쓰지 않는다.
 * 부르는 쪽의 트랜잭션 안에서만 쓴다 (사용자 생성·정리·새 세션이 한 트랜잭션이어야 한다).
 */
@Service
class LoginService(
	private val userService: UserService,
	private val sessions: AuthSessionRepository,
	private val deviceService: DeviceService,
	private val accessTokens: AccessTokenCodec,
	private val clock: Clock,
) {

	/**
	 * 로그인 3종의 끝 단계: 이전 세션·기기를 정리하고 새 세션과 토큰을 만든다.
	 * 사용자 행을 잠근 뒤 처리하므로 같은 계정으로 두 기기가 동시에 로그인해도 유효한 세션은 하나만 남는다 (D-51).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun login(user: User, isNewUser: Boolean): AuthTokens {
		cleanUpPreviousSessionsAndDevices(user.requireId())
		return startSession(user, isNewUser)
	}

	/**
	 * 이전 세션·기기 정리 (D-51): 그 사용자의 다른 유효한 세션을 모두 `REPLACED`로 바꾸고 `devices`를 모두 지운다.
	 * 새 세션을 만들기 전에 부른다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun cleanUpPreviousSessionsAndDevices(userId: UUID) {
		val now = clock.nowInSeconds()
		userService.lockForUpdate(userId)
		sessions.revokeValidSessions(userId, now, SessionRevokeReason.REPLACED)
		deviceService.deleteAllOf(userId)
	}

	/** 새 세션 한 행과 access·refresh 토큰을 만든다. refresh 만료는 계정 종류로 정한다 (회원 30일, 게스트 365일) */
	@Transactional(propagation = Propagation.MANDATORY)
	fun startSession(user: User, isNewUser: Boolean): AuthTokens {
		val now = clock.nowInSeconds()
		val refreshToken = RefreshTokens.generate()
		val refreshExpiresAt = now.plus(TokenLifetimes.refresh(user.accountType))
		val session = sessions.save(AuthSession(user.requireId(), RefreshTokens.hash(refreshToken), refreshExpiresAt, now))
		val access = accessTokens.issue(user.requireId(), session.requireId(), now)
		return AuthTokens(access.token, access.expiresAt, refreshToken, refreshExpiresAt, isNewUser)
	}
}
