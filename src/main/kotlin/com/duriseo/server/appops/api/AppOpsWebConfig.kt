package com.duriseo.server.appops.api

import com.duriseo.server.auth.api.AuthWebConfig
import com.duriseo.server.common.web.WebConfig
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 인터셉터 순서 (API.md 1장 검사 순서): 강제 업데이트 차단(R-20) → 인증(`AuthWebConfig`).
 * 관리자 키 검사(R-19)는 `/admin/**`만 맡고 R-20과 인증을 받지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class AppOpsWebConfig(
	private val forceUpdateGuard: ForceUpdateGuardInterceptor,
	private val adminKey: AdminKeyInterceptor,
) : WebMvcConfigurer {

	override fun addInterceptors(registry: InterceptorRegistry) {
		registry.addInterceptor(forceUpdateGuard)
			.addPathPatterns("${WebConfig.API_PREFIX}/**")
			.excludePathPatterns(R20_EXEMPT_PATHS)
			.order(AuthWebConfig.AUTH_ORDER - 20)
		registry.addInterceptor(adminKey)
			.addPathPatterns("${WebConfig.API_PREFIX}/admin/**")
			.order(AuthWebConfig.AUTH_ORDER - 10)
	}

	companion object {
		/** R-20의 예외 경로: 공개 조회 3개와 관리자 API */
		val R20_EXEMPT_PATHS: List<String> =
			listOf("/app-notices", "/app-update-rules", "/app-notice-images/**", "/admin/**").map { "${WebConfig.API_PREFIX}$it" }
	}
}
