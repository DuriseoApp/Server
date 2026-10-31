package com.duriseo.server.auth.application.social

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.config.DuriseoProperties
import org.springframework.stereotype.Component
import java.security.MessageDigest

/**
 * Kakao 연결 해제 웹훅의 인증 (API.md 3.6, NFR-SEC-01). 둘 다 맞아야 한다.
 * ① `Authorization` 헤더가 정확히 `KakaoAK ` + 서버 설정의 Kakao 어드민 키 ② `app_id`가 서버 설정의 Kakao 앱 ID.
 * 비교는 상수 시간으로 하고, 하나가 틀려도 나머지 비교를 건너뛰지 않는다. 설정값이 비어 있으면 아무것도 통과시키지 않는다.
 * 헤더 값과 키는 로그에 쓰지 않는다. 틀리면 401 `WEBHOOK_UNAUTHORIZED`다.
 */
@Component
class KakaoWebhookAuthenticator(private val properties: DuriseoProperties) {

	fun authenticate(authorizationHeader: String?, appId: String?) {
		val adminKey = properties.kakao.adminKey
		val expectedAppId = properties.kakao.appId
		if (adminKey.isBlank() || expectedAppId.isBlank()) throw unauthorized()
		val keyMatches = constantTimeEquals(authorizationHeader.orEmpty(), "$HEADER_PREFIX$adminKey")
		val appIdMatches = constantTimeEquals(appId.orEmpty(), expectedAppId)
		if (!(keyMatches and appIdMatches)) throw unauthorized()
	}

	private fun constantTimeEquals(actual: String, expected: String): Boolean =
		MessageDigest.isEqual(actual.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

	private fun unauthorized() = ApiException(ErrorCode.WEBHOOK_UNAUTHORIZED, "kakao webhook request is not authentic")

	private companion object {
		const val HEADER_PREFIX = "KakaoAK "
	}
}
