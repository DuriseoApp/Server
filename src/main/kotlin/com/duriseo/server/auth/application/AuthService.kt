package com.duriseo.server.auth.application

import com.duriseo.server.auth.domain.AuthSessionRepository
import com.duriseo.server.auth.domain.SessionRevokeReason
import com.duriseo.server.common.access.SpaceAccessPolicy
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.time.nowInSeconds
import com.duriseo.server.common.web.AuthenticatedUser
import com.duriseo.server.user.application.DeviceService
import com.duriseo.server.user.application.UserService
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class AuthService(
	private val userService: UserService,
	private val loginService: LoginService,
	private val sessions: AuthSessionRepository,
	private val deviceService: DeviceService,
	private val accessTokens: AccessTokenCodec,
	private val policy: SpaceAccessPolicy,
	private val clock: Clock,
) {

	/** `POST /auth/guest` (API.md 4.1): 새 게스트 사용자와 개인 공간을 만들고 로그인한다. 부를 때마다 새 계정이다 */
	@Transactional
	fun startGuest(): AuthTokens {
		val guest = userService.createGuest()
		return loginService.login(guest, isNewUser = true)
	}

	/**
	 * `POST /auth/refresh` (API.md 1장, DB-02): refresh 토큰으로 새 access·refresh 토큰을 받는다.
	 * 새 행을 만들지 않고 같은 세션 행의 해시·만료·last_used_at만 바꾸므로 `sid`는 그대로다.
	 * 이전 토큰은 해시가 없어 401 SESSION_INVALID다. 만료는 계정 종류로 다시 정한다 (회원 30일, 게스트 365일).
	 */
	@Transactional
	fun refresh(refreshToken: String): AuthTokens {
		val now = clock.nowInSeconds()
		val session = sessions.findByRefreshTokenHashForUpdate(RefreshTokens.hash(refreshToken))
			?: throw ApiException(ErrorCode.SESSION_INVALID, "unknown refresh token")
		if (session.revokedAt != null) {
			throw if (session.revokedReason == SessionRevokeReason.REPLACED) {
				ApiException(ErrorCode.SESSION_REPLACED, "session replaced by another login")
			} else {
				ApiException(ErrorCode.SESSION_INVALID, "session revoked")
			}
		}
		if (!session.expiresAt.isAfter(now)) throw ApiException(ErrorCode.SESSION_INVALID, "refresh token expired")
		val user = userService.findActive(session.userId)
			?: throw ApiException(ErrorCode.SESSION_INVALID, "user not found")

		val newRefreshToken = RefreshTokens.generate()
		val newExpiresAt = now.plus(TokenLifetimes.refresh(user.accountType))
		session.rotate(RefreshTokens.hash(newRefreshToken), newExpiresAt, now)
		val access = accessTokens.issue(user.requireId(), session.requireId(), now)
		return AuthTokens(access.token, access.expiresAt, newRefreshToken, newExpiresAt, isNewUser = false)
	}

	/**
	 * `POST /auth/logout` (FR-AUTH-05, R-17): 회원만. 게스트면 아무것도 바꾸지 않고 403 GUEST_NOT_ALLOWED.
	 * 현재 세션을 `LOGOUT`으로 무효화하고 그 사용자의 `devices`를 모두 지운다 (DB-09). 사용자·공간 데이터는 남는다.
	 */
	@Transactional
	fun logout(requester: AuthenticatedUser) {
		policy.requireMember(requester.userId)
		val now = clock.nowInSeconds()
		val session = sessions.findByIdOrNull(requester.sessionId)
			?: throw ApiException(ErrorCode.SESSION_INVALID, "session not found")
		session.revoke(SessionRevokeReason.LOGOUT, now)
		deviceService.deleteAllOf(requester.userId)
	}
}
