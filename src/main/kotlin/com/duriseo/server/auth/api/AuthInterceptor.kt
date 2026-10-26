package com.duriseo.server.auth.api

import com.duriseo.server.auth.application.AccessTokenAuthenticator
import com.duriseo.server.common.web.CurrentUserArgumentResolver
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

/**
 * access 토큰 인증 (API.md 1장). Spring Security 대신 인터셉터를 쓰는 이유: 여기서 던진 `ApiException`이
 * `GlobalExceptionHandler`를 거쳐 1.1 오류 객체(`SESSION_REPLACED` 등)로 나가기 때문이다.
 * 컨트롤러 메서드가 있는 경로에만 적용한다 (없는 경로는 인증 없이 404로 답한다). 공개 경로는 `AuthWebConfig`가 뺀다.
 */
@Component
class AuthInterceptor(private val authenticator: AccessTokenAuthenticator) : HandlerInterceptor {

	override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
		if (handler !is HandlerMethod) return true
		val user = authenticator.authenticate(request.getHeader(HttpHeaders.AUTHORIZATION))
		CurrentUserArgumentResolver.store(request, user)
		return true
	}
}
