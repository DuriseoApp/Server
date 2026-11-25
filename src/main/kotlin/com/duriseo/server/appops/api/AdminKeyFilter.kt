package com.duriseo.server.appops.api

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.web.WebConfig
import com.duriseo.server.config.DuriseoProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.util.UrlPathHelper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 관리자 API(`/api/v1/admin` 아래 모든 경로) 키 검사 (R-19, NFR-SEC-04). DispatcherServlet 앞의 필터라서
 * 경로가 없거나 메서드가 틀려도, multipart를 읽기 전에도 키 검사가 먼저다.
 * 환경변수 `DURISEO_ADMIN_API_KEY`가 비어(공백만 있어도) 있으면 모두 404 `NOT_FOUND`(키가 맞아도 같다),
 * 헤더 `X-Admin-Key`가 없거나 틀리면 401 `ADMIN_KEY_INVALID`. 비교는 상수 시간이다. 키는 로그·응답에 쓰지 않는다.
 * 경로는 원본 URI가 아니라 디코드·정규화한 경로로 판단한다(`%61dmin`, 끝 슬래시, 중복 슬래시, `.`·`..` 우회 방지).
 * 오류는 `HandlerExceptionResolver`에 넘겨 `GlobalExceptionHandler`가 1.1 형식으로 만든다(traceId는 `TraceIdFilter`가 먼저 정한다).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class AdminKeyFilter(
	properties: DuriseoProperties,
	@Qualifier("handlerExceptionResolver") private val resolver: HandlerExceptionResolver,
) : OncePerRequestFilter() {

	private val expected: ByteArray? = properties.admin.apiKey.trim().takeIf { it.isNotEmpty() }?.let { sha256(it) }

	override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
		if (!isAdminPath(UrlPathHelper.defaultInstance.getPathWithinApplication(request))) {
			filterChain.doFilter(request, response)
			return
		}
		val failure = check(request)
		if (failure != null) {
			resolver.resolveException(request, response, null, failure)
			return
		}
		filterChain.doFilter(request, response)
	}

	private fun check(request: HttpServletRequest): ApiException? {
		val expected = expected ?: return ApiException(ErrorCode.NOT_FOUND)
		val given = request.getHeader(HEADER)
		// 해시한 뒤 비교해 길이가 달라도 시간이 같다
		if (given == null || !MessageDigest.isEqual(expected, sha256(given))) return ApiException(ErrorCode.ADMIN_KEY_INVALID)
		return null
	}

	private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))

	companion object {
		const val HEADER = "X-Admin-Key"

		/** 디코드한 경로 [path]를 정규화(`//`, `.`, `..`)해서 `/api/v1/admin` 이거나 그 아래인지. 대소문자는 구분하지 않는다 */
		fun isAdminPath(path: String): Boolean {
			val segments = ArrayList<String>()
			for (segment in path.split('/')) {
				when (segment) {
					"", "." -> Unit
					".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
					else -> segments.add(segment.lowercase())
				}
			}
			val prefix = WebConfig.API_PREFIX.trim('/').split('/') + "admin"
			return segments.size >= prefix.size && segments.subList(0, prefix.size) == prefix
		}
	}
}
