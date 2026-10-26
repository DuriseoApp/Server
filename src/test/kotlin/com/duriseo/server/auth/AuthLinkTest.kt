package com.duriseo.server.auth

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.IntegrationTestSupport
import com.duriseo.server.support.TestClockConfig
import com.duriseo.server.user.domain.SocialProvider
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals

/**
 * 게스트 → 회원 연동 `POST /auth/link` (API.md 4.3, FR-AUTH-03 AC1·AC2, R-14).
 * 소셜 제공자는 가짜로 바꿨다 (`FakeSocialTokenVerifier`).
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class AuthLinkTest : IntegrationTestSupport() {

	private fun registerGoogle(token: String = "fake-google-token", sub: String = "fake-google-sub-1", name: String? = "홍길동") =
		registerSocial(SocialProvider.GOOGLE, token, sub, name)

	private fun registerKakao(token: String = "fake-kakao-token", id: String = "9001", name: String? = "카카오닉") =
		registerSocial(SocialProvider.KAKAO, token, id, name)

	private fun nicknameOf(userId: UUID): String =
		jdbc.queryForObject("select nickname from users where id = ?", String::class.java, userId)!!

	// ── FR-AUTH-03 AC1 ──────────────────────────────────

	@Test
	fun `FR-AUTH-03 AC1 게스트가 연동하면 같은 사용자 ID로 MEMBER가 되고 공간과 기록이 그대로다`() {
		registerGoogle()
		val guest = startGuest()
		val spaceId = jdbc.queryForObject("select id from spaces", UUID::class.java)!!
		val memoId = insertMemo(spaceId, guest.userId)
		val eventId = insertEvent(spaceId, guest.userId)

		link(guest.accessToken, "GOOGLE", "fake-google-token")
			.andExpect(status().isNoContent)
			.andExpect(content().string(""))

		assertEquals(1, count("select count(*) from users"))
		assertEquals("MEMBER", jdbc.queryForObject("select account_type from users where id = ?", String::class.java, guest.userId))
		assertEquals(1, count("select count(*) from social_accounts where user_id = ? and provider = 'GOOGLE' and provider_user_id = 'fake-google-sub-1'", guest.userId))
		// 공간과 기록은 그대로다
		assertEquals(1, count("select count(*) from spaces"))
		assertEquals(1, count("select count(*) from space_members where space_id = ? and user_id = ? and left_at is null", spaceId, guest.userId))
		assertEquals(1, count("select count(*) from memos where id = ? and author_id = ?", memoId, guest.userId))
		assertEquals(1, count("select count(*) from events where id = ? and author_id = ?", eventId, guest.userId))
		// 앱은 연동 뒤 GET /me로 다시 읽는다
		getMe(guest.accessToken)
			.andExpect(jsonPath("$.id").value(guest.userId.toString()))
			.andExpect(jsonPath("$.accountType").value("MEMBER"))
			.andExpect(jsonPath("$.providers[0]").value("GOOGLE"))
			.andExpect(jsonPath("$.space.id").value(spaceId.toString()))
	}

	@Test
	fun `FR-AUTH-03 AC1 닉네임이 게스트면 연동할 때 소셜 프로필 이름(앞 10자)으로 바뀐다`() {
		registerGoogle(name = "가나다라마바사아자차카타")
		registerKakao(name = "  카카오닉  ")
		val googleGuest = startGuest()
		val kakaoGuest = startGuest()

		link(googleGuest.accessToken, "GOOGLE", "fake-google-token").andExpect(status().isNoContent)
		link(kakaoGuest.accessToken, "KAKAO", "fake-kakao-token").andExpect(status().isNoContent)

		assertEquals("가나다라마바사아자차", nicknameOf(googleGuest.userId))
		assertEquals("카카오닉", nicknameOf(kakaoGuest.userId))
		// 닉네임을 바꿔야 하므로 Kakao 프로필 이름을 받았다
		assertEquals(2, fakeSocial.nameLookups.get())
	}

	@Test
	fun `FR-AUTH-03 AC1 직접 바꾼 닉네임은 연동해도 그대로다`() {
		registerKakao()
		val guest = startGuest()
		patchMe(guest.accessToken, """{"nickname": "내닉네임"}""").andExpect(status().isOk)

		link(guest.accessToken, "KAKAO", "fake-kakao-token").andExpect(status().isNoContent)

		assertEquals("내닉네임", nicknameOf(guest.userId))
		// 닉네임이 필요 없으면 Kakao 프로필 이름을 받지 않는다 (API.md 4.3 1단계)
		assertEquals(0, fakeSocial.nameLookups.get())
	}

	@Test
	fun `FR-AUTH-03 AC1 소셜 이름이 없거나 공백뿐이면 연동할 때 닉네임은 사용자가 된다`() {
		registerGoogle(name = null)
		registerKakao(name = "   ")
		val googleGuest = startGuest()
		val kakaoGuest = startGuest()

		link(googleGuest.accessToken, "GOOGLE", "fake-google-token").andExpect(status().isNoContent)
		link(kakaoGuest.accessToken, "KAKAO", "fake-kakao-token").andExpect(status().isNoContent)

		assertEquals("사용자", nicknameOf(googleGuest.userId))
		assertEquals("사용자", nicknameOf(kakaoGuest.userId))
	}

	@Test
	fun `FR-AUTH-03 AC1 연동은 현재 세션·sid·devices를 그대로 두고 새 토큰을 주지 않는다`() {
		registerGoogle()
		val guest = startGuest()
		putDevice(guest.accessToken, """{"fcmToken": "fake-fcm-token-1"}""").andExpect(status().isNoContent)
		val sessionBefore = jdbc.queryForList("select id, refresh_token_hash, expires_at, last_used_at, revoked_at, revoked_reason from auth_sessions").toString()
		val devicesBefore = jdbc.queryForList("select id, user_id, fcm_token, updated_at from devices").toString()
		clock.advance(Duration.ofMinutes(10))

		val result = link(guest.accessToken, "GOOGLE", "fake-google-token").andExpect(status().isNoContent).andReturn()

		// 본문이 없으니 새 토큰도 없다
		assertEquals("", result.response.getContentAsString(Charsets.UTF_8))
		// 세션 행(해시·만료·sid)과 기기는 그대로, 다른 세션을 끊지도 않는다
		assertEquals(sessionBefore, jdbc.queryForList("select id, refresh_token_hash, expires_at, last_used_at, revoked_at, revoked_reason from auth_sessions").toString())
		assertEquals(devicesBefore, jdbc.queryForList("select id, user_id, fcm_token, updated_at from devices").toString())
		assertEquals(1, count("select count(*) from auth_sessions"))
		// 같은 access 토큰과 refresh 토큰을 계속 쓴다
		getMe(guest.accessToken).andExpect(status().isOk)
		refresh(guest.refreshToken).andExpect(status().isOk)
	}

	@Test
	fun `FR-AUTH-03 AC1 연동 뒤 첫 refresh부터 만료가 30일이다`() {
		registerGoogle()
		val guest = startGuest()
		link(guest.accessToken, "GOOGLE", "fake-google-token").andExpect(status().isNoContent)
		// 연동 직후에는 게스트 때의 365일 그대로다
		assertEquals(START.plus(Duration.ofDays(365)), instantOf("select expires_at from auth_sessions"))
		clock.advance(Duration.ofMinutes(5))

		val refreshed = refresh(guest.refreshToken).andExpect(status().isOk).andReturn()

		val tokens = tokensOf(refreshed.response.getContentAsString(Charsets.UTF_8))
		assertEquals(clock.instant().plus(Duration.ofDays(30)).toString(), tokens.refreshTokenExpiresAt)
		assertEquals(clock.instant().plus(Duration.ofDays(30)), instantOf("select expires_at from auth_sessions"))
		assertEquals(guest.sessionId, tokens.sessionId)
	}

	// ── FR-AUTH-03 AC2 ──────────────────────────────────

	@Test
	fun `FR-AUTH-03 AC2 이미 다른 회원이 쓰는 소셜 계정으로 연동하면 409 SOCIAL_ALREADY_USED이고 아무것도 바뀌지 않는다`() {
		registerGoogle()
		val member = socialTokens("GOOGLE", "fake-google-token")
		patchMe(member.accessToken, """{"nickname": "원래회원"}""").andExpect(status().isOk)
		// 다른 기기의 게스트가 같은 Google 계정으로 연동하려 한다 (토큰은 다르지만 같은 계정)
		registerGoogle(token = "fake-google-token-other-device", name = "다른이름")
		val guest = startGuest()
		putDevice(guest.accessToken, """{"fcmToken": "fake-fcm-token-1"}""").andExpect(status().isNoContent)
		val before = accountSnapshot()

		link(guest.accessToken, "GOOGLE", "fake-google-token-other-device").expectError(409, "SOCIAL_ALREADY_USED")

		// 데이터를 합치지 않고 아무것도 바꾸지 않는다
		assertEquals(before, accountSnapshot())
		assertEquals("게스트", nicknameOf(guest.userId))
		assertEquals("GUEST", jdbc.queryForObject("select account_type from users where id = ?", String::class.java, guest.userId))
		assertEquals("원래회원", nicknameOf(member.userId))
		// 게스트는 계속 쓸 수 있고, 앱은 이어서 /auth/switch를 부를 수 있다
		getMe(guest.accessToken).andExpect(status().isOk).andExpect(jsonPath("$.accountType").value("GUEST"))
	}

	@Test
	fun `FR-AUTH-03 AC2 같은 ID라도 제공자가 다르면 다른 소셜 계정이라 연동할 수 있다`() {
		registerGoogle(sub = "same-id-123")
		socialTokens("GOOGLE", "fake-google-token")
		registerKakao(id = "same-id-123")
		val guest = startGuest()

		link(guest.accessToken, "KAKAO", "fake-kakao-token").andExpect(status().isNoContent)

		assertEquals(2, count("select count(*) from social_accounts"))
	}

	// ── R-14 ────────────────────────────────────────────

	@Test
	fun `R-14 게스트는 auth 아래 link를 부를 수 있다`() {
		registerGoogle()
		val guest = startGuest()

		link(guest.accessToken, "GOOGLE", "fake-google-token").andExpect(status().isNoContent)
	}

	@Test
	fun `R-14 회원이 auth 아래 link를 부르면 403 GUEST_ONLY이고 아무것도 바뀌지 않는다`() {
		registerGoogle()
		registerKakao()
		val member = socialTokens("GOOGLE", "fake-google-token")
		putDevice(member.accessToken, """{"fcmToken": "fake-fcm-token-1"}""").andExpect(status().isNoContent)
		val before = accountSnapshot()
		val verifyCallsBefore = fakeSocial.verifyCalls.get()

		link(member.accessToken, "KAKAO", "fake-kakao-token").expectError(403, "GUEST_ONLY")

		assertEquals(before, accountSnapshot())
		assertEquals(1, count("select count(*) from social_accounts"))
		// R-14는 소셜 토큰 검증보다 먼저다: 검증기를 부르지 않았고, 틀린 토큰이어도 401이 아니라 403이다
		assertEquals(verifyCallsBefore, fakeSocial.verifyCalls.get())
		link(member.accessToken, "KAKAO", "unknown-token").expectError(403, "GUEST_ONLY")
		assertEquals(verifyCallsBefore, fakeSocial.verifyCalls.get())
	}

	@Test
	fun `R-14 연동한 직후 같은 access 토큰으로 auth 아래 link를 다시 부르면 403 GUEST_ONLY`() {
		registerGoogle()
		registerKakao()
		val guest = startGuest()
		link(guest.accessToken, "GOOGLE", "fake-google-token").andExpect(status().isNoContent)
		val before = accountSnapshot()

		// 계정 종류는 토큰이 아니라 DB에서 읽으므로 같은 토큰으로 바로 회원 취급이다
		link(guest.accessToken, "KAKAO", "fake-kakao-token").expectError(403, "GUEST_ONLY")

		assertEquals(before, accountSnapshot())
		assertEquals(1, count("select count(*) from social_accounts"))
	}

	@Test
	fun `R-14 auth 아래 link는 access 토큰이 없으면 401 SESSION_INVALID다`() {
		registerGoogle()

		link("not-a-token", "GOOGLE", "fake-google-token").expectError(401, "SESSION_INVALID")
		assertEquals(0, fakeSocial.verifyCalls.get())
	}

	@Test
	fun `FR-AUTH-03 AC1 연동 요청의 provider나 token이 없으면 400 VALIDATION_FAILED`() {
		val guest = startGuest()

		link(guest.accessToken, "FACEBOOK", "x").expectError(400, "VALIDATION_FAILED")
		link(guest.accessToken, "GOOGLE", "  ").expectError(400, "VALIDATION_FAILED")
	}
}
