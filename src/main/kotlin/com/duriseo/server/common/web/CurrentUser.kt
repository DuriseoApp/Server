package com.duriseo.server.common.web

import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.util.UUID

/**
 * 인증을 통과한 요청자. access 토큰의 `sub`와 `sid`다.
 * 계정 종류(GUEST·MEMBER)는 토큰에 없으므로 여기에도 없다. 필요하면 `SpaceAccessPolicy`가 DB에서 읽는다 (API.md 1장).
 */
data class AuthenticatedUser(val userId: UUID, val sessionId: UUID)

/** 컨트롤러 인자에 붙이면 인증된 요청자(`AuthenticatedUser`)를 받는다. access 토큰이 필요한 경로에서만 쓴다 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentUser

class CurrentUserArgumentResolver : HandlerMethodArgumentResolver {

	override fun supportsParameter(parameter: MethodParameter): Boolean =
		parameter.hasParameterAnnotation(CurrentUser::class.java) &&
			AuthenticatedUser::class.java.isAssignableFrom(parameter.parameterType)

	override fun resolveArgument(
		parameter: MethodParameter,
		mavContainer: ModelAndViewContainer?,
		webRequest: NativeWebRequest,
		binderFactory: WebDataBinderFactory?,
	): Any? = webRequest.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST)
		?: error("@CurrentUser is used on a route that does not require authentication")

	companion object {
		/** 인증 인터셉터가 요청에 넣는 속성 이름 */
		const val ATTRIBUTE = "duriseo.authenticatedUser"

		fun store(request: HttpServletRequest, user: AuthenticatedUser) {
			request.setAttribute(ATTRIBUTE, user)
		}
	}
}
