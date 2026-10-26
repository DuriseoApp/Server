package com.duriseo.server.auth.application

import com.duriseo.server.auth.domain.AuthSessionRepository
import com.duriseo.server.auth.domain.SessionRevokeReason
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.web.AuthenticatedUser
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * access 토큰이 필요한 경로의 인증 (API.md 1장 "인증 실패 규칙과 검사 순서"). 처음 걸린 것으로 응답한다.
 *  1. 형식·서명: 헤더 없음, `Bearer <토큰>` 아님, JWT 아님, 서명 틀림, 클레임 없음·틀림 → 401 SESSION_INVALID
 *  2. 만료: 서명은 맞고 `exp`가 지남 → 401 TOKEN_EXPIRED
 *  3. 세션: `sid`의 세션이 없거나 무효이면 REPLACED는 401 SESSION_REPLACED, 그 밖(로그아웃·탈퇴·만료)은 401 SESSION_INVALID
 * 계정 종류는 보지 않는다. 필요한 경로는 `SpaceAccessPolicy`가 DB에서 읽는다.
 */
@Service
class AccessTokenAuthenticator(
	private val accessTokens: AccessTokenCodec,
	private val sessions: AuthSessionRepository,
	private val clock: Clock,
) {

	@Transactional(readOnly = true)
	fun authenticate(authorizationHeader: String?): AuthenticatedUser {
		val token = bearerToken(authorizationHeader)
		val now = clock.instant()
		val claims = accessTokens.parse(token, now)

		val session = sessions.findByIdOrNull(claims.sessionId)
			?: throw ApiException(ErrorCode.SESSION_INVALID, "session not found")
		if (session.userId != claims.userId) throw ApiException(ErrorCode.SESSION_INVALID, "session belongs to another user")
		if (session.revokedAt != null) {
			throw if (session.revokedReason == SessionRevokeReason.REPLACED) {
				ApiException(ErrorCode.SESSION_REPLACED, "session replaced by another login")
			} else {
				ApiException(ErrorCode.SESSION_INVALID, "session revoked")
			}
		}
		if (!session.expiresAt.isAfter(now)) throw ApiException(ErrorCode.SESSION_INVALID, "session expired")
		return AuthenticatedUser(claims.userId, claims.sessionId)
	}

	private fun bearerToken(header: String?): String {
		if (header == null || !header.regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true)) {
			throw ApiException(ErrorCode.SESSION_INVALID, "missing or malformed Authorization header")
		}
		val token = header.substring(BEARER_PREFIX.length).trim()
		if (token.isEmpty()) throw ApiException(ErrorCode.SESSION_INVALID, "empty bearer token")
		return token
	}

	private companion object {
		const val BEARER_PREFIX = "Bearer "
	}
}
