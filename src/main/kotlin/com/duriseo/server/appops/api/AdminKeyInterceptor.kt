package com.duriseo.server.appops.api

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.config.DuriseoProperties
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 관리자 API(`/admin/**`) 키 검사 (R-19, NFR-SEC-04). 환경변수 `DURISEO_ADMIN_API_KEY`가 비어 있으면 모두 404 `NOT_FOUND`(키가 맞아도 같다),
 * 헤더 `X-Admin-Key`가 없거나 틀리면 401 `ADMIN_KEY_INVALID`. 비교는 상수 시간이다.
 * 요청 본문을 읽기 전에(인터셉터에서) 검사하므로 키가 틀린 요청에는 경로·필드 정보를 알려 주지 않는다. 키는 로그·응답에 쓰지 않는다.
 * 컨트롤러 메서드가 없는 경로도 같은 순서로 막는다(키가 틀리면 401).
 */
@Component
class AdminKeyInterceptor(properties: DuriseoProperties) : HandlerInterceptor {

	private val expected: ByteArray? = properties.admin.apiKey.takeIf { it.isNotEmpty() }?.let { sha256(it) }

	override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
		val expected = expected ?: throw ApiException(ErrorCode.NOT_FOUND)
		val given = request.getHeader(HEADER)
		// 해시한 뒤 비교해 길이가 달라도 시간이 같다
		if (given == null || !MessageDigest.isEqual(expected, sha256(given))) throw ApiException(ErrorCode.ADMIN_KEY_INVALID)
		return true
	}

	private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))

	companion object {
		const val HEADER = "X-Admin-Key"
	}
}
