package com.duriseo.server.auth.api

import com.duriseo.server.user.domain.SocialProvider
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

/**
 * `POST /auth/social`, `/auth/link`, `/auth/switch` 본문 (API.md 3.1).
 * `token`은 Google이면 ID 토큰, Kakao면 액세스 토큰이다. 소셜 토큰이 로그에 남지 않게 `toString`을 가린다 (NFR-SEC-01).
 */
data class SocialLoginRequest(
	@field:NotNull val provider: SocialProvider?,
	@field:NotBlank val token: String?,
) {
	override fun toString(): String = "SocialLoginRequest(provider=$provider)"
}
