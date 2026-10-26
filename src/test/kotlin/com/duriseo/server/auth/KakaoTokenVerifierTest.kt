package com.duriseo.server.auth

import com.duriseo.server.auth.application.social.KakaoApiClient
import com.duriseo.server.auth.application.social.KakaoRejectedException
import com.duriseo.server.auth.application.social.KakaoTokenVerifier
import com.duriseo.server.auth.application.social.KakaoUnavailableException
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.config.DuriseoProperties
import com.duriseo.server.support.LogCapture
import com.duriseo.server.user.domain.SocialProvider
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.ResponseCreator
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Kakao 토큰 검증과 호출 (API.md 4.2 2단계, 4.6, NFR-SEC-01). `MockRestServiceServer`로 Kakao 응답을 흉내 낸다 (네트워크 없음).
 * 주소·헤더·본문, 4xx·5xx·시간 초과 처리, 닉네임 조회 시점을 확인한다.
 */
class KakaoTokenVerifierTest {

	private val properties = DuriseoProperties(kakao = DuriseoProperties.Kakao(appId = "123456", adminKey = "fake-kakao-admin-key"))
	private val builder = RestClient.builder()
	private val server: MockRestServiceServer = MockRestServiceServer.bindTo(builder).build()
	private val client = KakaoApiClient(builder.build(), properties)
	private val verifier = KakaoTokenVerifier(client, properties)

	private val timeout = ResponseCreator { throw SocketTimeoutException("Read timed out") }

	private fun expectTokenInfo(respond: ResponseCreator) {
		server.expect(requestTo("https://kapi.kakao.com/v1/user/access_token_info"))
			.andExpect(method(org.springframework.http.HttpMethod.GET))
			.andExpect(header("Authorization", "Bearer fake-kakao-access-token"))
			.andRespond(respond)
	}

	private fun expectUserMe(respond: ResponseCreator) {
		server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
			.andExpect(method(org.springframework.http.HttpMethod.GET))
			.andExpect(header("Authorization", "Bearer fake-kakao-access-token"))
			.andRespond(respond)
	}

	private fun validTokenInfo() = withSuccess("""{"id": 777, "expires_in": 3000, "app_id": 123456}""", MediaType.APPLICATION_JSON)

	private fun assertApiError(code: ErrorCode, block: () -> Unit) {
		val error = assertFailsWith<ApiException> { block() }
		assertEquals(code, error.code)
	}

	@Test
	fun `NFR-SEC-01 app_id가 둘이서 앱 ID와 같은 Kakao 토큰은 id를 사용자 ID로 쓴다`() {
		expectTokenInfo(validTokenInfo())

		val identity = verifier.verify("fake-kakao-access-token")

		assertEquals(SocialProvider.KAKAO, identity.provider)
		assertEquals("777", identity.providerUserId)
		// 이름을 묻지 않았으면 v2/user/me는 부르지 않는다 (기존 회원 로그인, switch)
		server.verify()
	}

	@Test
	fun `NFR-SEC-01 Kakao 토큰의 app_id가 둘이서 앱 ID가 아니면 401 SOCIAL_TOKEN_INVALID`() {
		expectTokenInfo(withSuccess("""{"id": 777, "expires_in": 3000, "app_id": 999999}""", MediaType.APPLICATION_JSON))

		assertApiError(ErrorCode.SOCIAL_TOKEN_INVALID) { verifier.verify("fake-kakao-access-token") }
		server.verify()
	}

	@Test
	fun `NFR-SEC-01 Kakao가 토큰을 거부하면 401 SOCIAL_TOKEN_INVALID`() {
		expectTokenInfo(
			withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
				.body("""{"msg": "this access token does not exist", "code": -401}"""),
		)

		assertApiError(ErrorCode.SOCIAL_TOKEN_INVALID) { verifier.verify("fake-kakao-access-token") }
	}

	@Test
	fun `NFR-SEC-01 Kakao access_token_info가 5xx를 주면 500 INTERNAL_ERROR다`() {
		expectTokenInfo(withServerError())

		assertApiError(ErrorCode.INTERNAL_ERROR) { verifier.verify("fake-kakao-access-token") }
	}

	@Test
	fun `NFR-SEC-01 Kakao access_token_info가 시간 초과되면 500 INTERNAL_ERROR다`() {
		expectTokenInfo(timeout)

		assertApiError(ErrorCode.INTERNAL_ERROR) { verifier.verify("fake-kakao-access-token") }
	}

	@Test
	fun `NFR-SEC-01 앱 ID 설정이 비어 있으면 어떤 Kakao 토큰도 401 SOCIAL_TOKEN_INVALID다`() {
		val unconfigured = DuriseoProperties()
		expectTokenInfo(validTokenInfo())

		assertApiError(ErrorCode.SOCIAL_TOKEN_INVALID) { KakaoTokenVerifier(client, unconfigured).verify("fake-kakao-access-token") }
	}

	@Test
	fun `FR-AUTH-07 AC1 이름이 필요하면 Kakao v2 user me에서 닉네임만 읽는다`() {
		expectTokenInfo(validTokenInfo())
		expectUserMe(
			withSuccess(
				"""{"id": 777, "kakao_account": {"profile": {"nickname": "카카오닉", "is_default_nickname": false}, "email": "private-person@example.invalid"}}""",
				MediaType.APPLICATION_JSON,
			),
		)

		val identity = verifier.verify("fake-kakao-access-token")

		assertEquals("카카오닉", identity.profileName())
		server.verify()
	}

	@Test
	fun `FR-AUTH-07 AC1 Kakao v2 user me가 4xx를 주면 로그인을 거부하지 않고 이름 없음이다`() {
		expectTokenInfo(validTokenInfo())
		expectUserMe(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body("""{"msg": "insufficient scopes", "code": -402}"""))

		val identity = verifier.verify("fake-kakao-access-token")

		assertNull(identity.profileName())
	}

	@Test
	fun `FR-AUTH-07 AC1 Kakao 프로필에 닉네임이 없으면 이름 없음이다`() {
		expectTokenInfo(validTokenInfo())
		expectUserMe(withSuccess("""{"id": 777, "kakao_account": {"profile_nickname_needs_agreement": true}}""", MediaType.APPLICATION_JSON))

		assertNull(verifier.verify("fake-kakao-access-token").profileName())
	}

	@Test
	fun `NFR-SEC-01 Kakao v2 user me가 5xx를 주면 500 INTERNAL_ERROR다`() {
		expectTokenInfo(validTokenInfo())
		expectUserMe(withServerError())

		val identity = verifier.verify("fake-kakao-access-token")

		assertApiError(ErrorCode.INTERNAL_ERROR) { identity.profileName() }
	}

	@Test
	fun `NFR-SEC-01 Kakao v2 user me가 시간 초과되면 500 INTERNAL_ERROR다`() {
		expectTokenInfo(validTokenInfo())
		expectUserMe(timeout)

		val identity = verifier.verify("fake-kakao-access-token")

		assertApiError(ErrorCode.INTERNAL_ERROR) { identity.profileName() }
	}

	@Test
	fun `NFR-PRIV-01 Kakao 검증은 이메일을 받거나 로그와 문자열에 남기지 않는다`() {
		val email = "private-person@example.invalid"
		expectTokenInfo(validTokenInfo())
		expectUserMe(
			withSuccess("""{"id": 777, "kakao_account": {"profile": {"nickname": "카카오닉"}, "email": "$email"}}""", MediaType.APPLICATION_JSON),
		)

		LogCapture().use { logs ->
			val identity = verifier.verify("fake-kakao-access-token")
			identity.profileName()

			assertFalse(logs.text().contains(email), "이메일이 로그에 남았어요")
			assertFalse(logs.text().contains("fake-kakao-access-token"), "토큰이 로그에 남았어요")
			assertFalse(identity.toString().contains("fake-kakao-access-token"))
		}
	}

	// ── 연결 끊기 (API.md 4.6) ──────────────────────────

	@Test
	fun `FR-AUTH-06 AC3 Kakao 연결 끊기는 어드민 키로 target_id_type=user_id와 provider_user_id를 보낸다`() {
		server.expect(requestTo("https://kapi.kakao.com/v1/user/unlink"))
			.andExpect(method(org.springframework.http.HttpMethod.POST))
			.andExpect(header("Authorization", "KakaoAK fake-kakao-admin-key"))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
			.andExpect(content().string("target_id_type=user_id&target_id=777"))
			.andRespond(withSuccess("""{"id": 777}""", MediaType.APPLICATION_JSON))

		client.unlink("777")

		server.verify()
	}

	@Test
	fun `FR-AUTH-06 AC3 Kakao 연결 끊기가 4xx 5xx 시간 초과면 호출한 쪽이 알 수 있게 예외를 던진다`() {
		server.expect(requestTo("https://kapi.kakao.com/v1/user/unlink")).andRespond(withStatus(HttpStatus.BAD_REQUEST))
		assertFailsWith<KakaoRejectedException> { client.unlink("777") }

		server.expect(requestTo("https://kapi.kakao.com/v1/user/unlink")).andRespond(withServerError())
		assertFailsWith<KakaoUnavailableException> { client.unlink("777") }

		server.expect(requestTo("https://kapi.kakao.com/v1/user/unlink")).andRespond(timeout)
		assertFailsWith<KakaoUnavailableException> { client.unlink("777") }
	}

	@Test
	fun `FR-AUTH-06 AC3 Kakao 어드민 키가 비어 있으면 연결 끊기를 건너뛰고 호출하지 않는다`() {
		// 기대하는 요청이 하나도 없으므로 호출하면 MockRestServiceServer가 실패시킨다
		val withoutKey = KakaoApiClient(builder.build(), DuriseoProperties(kakao = DuriseoProperties.Kakao(appId = "123456", adminKey = "")))

		withoutKey.unlink("777")

		server.verify()
	}

	@Test
	fun `FR-AUTH-06 AC3 Kakao 연결 끊기 실패 로그에는 어드민 키가 없다`() {
		server.expect(requestTo("https://kapi.kakao.com/v1/user/unlink")).andRespond(withServerError())

		LogCapture().use { logs ->
			runCatching { client.unlink("777") }

			assertFalse(logs.text().contains("fake-kakao-admin-key"))
		}
	}
}
