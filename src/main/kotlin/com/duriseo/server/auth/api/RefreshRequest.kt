package com.duriseo.server.auth.api

import jakarta.validation.constraints.NotBlank

/** `POST /auth/refresh` 본문 (API.md 3.1). access 토큰은 필요 없다 */
data class RefreshRequest(
	@field:NotBlank val refreshToken: String?,
) {
	override fun toString(): String = "RefreshRequest()"
}
