package com.duriseo.server.appops.api

import com.duriseo.server.auth.api.AuthWebConfig
import com.duriseo.server.common.web.WebConfig
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 인터셉터 순서 (API.md 1장 검사 순서): 강제 업데이트 차단(R-20) → 인증(`AuthWebConfig`).
 * 관리자 키 검사(R-19)는 인터셉터가 아니라 `AdminKeyFilter`(DispatcherServlet 앞)가 한다.
 */
@Configuration(proxyBeanMethods = false)
class AppOpsWebConfig(
	private val forceUpdateGuard: ForceUpdateGuardInterceptor,
) : WebMvcConfigurer {

	override fun addInterceptors(registry: InterceptorRegistry) {
		registry.addInterceptor(forceUpdateGuard)
			.addPathPatterns("${WebConfig.API_PREFIX}/**")
			.excludePathPatterns(WebConfig.APP_OPS_OPEN_PATHS)
			.order(AuthWebConfig.AUTH_ORDER - 20)
	}

}
