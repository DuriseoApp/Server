package com.duriseo.server.auth.api

import com.duriseo.server.auth.application.AuthTokens
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/** API.md 3.1 `AuthTokens`. `/auth/guest`, `/auth/social`, `/auth/switch`, `/auth/refresh`의 응답 본문 */
data class AuthTokensResponse(
	val accessToken: String,
	val accessTokenExpiresAt: Instant,
	val refreshToken: String,
	val refreshTokenExpiresAt: Instant,
	// Kotlin의 `isXxx` Boolean이 JSON에서 `xxx`로 바뀌지 않게 이름을 고정한다
	@get:JsonProperty("isNewUser") val isNewUser: Boolean,
) {
	// 토큰 원문이 로그에 남지 않게 한다
	override fun toString(): String = "AuthTokensResponse(isNewUser=$isNewUser)"

	companion object {
		fun from(tokens: AuthTokens) = AuthTokensResponse(
			accessToken = tokens.accessToken,
			accessTokenExpiresAt = tokens.accessTokenExpiresAt,
			refreshToken = tokens.refreshToken,
			refreshTokenExpiresAt = tokens.refreshTokenExpiresAt,
			isNewUser = tokens.isNewUser,
		)
	}
}
