package com.duriseo.server.auth

import com.duriseo.server.auth.application.social.KakaoWebhookAuthenticator
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.config.DuriseoProperties
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Kakao 웹훅 인증 (API.md 3.6, NFR-SEC-01)의 비교 규칙: `KakaoAK <어드민 키>` 헤더와 `app_id`가 모두 맞아야 통과한다.
 * 설정값(앱 ID, 어드민 키)은 가짜 값이다.
 */
class KakaoWebhookAuthenticatorTest {

	private val authenticator = KakaoWebhookAuthenticator(
		DuriseoProperties(kakao = DuriseoProperties.Kakao(appId = "123456", adminKey = "fake-kakao-admin-key")),
	)

	private fun assertUnauthorized(block: () -> Unit) {
		val error = assertFailsWith<ApiException> { block() }
		assertEquals(ErrorCode.WEBHOOK_UNAUTHORIZED, error.code)
		assertEquals(401, error.code.status.value())
	}

	@Test
	fun `FR-AUTH-06 AC4 어드민 키 헤더와 app_id가 모두 맞으면 통과한다`() {
		authenticator.authenticate("KakaoAK fake-kakao-admin-key", "123456")
	}

	@Test
	fun `FR-AUTH-06 AC4 헤더가 정확히 KakaoAK와 어드민 키가 아니면 거부한다`() {
		assertUnauthorized { authenticator.authenticate(null, "123456") }
		assertUnauthorized { authenticator.authenticate("", "123456") }
		assertUnauthorized { authenticator.authenticate("fake-kakao-admin-key", "123456") }
		assertUnauthorized { authenticator.authenticate("KakaoAK fake-kakao-admin-key ", "123456") }
		assertUnauthorized { authenticator.authenticate("KakaoAK fake-kakao-admin-ke", "123456") }
		assertUnauthorized { authenticator.authenticate("KakaoAK  fake-kakao-admin-key", "123456") }
		assertUnauthorized { authenticator.authenticate("kakaoak fake-kakao-admin-key", "123456") }
	}

	@Test
	fun `FR-AUTH-06 AC4 app_id가 다르거나 없으면 거부한다`() {
		assertUnauthorized { authenticator.authenticate("KakaoAK fake-kakao-admin-key", "654321") }
		assertUnauthorized { authenticator.authenticate("KakaoAK fake-kakao-admin-key", "123456 ") }
		assertUnauthorized { authenticator.authenticate("KakaoAK fake-kakao-admin-key", "") }
		assertUnauthorized { authenticator.authenticate("KakaoAK fake-kakao-admin-key", null) }
	}

	@Test
	fun `FR-AUTH-06 AC4 서버 설정이 비어 있으면 어떤 요청도 거부한다`() {
		val noKey = KakaoWebhookAuthenticator(DuriseoProperties(kakao = DuriseoProperties.Kakao(appId = "123456", adminKey = "")))
		val noAppId = KakaoWebhookAuthenticator(DuriseoProperties(kakao = DuriseoProperties.Kakao(appId = "", adminKey = "fake-kakao-admin-key")))

		// 비어 있는 설정과 비어 있는 요청 값이 "같다"고 통과하면 안 된다
		assertUnauthorized { noKey.authenticate("KakaoAK ", "123456") }
		assertUnauthorized { noKey.authenticate("", "123456") }
		assertUnauthorized { noAppId.authenticate("KakaoAK fake-kakao-admin-key", "") }
		assertUnauthorized { noAppId.authenticate("KakaoAK fake-kakao-admin-key", null) }
	}
}
