package com.duriseo.server.auth

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.IntegrationTestSupport
import com.duriseo.server.support.TestClockConfig
import com.duriseo.server.support.Tokens
import com.duriseo.server.user.domain.SocialProvider
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import kotlin.test.assertEquals

/**
 * 로그인 3종 공통 "이전 세션·기기 정리" (API.md 4.2 4단계, ERD 4.6, DB-09).
 * 소셜 로그인 경로(`POST /auth/social`)로 같은 회원이 다른 기기에서 다시 로그인하는 것을 확인한다.
 * `POST /auth/switch`가 같은 정리를 쓰는 것은 `AuthSwitchTest`에서 확인한다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class LoginCleanupTest : IntegrationTestSupport() {

	private fun registerGoogleAccount() {
		registerSocial(SocialProvider.GOOGLE, "fake-google-token", "fake-google-sub-1", "홍길동")
	}

	/** 같은 Google 계정으로 로그인한다. 처음이면 가입이고, 다시 부르면 다른 기기에서의 로그인이다 */
	private fun loginWithGoogle(): Tokens = socialTokens("GOOGLE", "fake-google-token")

	private fun registerDevice(tokens: Tokens) {
		putDevice(tokens.accessToken, """{"fcmToken": "fake-fcm-token-1"}""").andExpect(status().isNoContent)
	}

	@Test
	fun `FR-AUTH-02 AC3 다른 기기에서 로그인하면 이전 세션은 REPLACED가 되고 devices가 모두 지워진다`() {
		registerGoogleAccount()
		val oldDevice = loginWithGoogle()
		registerDevice(oldDevice)
		assertEquals(1, count("select count(*) from devices"))
		clock.advance(Duration.ofMinutes(5))

		loginWithGoogle()

		assertEquals(2, count("select count(*) from auth_sessions"))
		assertEquals(
			1,
			count("select count(*) from auth_sessions where id = ? and revoked_reason = 'REPLACED' and revoked_at = ?", oldDevice.sessionId, ts(clock.instant())),
		)
		assertEquals(0, count("select count(*) from devices"))
		// 사용자와 공간은 새로 만들어지지 않는다
		assertEquals(1, count("select count(*) from users"))
		assertEquals(1, count("select count(*) from spaces"))
	}

	@Test
	fun `FR-AUTH-02 AC3 이전 기기의 access 토큰 요청은 401 SESSION_REPLACED`() {
		registerGoogleAccount()
		val oldDevice = loginWithGoogle()

		loginWithGoogle()

		getMe(oldDevice.accessToken).expectError(401, "SESSION_REPLACED")
	}

	@Test
	fun `FR-AUTH-02 AC3 이전 기기의 refresh 요청은 401 SESSION_REPLACED`() {
		registerGoogleAccount()
		val oldDevice = loginWithGoogle()

		loginWithGoogle()

		refresh(oldDevice.refreshToken).expectError(401, "SESSION_REPLACED")
	}

	@Test
	fun `FR-AUTH-02 AC3 REPLACED 세션은 access 토큰이 만료된 뒤에도 SESSION_REPLACED를 준다 (DB-11)`() {
		registerGoogleAccount()
		val oldDevice = loginWithGoogle()
		loginWithGoogle()
		clock.advance(Duration.ofDays(3))

		// access 토큰은 만료됐지만 만료 검사는 형식·서명 다음, 세션 검사 앞이므로 먼저 TOKEN_EXPIRED다
		getMe(oldDevice.accessToken).expectError(401, "TOKEN_EXPIRED")
		// 앱은 refresh를 부르고, 그 기준으로 SESSION_REPLACED 안내를 받는다
		refresh(oldDevice.refreshToken).expectError(401, "SESSION_REPLACED")
	}

	@Test
	fun `FR-AUTH-02 AC3 로그인한 사용자에게 유효한 세션은 하나뿐이다`() {
		registerGoogleAccount()
		val first = loginWithGoogle()

		repeat(3) { loginWithGoogle() }

		assertEquals(4, count("select count(*) from auth_sessions where user_id = ?", first.userId))
		assertEquals(
			1,
			count("select count(*) from auth_sessions where user_id = ? and revoked_at is null and expires_at > ?", first.userId, ts(clock.instant())),
		)
		assertEquals(3, count("select count(*) from auth_sessions where user_id = ? and revoked_reason = 'REPLACED'", first.userId))
	}

	@Test
	fun `FR-AUTH-02 AC3 새 기기의 세션은 정상으로 쓸 수 있다`() {
		registerGoogleAccount()
		val oldDevice = loginWithGoogle()

		val newDevice = loginWithGoogle()

		assertEquals(oldDevice.userId, newDevice.userId)
		getMe(newDevice.accessToken).andExpect(status().isOk).andExpect(jsonPath("$.id").value(oldDevice.userId.toString()))
	}

	@Test
	fun `FR-AUTH-02 AC3 다른 사용자의 세션과 기기는 건드리지 않는다`() {
		registerGoogleAccount()
		registerSocial(SocialProvider.GOOGLE, "fake-google-token-2", "fake-google-sub-2", "김철수")
		val other = socialTokens("GOOGLE", "fake-google-token-2")
		registerDevice(other)
		val guest = startGuest()

		loginWithGoogle()
		loginWithGoogle()

		getMe(other.accessToken).andExpect(status().isOk)
		getMe(guest.accessToken).andExpect(status().isOk)
		assertEquals(1, count("select count(*) from devices"))
	}

	@Test
	fun `FR-AUTH-01 AC1 게스트 시작은 새 사용자라 끊을 세션과 지울 기기가 없다`() {
		val first = startGuest()
		registerDevice(first)

		val second = startGuest()

		// 다른 게스트의 세션·기기는 건드리지 않는다
		getMe(first.accessToken).andExpect(status().isOk)
		getMe(second.accessToken).andExpect(status().isOk)
		assertEquals(1, count("select count(*) from devices"))
		assertEquals(0, count("select count(*) from auth_sessions where revoked_at is not null"))
	}
}
