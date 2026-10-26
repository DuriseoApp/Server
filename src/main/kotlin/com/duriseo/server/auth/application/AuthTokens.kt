package com.duriseo.server.auth.application

import java.time.Instant

/** 토큰을 주는 경로의 결과 (API.md 3.1 `AuthTokens`). 응답 DTO는 api 계층이 만든다 */
data class AuthTokens(
	val accessToken: String,
	val accessTokenExpiresAt: Instant,
	val refreshToken: String,
	val refreshTokenExpiresAt: Instant,
	val isNewUser: Boolean,
) {
	// 토큰 원문이 로그에 남지 않게 한다 (NFR-SEC-01)
	override fun toString(): String = "AuthTokens(isNewUser=$isNewUser)"
}
