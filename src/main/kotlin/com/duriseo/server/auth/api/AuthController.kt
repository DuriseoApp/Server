package com.duriseo.server.auth.api

import com.duriseo.server.auth.application.AuthService
import com.duriseo.server.common.web.AuthenticatedUser
import com.duriseo.server.common.web.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** 인증 API (API.md 3.1). 소셜 로그인·연동·switch는 2부에서 더한다 */
@RestController
class AuthController(private val authService: AuthService) {

	/** 게스트 시작. 201과 `AuthTokens` (`isNewUser: true`). 인증 불필요 */
	@PostMapping("/auth/guest")
	fun startGuest(): ResponseEntity<AuthTokensResponse> =
		ResponseEntity.status(HttpStatus.CREATED).body(AuthTokensResponse.from(authService.startGuest()))

	/** refresh 토큰 교체 발급. 본문 `{refreshToken}`만 받는다. 인증 불필요 */
	@PostMapping("/auth/refresh")
	fun refresh(@Valid @RequestBody request: RefreshRequest): AuthTokensResponse =
		AuthTokensResponse.from(authService.refresh(request.refreshToken!!))

	/** 로그아웃 (R-17: 회원만). 204 */
	@PostMapping("/auth/logout")
	fun logout(@CurrentUser requester: AuthenticatedUser): ResponseEntity<Void> {
		authService.logout(requester)
		return ResponseEntity.noContent().build()
	}
}
