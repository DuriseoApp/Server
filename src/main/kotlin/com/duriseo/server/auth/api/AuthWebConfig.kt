package com.duriseo.server.auth.api

import com.duriseo.server.common.web.WebConfig
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * `/auth/guest`, `/auth/social`, `/auth/refresh`를 뺀 모든 `/api/v1` 경로는 access 토큰이 필요하다 (API.md 1장).
 * `/auth/link`, `/auth/switch`는 게스트의 access 토큰이 필요하므로 공개 경로에 넣지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class AuthWebConfig(private val authInterceptor: AuthInterceptor) : WebMvcConfigurer {

	override fun addInterceptors(registry: InterceptorRegistry) {
		registry.addInterceptor(authInterceptor)
			.addPathPatterns("${WebConfig.API_PREFIX}/**")
			.excludePathPatterns(PUBLIC_PATHS)
	}

	companion object {
		val PUBLIC_PATHS: List<String> = listOf("/auth/guest", "/auth/social", "/auth/refresh").map { "${WebConfig.API_PREFIX}$it" }
	}
}
