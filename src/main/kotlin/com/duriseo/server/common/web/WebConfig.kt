package com.duriseo.server.common.web

import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.HandlerTypePredicate
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** 모든 API 컨트롤러 경로 앞에 /api/v1을 붙인다 (API.md 1장). 컨트롤러에는 /api/v1을 쓰지 않는다 */
@Configuration(proxyBeanMethods = false)
class WebConfig : WebMvcConfigurer {

	override fun configurePathMatch(configurer: PathMatchConfigurer) {
		configurer.addPathPrefix(API_PREFIX, HandlerTypePredicate.forAnnotation(RestController::class.java))
	}

	override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
		resolvers.add(CurrentUserArgumentResolver())
	}

	companion object {
		const val API_PREFIX = "/api/v1"

		// 로그인(access 토큰)과 강제 업데이트 차단(R-20)을 받지 않는 운영 경로: 공개 조회 3개와 관리자 API (API.md 1장, R-20).
		// 관리자 API는 AdminKeyFilter가 따로 막는다. 두 설정이 같은 목록 하나를 쓴다
		val APP_OPS_OPEN_PATHS: List<String> =
			listOf("/app-notices", "/app-update-rules", "/app-notice-images/*", "/admin/**").map { "$API_PREFIX$it" }
	}
}
