package com.duriseo.server.auth

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.IntegrationTestSupport
import com.duriseo.server.support.TestClockConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** access·refresh 토큰과 세션 규칙 (API.md 1장, NFR-SEC-01, D-73, DB-02) */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class AuthTokenTest : IntegrationTestSupport() {

	// ── access 토큰 ─────────────────────────────────────

	@Test
	fun `NFR-SEC-01 1시간이 지난 access 토큰은 401 TOKEN_EXPIRED`() {
		val tokens = startGuest()

		clock.advance(Duration.ofHours(1).plusSeconds(1))

		getMe(tokens.accessToken).expectError(401, "TOKEN_EXPIRED")
	}

	@Test
	fun `NFR-SEC-01 1시간이 지나기 전의 access 토큰은 사용할 수 있다`() {
		val tokens = startGuest()

		clock.advance(Duration.ofMinutes(59).plusSeconds(59))

		getMe(tokens.accessToken).andExpect(status().isOk)
	}

	@Test
	fun `NFR-SEC-01 서명·만료가 맞아도 세션이 무효면 401 SESSION_INVALID`() {
		val tokens = startGuest()
		jdbc.update("update auth_sessions set revoked_at = ?, revoked_reason = 'LOGOUT'", ts(clock.instant()))

		getMe(tokens.accessToken).expectError(401, "SESSION_INVALID")
	}

	@Test
	fun `NFR-SEC-01 세션 자체가 만료됐으면 401 SESSION_INVALID`() {
		val tokens = startGuest()
		jdbc.update("update auth_sessions set expires_at = ?", ts(clock.instant().minusSeconds(1)))

		getMe(tokens.accessToken).expectError(401, "SESSION_INVALID")
	}

	@Test
	fun `NFR-SEC-01 sid의 세션이 없으면 401 SESSION_INVALID`() {
		val tokens = startGuest()
		val token = craftToken(sub = tokens.userId.toString(), sid = UUID.randomUUID().toString())

		getMe(token).expectError(401, "SESSION_INVALID")
	}

	@Test
	fun `NFR-SEC-01 sid의 세션이 다른 사용자의 것이면 401 SESSION_INVALID`() {
		val tokens = startGuest()
		val token = craftToken(sub = UUID.randomUUID().toString(), sid = tokens.sessionId.toString())

		getMe(token).expectError(401, "SESSION_INVALID")
	}

	@Test
	fun `NFR-SEC-01 Authorization 헤더가 없으면 401 SESSION_INVALID`() {
		mockMvc.perform(get("$API/me")).expectError(401, "SESSION_INVALID")
	}

	@Test
	fun `NFR-SEC-01 Bearer 형식이 아닌 헤더는 401 SESSION_INVALID`() {
		val tokens = startGuest()

		mockMvc.perform(get("$API/me").header("Authorization", "Basic ${tokens.accessToken}")).expectError(401, "SESSION_INVALID")
		mockMvc.perform(get("$API/me").header("Authorization", tokens.accessToken)).expectError(401, "SESSION_INVALID")
		mockMvc.perform(get("$API/me").header("Authorization", "Bearer ")).expectError(401, "SESSION_INVALID")
	}

	@Test
	fun `NFR-SEC-01 JWT 형식이 아닌 access 토큰은 401 SESSION_INVALID`() {
		getMe("not-a-jwt").expectError(401, "SESSION_INVALID")
	}

	@Test
	fun `NFR-SEC-01 서명이 틀린 access 토큰은 401 SESSION_INVALID`() {
		val forged = craftToken(secret = "another-fake-secret-that-is-long-enough-for-hs256")

		getMe(forged).expectError(401, "SESSION_INVALID")
	}

	@Test
	fun `NFR-SEC-01 서명이 틀리면 만료 여부와 관계없이 401 SESSION_INVALID다 (형식·서명이 만료보다 먼저)`() {
		val forgedAndExpired = craftToken(
			secret = "another-fake-secret-that-is-long-enough-for-hs256",
			iat = clock.instant().minusSeconds(7200),
			exp = clock.instant().minusSeconds(3600),
		)

		getMe(forgedAndExpired).expectError(401, "SESSION_INVALID")
	}

	@Test
	fun `NFR-SEC-01 필요한 클레임이 없는 access 토큰은 401 SESSION_INVALID`() {
		getMe(craftToken(sub = null)).expectError(401, "SESSION_INVALID")
		getMe(craftToken(sid = null)).expectError(401, "SESSION_INVALID")
		getMe(craftToken(iat = null)).expectError(401, "SESSION_INVALID")
		getMe(craftToken(exp = null)).expectError(401, "SESSION_INVALID")
		getMe(craftToken(sub = "not-a-uuid")).expectError(401, "SESSION_INVALID")
		getMe(craftToken(sid = "not-a-uuid")).expectError(401, "SESSION_INVALID")
	}

	@Test
	fun `NFR-SEC-01 공개 경로 auth 아래 guest·social·refresh는 access 토큰 없이 부를 수 있다`() {
		// guest: 토큰 없이 201
		val tokens = startGuest()
		// refresh: access 토큰 없이 본문의 refresh 토큰만으로 200
		refresh(tokens.refreshToken).andExpect(status().isOk)
		// social: 2부에서 경로가 생긴다. 지금은 없는 경로라 NOT_FOUND이지만 어느 경우에도 인증 실패(401)는 아니다
		val social = mockMvc.perform(
			post("$API/auth/social").contentType(MediaType.APPLICATION_JSON).content("""{"provider":"GOOGLE","token":"x"}"""),
		).andReturn().response.status
		assertNotEquals(401, social)
	}

	// ── refresh 토큰 ────────────────────────────────────

	@Test
	fun `FR-AUTH-01 AC2 저장한 refresh 토큰으로 같은 게스트 계정의 새 토큰을 받는다`() {
		val first = startGuest()
		clock.advance(Duration.ofHours(2)) // access 토큰이 만료된 뒤 앱을 다시 열었다
		getMe(first.accessToken).expectError(401, "TOKEN_EXPIRED")

		val result = refresh(first.refreshToken)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.isNewUser").value(false))
			.andReturn()
		val second = tokensOf(result.response.getContentAsString(Charsets.UTF_8))

		assertEquals(first.userId, second.userId)
		assertNotEquals(first.refreshToken, second.refreshToken)
		assertEquals(1, count("select count(*) from users"))
		getMe(second.accessToken)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.id").value(first.userId.toString()))
			.andExpect(jsonPath("$.accountType").value("GUEST"))
	}

	@Test
	fun `NFR-SEC-01 refresh는 같은 세션 행의 해시·만료·last_used_at만 바꾸고 sid는 그대로다`() {
		val first = startGuest()
		val createdAt = instantOf("select created_at from auth_sessions")
		val hashBefore = jdbc.queryForObject("select refresh_token_hash from auth_sessions", String::class.java)

		clock.advance(Duration.ofMinutes(10))
		val result = refresh(first.refreshToken).andExpect(status().isOk).andReturn()
		val second = tokensOf(result.response.getContentAsString(Charsets.UTF_8))

		assertEquals(1, count("select count(*) from auth_sessions"))
		assertEquals(first.sessionId, second.sessionId)
		assertEquals(first.sessionId, jdbc.queryForObject("select id from auth_sessions", UUID::class.java))
		assertNotEquals(hashBefore, jdbc.queryForObject("select refresh_token_hash from auth_sessions", String::class.java))
		assertEquals(START.plus(Duration.ofMinutes(10)), instantOf("select last_used_at from auth_sessions"))
		assertEquals(START.plus(Duration.ofMinutes(10)).plus(Duration.ofDays(365)), instantOf("select expires_at from auth_sessions"))
		assertEquals(createdAt, instantOf("select created_at from auth_sessions"))
		assertEquals(0, count("select count(*) from auth_sessions where revoked_at is not null"))
	}

	@Test
	fun `NFR-SEC-01 이미 교체된 refresh 토큰을 다시 보내면 401 SESSION_INVALID`() {
		val first = startGuest()
		refresh(first.refreshToken).andExpect(status().isOk)

		refresh(first.refreshToken).expectError(401, "SESSION_INVALID")
	}

	@Test
	fun `NFR-SEC-01 알 수 없는 refresh 토큰은 401 SESSION_INVALID`() {
		refresh("x".repeat(43)).expectError(401, "SESSION_INVALID")
	}

	@Test
	fun `NFR-SEC-01 만료된 refresh 토큰은 401 SESSION_INVALID`() {
		val tokens = startGuest()

		clock.advance(Duration.ofDays(365).plusSeconds(1))

		refresh(tokens.refreshToken).expectError(401, "SESSION_INVALID")
	}

	@Test
	fun `NFR-SEC-01 refresh는 만료를 회원 30일·게스트 365일로 다시 늘린다`() {
		val guest = startGuest()
		clock.advance(Duration.ofDays(10))
		val refreshedAsGuest = tokensOf(refresh(guest.refreshToken).andExpect(status().isOk).andReturn().response.getContentAsString(Charsets.UTF_8))
		assertEquals(clock.instant().plus(Duration.ofDays(365)), instantOf("select expires_at from auth_sessions"))

		// 게스트 연동(2부)이 계정을 회원으로 바꾼 뒤의 첫 갱신부터 30일이다
		makeMember(guest.userId)
		clock.advance(Duration.ofDays(1))
		val result = refresh(refreshedAsGuest.refreshToken)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.refreshTokenExpiresAt").value(clock.instant().plus(Duration.ofDays(30)).toString()))
			.andReturn()
		tokensOf(result.response.getContentAsString(Charsets.UTF_8))
		assertEquals(clock.instant().plus(Duration.ofDays(30)), instantOf("select expires_at from auth_sessions"))
	}

	@Test
	fun `NFR-SEC-01 auth_sessions에는 refresh 토큰의 SHA-256 해시만 저장한다`() {
		val tokens = startGuest()

		val stored = jdbc.queryForObject("select refresh_token_hash from auth_sessions", String::class.java)
		assertEquals(sha256Hex(tokens.refreshToken), stored)
		assertEquals(64, stored!!.length)
		assertTrue(stored != tokens.refreshToken)
		// 원문은 어떤 컬럼에도 없다
		assertEquals(0, count("select count(*) from auth_sessions where refresh_token_hash = ?", tokens.refreshToken))

		// 교체한 뒤에도 해시만 있다
		val second = tokensOf(refresh(tokens.refreshToken).andReturn().response.getContentAsString(Charsets.UTF_8))
		assertEquals(sha256Hex(second.refreshToken), jdbc.queryForObject("select refresh_token_hash from auth_sessions", String::class.java))
	}

	@Test
	fun `NFR-SEC-01 refreshToken이 없거나 비면 400 VALIDATION_FAILED (refreshToken REQUIRED)`() {
		mockMvc.perform(post("$API/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.expectError(400, "VALIDATION_FAILED")
		mockMvc.perform(post("$API/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""{"refreshToken": "  "}"""))
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.details.fields[0].field").value("refreshToken"))
			.andExpect(jsonPath("$.details.fields[0].reason").value("REQUIRED"))
	}
}
