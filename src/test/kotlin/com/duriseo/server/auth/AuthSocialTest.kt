package com.duriseo.server.auth

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.auth.application.social.GoogleIdTokenVerifier
import com.duriseo.server.auth.application.social.GoogleKeySource
import com.duriseo.server.auth.application.social.SocialIdentity
import com.duriseo.server.auth.application.social.SocialTokenVerifier
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.config.DuriseoProperties
import com.duriseo.server.support.IntegrationTestSupport
import com.duriseo.server.support.LogCapture
import com.duriseo.server.support.TestClockConfig
import com.duriseo.server.user.domain.SocialProvider
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.util.Date
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 소셜 로그인 `POST /auth/social` (API.md 4.2, FR-AUTH-02, FR-AUTH-07 AC1, NFR-SEC-01, NFR-PRIV-01).
 * 소셜 제공자는 가짜로 바꿨다(`FakeSocialTokenVerifier`). 실제 Google·Kakao 검증은 `GoogleIdTokenVerifierTest`, `KakaoTokenVerifierTest`가 한다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class AuthSocialTest : IntegrationTestSupport() {

	private fun registerGoogle(token: String = "fake-google-token", sub: String = "fake-google-sub-1", name: String? = "홍길동") =
		registerSocial(SocialProvider.GOOGLE, token, sub, name)

	private fun registerKakao(token: String = "fake-kakao-token", id: String = "9001", name: String? = "카카오닉") =
		registerSocial(SocialProvider.KAKAO, token, id, name)

	// ── FR-AUTH-02 AC1: 신규 가입 ───────────────────────

	@Test
	fun `FR-AUTH-02 AC1 처음 쓰는 Google 계정으로 로그인하면 회원과 개인 공간을 만들고 isNewUser는 true다`() {
		registerGoogle()

		social("GOOGLE", "fake-google-token")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.accessToken").isNotEmpty)
			.andExpect(jsonPath("$.refreshToken").isNotEmpty)
			.andExpect(jsonPath("$.accessTokenExpiresAt").value("2026-10-26T01:00:00Z"))
			.andExpect(jsonPath("$.refreshTokenExpiresAt").isNotEmpty)
			.andExpect(jsonPath("$.isNewUser").value(true))

		assertEquals(1, count("select count(*) from users"))
		assertEquals(1, count("select count(*) from users where account_type = 'MEMBER' and deleted_at is null and time_zone = 'Asia/Seoul'"))
		assertEquals(1, count("select count(*) from social_accounts where provider = 'GOOGLE' and provider_user_id = 'fake-google-sub-1'"))
		assertEquals(1, count("select count(*) from social_accounts sa join users u on u.id = sa.user_id"))
		assertEquals(
			1,
			count("select count(*) from notification_settings where event_reminder and partner_created and memo_pinned and anniversary"),
		)
		assertEquals(1, count("select count(*) from spaces where type = 'PERSONAL' and status = 'ACTIVE'"))
		assertEquals(1, count("select count(*) from space_members where color = 'pick-orange' and left_at is null"))
		assertEquals(1, count("select count(*) from auth_sessions where revoked_at is null and revoked_reason is null"))
	}

	@Test
	fun `FR-AUTH-02 AC1 처음 쓰는 Kakao 계정으로 로그인하면 회원과 개인 공간을 만들고 isNewUser는 true다`() {
		registerKakao()

		social("KAKAO", "fake-kakao-token")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.isNewUser").value(true))

		assertEquals(1, count("select count(*) from users where account_type = 'MEMBER'"))
		assertEquals(1, count("select count(*) from social_accounts where provider = 'KAKAO' and provider_user_id = '9001'"))
		assertEquals(1, count("select count(*) from spaces where type = 'PERSONAL'"))
		assertEquals(1, count("select count(*) from space_members where color = 'pick-orange'"))
		assertEquals(1, count("select count(*) from auth_sessions"))
		// 신규 가입이라 닉네임을 위해 프로필 이름을 한 번 받는다
		assertEquals(1, fakeSocial.nameLookups.get())
	}

	@Test
	fun `FR-AUTH-02 AC1 회원의 refresh 토큰은 30일 뒤 만료된다`() {
		registerGoogle()

		val tokens = socialTokens("GOOGLE", "fake-google-token")

		val expected = START.plus(Duration.ofDays(30))
		assertEquals(expected.toString(), tokens.refreshTokenExpiresAt)
		assertEquals(expected, instantOf("select expires_at from auth_sessions"))
		assertEquals(43, tokens.refreshToken.length)
		// 발급한 토큰으로 바로 쓸 수 있다
		getMe(tokens.accessToken).andExpect(status().isOk).andExpect(jsonPath("$.accountType").value("MEMBER"))
	}

	// ── FR-AUTH-02 AC2: 이미 가입한 계정 ─────────────────

	@Test
	fun `FR-AUTH-02 AC2 이미 가입한 소셜 계정으로 로그인하면 같은 사용자와 공간으로 들어가고 isNewUser는 false다`() {
		registerGoogle()
		val first = socialTokens("GOOGLE", "fake-google-token")
		val spaceBefore = jdbc.queryForObject("select id from spaces", UUID::class.java)
		clock.advance(Duration.ofDays(2))

		social("GOOGLE", "fake-google-token")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.isNewUser").value(false))

		assertEquals(1, count("select count(*) from users"))
		assertEquals(1, count("select count(*) from spaces"))
		assertEquals(1, count("select count(*) from social_accounts"))
		assertEquals(1, count("select count(*) from notification_settings"))
		// 다른 기기·재설치에서도 같은 계정, 같은 공간
		val second = tokensOf(social("GOOGLE", "fake-google-token").andReturn().response.getContentAsString(Charsets.UTF_8))
		assertEquals(first.userId, second.userId)
		getMe(second.accessToken).andExpect(jsonPath("$.space.id").value(spaceBefore.toString()))
	}

	@Test
	fun `FR-AUTH-02 AC2 이미 가입한 Kakao 계정의 로그인은 프로필 이름을 다시 받지 않는다`() {
		registerKakao()
		socialTokens("KAKAO", "fake-kakao-token")
		assertEquals(1, fakeSocial.nameLookups.get())

		social("KAKAO", "fake-kakao-token").andExpect(status().isOk).andExpect(jsonPath("$.isNewUser").value(false))

		// 기존 회원의 로그인은 access_token_info 확인만 한다 (API.md 4.2)
		assertEquals(1, fakeSocial.nameLookups.get())
	}

	@Test
	fun `FR-AUTH-02 AC2 소셜 이름이 바뀌어도 기존 회원의 닉네임은 그대로다`() {
		registerGoogle(name = "홍길동")
		socialTokens("GOOGLE", "fake-google-token")
		registerGoogle(name = "새이름")

		social("GOOGLE", "fake-google-token").andExpect(status().isOk)

		assertEquals("홍길동", jdbc.queryForObject("select nickname from users", String::class.java))
	}

	@Test
	fun `FR-AUTH-02 AC2 같은 소셜 계정의 첫 로그인이 동시에 둘 오면 하나의 회원으로 로그인한다`() {
		registerGoogle()
		// 두 요청이 모두 "아직 회원이 아님"을 확인한 뒤 가입을 시도하도록 이름을 받는 지점에서 서로 기다리게 한다
		fakeSocial.nameLookupBarrier = CyclicBarrier(2)
		val pool = Executors.newFixedThreadPool(2)
		try {
			val results = (1..2).map {
				pool.submit<String> { social("GOOGLE", "fake-google-token").andReturn().response.getContentAsString(Charsets.UTF_8) }
			}.map { it.get() }

			// 둘 다 성공하고(500 없음), 회원은 한 명이다
			results.forEach { assertTrue(it.contains("accessToken"), "로그인에 실패했어요: $it") }
			assertEquals(1, count("select count(*) from users"))
			assertEquals(1, count("select count(*) from social_accounts"))
			assertEquals(1, count("select count(*) from spaces"))
			assertEquals(1, results.count { it.contains("\"isNewUser\":true") })
			assertEquals(1, results.count { it.contains("\"isNewUser\":false") })
			// 한 기기만 로그인: 유효한 세션은 하나
			assertEquals(1, count("select count(*) from auth_sessions where revoked_at is null"))
		} finally {
			pool.shutdownNow()
		}
	}

	// ── FR-AUTH-07 AC1: 소셜 가입 닉네임 ─────────────────

	@Test
	fun `FR-AUTH-07 AC1 소셜 가입 닉네임은 소셜 프로필 이름이다`() {
		registerGoogle(name = "  홍길동  ")

		val tokens = socialTokens("GOOGLE", "fake-google-token")

		getMe(tokens.accessToken).andExpect(jsonPath("$.nickname").value("홍길동"))
	}

	@Test
	fun `FR-AUTH-07 AC1 소셜 이름이 10자를 넘으면 앞 10자만 쓴다`() {
		registerGoogle(name = "가나다라마바사아자차카타")
		registerKakao(name = "😀".repeat(12))

		val google = socialTokens("GOOGLE", "fake-google-token")
		val kakao = socialTokens("KAKAO", "fake-kakao-token")

		getMe(google.accessToken).andExpect(jsonPath("$.nickname").value("가나다라마바사아자차"))
		// 글자 수는 코드 포인트로 센다: 이모지를 반으로 자르지 않는다
		getMe(kakao.accessToken).andExpect(jsonPath("$.nickname").value("😀".repeat(10)))
	}

	@Test
	fun `FR-AUTH-07 AC1 소셜 이름이 없거나 공백뿐이면 닉네임은 사용자다`() {
		registerGoogle(name = null)
		registerKakao(name = "   ")

		val google = socialTokens("GOOGLE", "fake-google-token")
		val kakao = socialTokens("KAKAO", "fake-kakao-token")

		getMe(google.accessToken).andExpect(jsonPath("$.nickname").value("사용자"))
		getMe(kakao.accessToken).andExpect(jsonPath("$.nickname").value("사용자"))
	}

	// ── 요청 검사 ───────────────────────────────────────

	@Test
	fun `FR-AUTH-02 AC1 provider나 token이 없거나 알 수 없는 provider면 400 VALIDATION_FAILED`() {
		fun body(json: String) = mockMvc.perform(post("$API/auth/social").contentType(MediaType.APPLICATION_JSON).content(json))

		body("""{"token": "x"}""").expectError(400, "VALIDATION_FAILED")
		body("""{"provider": "GOOGLE"}""").expectError(400, "VALIDATION_FAILED")
		body("""{"provider": "GOOGLE", "token": "  "}""").expectError(400, "VALIDATION_FAILED")
		body("""{"provider": "FACEBOOK", "token": "x"}""").expectError(400, "VALIDATION_FAILED")
		assertEquals(0, count("select count(*) from users"))
	}

	// ── NFR-SEC-01: 검증 실패 ────────────────────────────

	@Test
	fun `NFR-SEC-01 등록되지 않은 소셜 토큰으로 로그인하면 401 SOCIAL_TOKEN_INVALID이고 아무 행도 만들지 않는다`() {
		social("GOOGLE", "unknown-token").expectError(401, "SOCIAL_TOKEN_INVALID")
		social("KAKAO", "unknown-token").expectError(401, "SOCIAL_TOKEN_INVALID")

		assertEquals(0, count("select count(*) from users"))
		assertEquals(0, count("select count(*) from spaces"))
		assertEquals(0, count("select count(*) from auth_sessions"))
	}

	@Test
	fun `NFR-SEC-01 소셜 토큰 검증이 실패하면 social·link·switch 모두 아무 행도 바꾸지 않는다`() {
		registerGoogle()
		val guest = startGuest()
		putDevice(guest.accessToken, """{"fcmToken": "fake-fcm-token-1"}""").andExpect(status().isNoContent)
		val before = accountSnapshot()

		social("GOOGLE", "unknown-token").expectError(401, "SOCIAL_TOKEN_INVALID")
		link(guest.accessToken, "GOOGLE", "unknown-token").expectError(401, "SOCIAL_TOKEN_INVALID")
		switchAccount(guest.accessToken, "GOOGLE", "unknown-token").expectError(401, "SOCIAL_TOKEN_INVALID")

		assertEquals(before, accountSnapshot())
		// 게스트는 계속 쓸 수 있다
		getMe(guest.accessToken).andExpect(status().isOk).andExpect(jsonPath("$.accountType").value("GUEST"))
	}

	@Test
	fun `NFR-SEC-01 소셜 제공자 장애면 social·link·switch 모두 500 INTERNAL_ERROR이고 아무 행도 바꾸지 않는다`() {
		registerGoogle()
		val guest = startGuest()
		val before = accountSnapshot()
		fakeSocial.providerDown = true

		social("GOOGLE", "fake-google-token").expectError(500, "INTERNAL_ERROR")
		link(guest.accessToken, "GOOGLE", "fake-google-token").expectError(500, "INTERNAL_ERROR")
		switchAccount(guest.accessToken, "GOOGLE", "fake-google-token").expectError(500, "INTERNAL_ERROR")

		assertEquals(before, accountSnapshot())
	}

	@Test
	fun `NFR-SEC-01 신규 가입에서 Kakao 닉네임 조회가 장애면 500 INTERNAL_ERROR이고 회원을 만들지 않는다`() {
		// 토큰 확인은 통과하고 이름을 받을 때만 장애인 상황을 흉내 낸다
		fakeSocial.delegate = object : SocialTokenVerifier {
			override fun verify(provider: SocialProvider, token: String): SocialIdentity = object : SocialIdentity {
				override val provider = SocialProvider.KAKAO
				override val providerUserId = "9001"
				override fun profileName(): String? = throw ApiException(ErrorCode.INTERNAL_ERROR, "kakao unavailable")
			}
		}

		social("KAKAO", "fake-kakao-token").expectError(500, "INTERNAL_ERROR")

		assertEquals(0, count("select count(*) from users"))
		assertEquals(0, count("select count(*) from social_accounts"))
		assertEquals(0, count("select count(*) from auth_sessions"))
	}

	// ── NFR-PRIV-01: 식별자·이름만 ───────────────────────

	@Test
	fun `NFR-PRIV-01 소셜 로그인은 이메일을 저장하거나 로그에 남기지 않는다`() {
		val email = "private-person@example.invalid"
		val clientId = "fake-web-client-id.apps.example.invalid"
		val key: RSAKey = RSAKeyGenerator(2048).keyID("fake-key-1").generate()
		val claims = JWTClaimsSet.Builder()
			.issuer("https://accounts.google.com").audience(clientId).subject("fake-google-sub-1")
			.claim("name", "홍길동").claim("email", email).claim("email_verified", true)
			.issueTime(Date.from(clock.instant())).expirationTime(Date.from(clock.instant().plusSeconds(3600)))
			.build()
		val idToken = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("fake-key-1").build(), claims)
			.also { it.sign(RSASSASigner(key)) }.serialize()
		// Google ID 토큰을 실제 검증기(가짜 공개키)로 확인하는 경로를 끼운다
		val google = GoogleIdTokenVerifier(
			GoogleKeySource { listOf(key.toPublicJWK()) },
			DuriseoProperties(google = DuriseoProperties.Google(webClientId = clientId)),
			clock,
		)
		fakeSocial.delegate = object : SocialTokenVerifier {
			override fun verify(provider: SocialProvider, token: String): SocialIdentity = google.verify(token)
		}

		LogCapture().use { logs ->
			social("GOOGLE", idToken).andExpect(status().isOk).andExpect(jsonPath("$.isNewUser").value(true))
			social("GOOGLE", idToken).andExpect(status().isOk).andExpect(jsonPath("$.isNewUser").value(false))

			val text = logs.text()
			assertFalse(text.contains(email), "이메일이 로그에 남았어요")
			assertFalse(text.contains(idToken), "소셜 토큰이 로그에 남았어요")
		}

		assertEquals("홍길동", jdbc.queryForObject("select nickname from users", String::class.java))
		assertEquals("fake-google-sub-1", jdbc.queryForObject("select provider_user_id from social_accounts", String::class.java))
		listOf("users", "social_accounts", "auth_sessions", "devices", "notification_settings", "spaces", "space_members").forEach { table ->
			assertEquals(0, count("select count(*) from $table t where t::text like ?", "%$email%"), "$table 에 이메일이 있어요")
		}
	}

	@Test
	fun `NFR-SEC-01 소셜 로그인 경로는 소셜 토큰을 로그에 남기지 않는다`() {
		registerGoogle(token = "fake-google-token-secret-value")
		registerKakao(token = "fake-kakao-token-secret-value")

		LogCapture().use { logs ->
			social("GOOGLE", "fake-google-token-secret-value").andExpect(status().isOk)
			social("KAKAO", "fake-kakao-token-secret-value").andExpect(status().isOk)
			social("KAKAO", "unknown-kakao-token-secret-value").expectError(401, "SOCIAL_TOKEN_INVALID")

			val text = logs.text()
			assertFalse(text.contains("fake-google-token-secret-value"))
			assertFalse(text.contains("fake-kakao-token-secret-value"))
			assertFalse(text.contains("unknown-kakao-token-secret-value"))
		}
	}
}
