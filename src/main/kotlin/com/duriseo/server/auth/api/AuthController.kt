package com.duriseo.server.auth.api

import com.duriseo.server.auth.application.AuthService
import com.duriseo.server.auth.application.SocialAuthService
import com.duriseo.server.common.web.AuthenticatedUser
import com.duriseo.server.common.web.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** 인증 API (API.md 3.1) */
@RestController
class AuthController(
	private val authService: AuthService,
	private val socialAuthService: SocialAuthService,
) {

	/** 게스트 시작. 201과 `AuthTokens` (`isNewUser: true`). 인증 불필요 */
	@PostMapping("/auth/guest")
	fun startGuest(): ResponseEntity<AuthTokensResponse> =
		ResponseEntity.status(HttpStatus.CREATED).body(AuthTokensResponse.from(authService.startGuest()))

	/** 소셜 로그인 (신규면 회원·개인 공간 생성). 200과 `AuthTokens`. 인증 불필요 */
	@PostMapping("/auth/social")
	fun social(@Valid @RequestBody request: SocialLoginRequest): AuthTokensResponse =
		AuthTokensResponse.from(socialAuthService.login(request.provider!!, request.token!!))

	/** 게스트 → 회원 연동 (R-14: 게스트만). 새 토큰 없이 204 */
	@PostMapping("/auth/link")
	fun link(
		@CurrentUser requester: AuthenticatedUser,
		@Valid @RequestBody request: SocialLoginRequest,
	): ResponseEntity<Void> {
		socialAuthService.link(requester, request.provider!!, request.token!!)
		return ResponseEntity.noContent().build()
	}

	/** 게스트 → 기존 계정 로그인 (R-14: 게스트만). 200과 `AuthTokens` (`isNewUser: false`) */
	@PostMapping("/auth/switch")
	fun switchAccount(
		@CurrentUser requester: AuthenticatedUser,
		@Valid @RequestBody request: SocialLoginRequest,
	): AuthTokensResponse =
		AuthTokensResponse.from(socialAuthService.switchToExistingAccount(requester, request.provider!!, request.token!!))

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
