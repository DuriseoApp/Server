package com.duriseo.server.common.web

import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.HandlerTypePredicate
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** 모든 API 컨트롤러 경로 앞에 /api/v1을 붙인다 (API.md 1장). 컨트롤러에는 /api/v1을 쓰지 않는다 */
@Configuration(proxyBeanMethods = false)
class WebConfig : WebMvcConfigurer {

	override fun configurePathMatch(configurer: PathMatchConfigurer) {
		configurer.addPathPrefix(API_PREFIX, HandlerTypePredicate.forAnnotation(RestController::class.java))
	}

	companion object {
		const val API_PREFIX = "/api/v1"
	}
}
