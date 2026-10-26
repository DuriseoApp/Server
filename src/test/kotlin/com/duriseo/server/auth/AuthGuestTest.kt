package com.duriseo.server.auth

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.IntegrationTestSupport
import com.duriseo.server.support.TestClockConfig
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** FR-AUTH-01 게스트로 시작 (API.md 4.1, ERD 4.1), FR-AUTH-07 AC1 게스트 기본 닉네임 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class AuthGuestTest : IntegrationTestSupport() {

	@Test
	fun `FR-AUTH-01 AC1 게스트 시작은 GUEST 사용자와 개인 공간을 만든다`() {
		mockMvc.perform(post("$API/auth/guest"))
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.accessToken").isNotEmpty)
			.andExpect(jsonPath("$.refreshToken").isNotEmpty)
			.andExpect(jsonPath("$.accessTokenExpiresAt").value("2026-10-26T01:00:00Z"))
			.andExpect(jsonPath("$.refreshTokenExpiresAt").isNotEmpty)
			.andExpect(jsonPath("$.isNewUser").value(true))

		// users: GUEST, 닉네임 "게스트", 시간대 Asia/Seoul
		assertEquals(1, count("select count(*) from users"))
		assertEquals("GUEST", jdbc.queryForObject("select account_type from users", String::class.java))
		assertEquals("게스트", jdbc.queryForObject("select nickname from users", String::class.java))
		assertEquals("Asia/Seoul", jdbc.queryForObject("select time_zone from users", String::class.java))
		assertEquals(0, count("select count(*) from users where deleted_at is not null or profile_image_key is not null"))

		// notification_settings: 4개 모두 true
		assertEquals(
			1,
			count(
				"select count(*) from notification_settings where event_reminder and partner_created and memo_pinned and anniversary",
			),
		)

		// spaces: PERSONAL, ACTIVE, 커플 전용 컬럼 없음
		assertEquals(1, count("select count(*) from spaces"))
		assertEquals(
			1,
			count(
				"select count(*) from spaces where type = 'PERSONAL' and status = 'ACTIVE' and start_date is null " +
					"and together_color is null and unlinked_at is null and purge_at is null",
			),
		)

		// space_members: 주황, 활성
		assertEquals(
			1,
			count(
				"select count(*) from space_members sm join users u on u.id = sm.user_id join spaces s on s.id = sm.space_id " +
					"where sm.color = 'pick-orange' and sm.left_at is null",
			),
		)

		// auth_sessions: 한 행, 유효
		assertEquals(1, count("select count(*) from auth_sessions where revoked_at is null and revoked_reason is null"))
	}

	@Test
	fun `FR-AUTH-01 AC1 게스트 시작의 refresh 토큰은 365일 뒤 만료된다`() {
		val tokens = startGuest()

		val expected = START.plus(Duration.ofDays(365))
		assertEquals(expected.toString(), tokens.refreshTokenExpiresAt)
		assertEquals(expected, instantOf("select expires_at from auth_sessions"))
		assertEquals(43, tokens.refreshToken.length)
	}

	@Test
	fun `FR-AUTH-01 AC1 게스트 시작의 access 토큰에는 새 세션 ID가 sid로 들어 있다`() {
		val tokens = startGuest()

		val sessionId = jdbc.queryForObject("select id from auth_sessions", java.util.UUID::class.java)
		assertEquals(sessionId, tokens.sessionId)
		assertEquals(tokens.userId, jdbc.queryForObject("select id from users", java.util.UUID::class.java))
	}

	@Test
	fun `NFR-SEC-01 access 토큰은 HS256이고 sub·sid·iat·exp 클레임을 가지며 계정 종류는 담지 않는다`() {
		val tokens = startGuest()

		val jwt = SignedJWT.parse(tokens.accessToken)
		assertEquals(JWSAlgorithm.HS256, jwt.header.algorithm)
		val claims = jwt.getJWTClaimsSet()
		assertEquals(START, claims.issueTime.toInstant())
		assertEquals(START.plus(Duration.ofHours(1)), claims.expirationTime.toInstant())
		assertEquals(setOf("sub", "sid", "iat", "exp"), claims.claims.keys)
	}

	@Test
	fun `FR-AUTH-01 AC3 게스트 시작은 부를 때마다 새 계정을 만든다`() {
		val first = startGuest()
		val second = startGuest()

		assertNotEquals(first.userId, second.userId)
		assertNotEquals(first.sessionId, second.sessionId)
		assertEquals(2, count("select count(*) from users"))
		assertEquals(2, count("select count(*) from spaces"))
		// 게스트 계정을 되찾는 경로는 없다: 서로 다른 계정의 세션이라 서로 끊지 않는다
		assertEquals(2, count("select count(*) from auth_sessions where revoked_at is null"))
		assertTrue(first.refreshToken != second.refreshToken)
	}

	@Test
	fun `FR-AUTH-07 AC1 게스트의 기본 닉네임은 게스트다`() {
		val tokens = startGuest()

		getMe(tokens.accessToken).andExpect(status().isOk).andExpect(jsonPath("$.nickname").value("게스트"))
	}
}
