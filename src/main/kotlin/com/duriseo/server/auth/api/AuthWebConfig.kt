package com.duriseo.server.auth.api

import com.duriseo.server.common.web.WebConfig
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * `/auth/guest`, `/auth/social`, `/auth/refresh`, `/webhooks/kakao/unlink`, 공개 조회 3개, `/admin/**`를 뺀 모든 `/api/v1` 경로는 access 토큰이 필요하다 (API.md 1장).
 * 웹훅은 Kakao 서버가 부르는 경로라 access 토큰 대신 어드민 키로 인증한다 (API.md 3.6).
 * `/auth/link`, `/auth/switch`는 게스트의 access 토큰이 필요하므로 공개 경로에 넣지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class AuthWebConfig(private val authInterceptor: AuthInterceptor) : WebMvcConfigurer {

	override fun addInterceptors(registry: InterceptorRegistry) {
		registry.addInterceptor(authInterceptor)
			.addPathPatterns("${WebConfig.API_PREFIX}/**")
			.excludePathPatterns(PUBLIC_PATHS)
			// 강제 업데이트 차단(R-20)은 인증보다 먼저다 (AppOpsWebConfig가 더 앞선 order로 등록한다)
			.order(AUTH_ORDER)
	}

	companion object {
		val PUBLIC_PATHS: List<String> = listOf("/auth/guest", "/auth/social", "/auth/refresh", "/webhooks/kakao/unlink").map { "${WebConfig.API_PREFIX}$it" } +
			// 공개 조회 3개와 관리자 API(관리자 키로 막는다, R-19). API.md 1장
			listOf("/app-notices", "/app-update-rules", "/app-notice-images/**", "/admin/**").map { "${WebConfig.API_PREFIX}$it" }

		/** 인터셉터 순서: 값이 작을수록 먼저. 강제 업데이트 차단·관리자 키 검사(`AppOpsWebConfig`)가 인증보다 앞선다 */
		const val AUTH_ORDER = 0
	}
}
