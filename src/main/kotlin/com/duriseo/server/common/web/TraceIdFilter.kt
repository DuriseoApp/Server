package com.duriseo.server.common.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * 요청마다 traceId를 만들어 로그(MDC)와 응답 헤더 X-Trace-Id에 넣는다 (API.md 1.1).
 * 오류 객체의 traceId도 이 값을 쓴다. 클라이언트가 보낸 값은 믿지 않고 항상 새로 만든다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceIdFilter : OncePerRequestFilter() {

	override fun doFilterInternal(
		request: HttpServletRequest,
		response: HttpServletResponse,
		filterChain: FilterChain,
	) {
		val traceId = UUID.randomUUID().toString().replace("-", "")
		request.setAttribute(ATTRIBUTE, traceId)
		response.setHeader(HEADER, traceId)
		MDC.put(MDC_KEY, traceId)
		try {
			filterChain.doFilter(request, response)
		} finally {
			MDC.remove(MDC_KEY)
		}
	}

	companion object {
		const val HEADER = "X-Trace-Id"
		const val ATTRIBUTE = "duriseo.traceId"
		const val MDC_KEY = "traceId"

		fun of(request: HttpServletRequest): String =
			request.getAttribute(ATTRIBUTE) as? String ?: UUID.randomUUID().toString().replace("-", "")
	}
}
