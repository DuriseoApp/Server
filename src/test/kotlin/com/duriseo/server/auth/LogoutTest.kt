package com.duriseo.server.auth

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.auth.application.LoginService
import com.duriseo.server.support.IntegrationTestSupport
import com.duriseo.server.support.TestClockConfig
import com.duriseo.server.user.domain.SocialProvider
import com.duriseo.server.user.application.UserService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals

/** FR-AUTH-05 로그아웃 (API.md 3.1, ERD 4.6, DB-09), R-17 (회원만) */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class LogoutTest : IntegrationTestSupport() {

	@Autowired
	private lateinit var loginService: LoginService

	@Autowired
	private lateinit var userService: UserService

	@Autowired
	private lateinit var transactionManager: PlatformTransactionManager

	/** 회원 한 명이 로그인하고 기기를 등록한 상태 */
	private fun memberWithDevice(): com.duriseo.server.support.Tokens {
		val tokens = startGuest()
		makeMember(tokens.userId)
		putDevice(tokens.accessToken, """{"fcmToken": "fake-fcm-token-1"}""").andExpect(status().isNoContent)
		return tokens
	}

	@Test
	fun `FR-AUTH-05 AC1 로그아웃하면 현재 세션이 LOGOUT이 되고 devices가 모두 지워진다`() {
		val tokens = memberWithDevice()
		clock.advance(Duration.ofMinutes(3))

		logout(tokens.accessToken).andExpect(status().isNoContent)

		assertEquals(
			1,
			count(
				"select count(*) from auth_sessions where id = ? and revoked_reason = 'LOGOUT' and revoked_at = ?",
				tokens.sessionId, ts(clock.instant()),
			),
		)
		assertEquals(0, count("select count(*) from devices"))
	}

	@Test
	fun `FR-AUTH-05 AC1 로그아웃 뒤 같은 access 토큰은 401 SESSION_INVALID`() {
		val tokens = memberWithDevice()

		logout(tokens.accessToken).andExpect(status().isNoContent)

		getMe(tokens.accessToken).expectError(401, "SESSION_INVALID")
	}

	@Test
	fun `FR-AUTH-05 AC1 로그아웃 뒤 refresh는 401 SESSION_INVALID`() {
		val tokens = memberWithDevice()

		logout(tokens.accessToken).andExpect(status().isNoContent)

		refresh(tokens.refreshToken).expectError(401, "SESSION_INVALID")
	}

	@Test
	fun `FR-AUTH-05 AC1 로그아웃해도 사용자와 공간 데이터는 남고 다시 로그인하면 그대로다`() {
		val tokens = memberWithDevice()
		val spaceBefore = jdbc.queryForObject("select id from spaces", UUID::class.java)

		logout(tokens.accessToken).andExpect(status().isNoContent)

		assertEquals(1, count("select count(*) from users where deleted_at is null"))
		assertEquals(1, count("select count(*) from spaces"))
		assertEquals(1, count("select count(*) from space_members where left_at is null"))
		assertEquals(1, count("select count(*) from notification_settings"))
		assertEquals(1, count("select count(*) from social_accounts"))

		// 다시 로그인(2부의 소셜 로그인이 쓰는 공통 서비스)하면 같은 계정·같은 공간이다
		var relogin: String? = null
		TransactionTemplate(transactionManager).executeWithoutResult {
			relogin = loginService.login(checkNotNull(userService.findActive(tokens.userId)), isNewUser = false).accessToken
		}
		getMe(checkNotNull(relogin))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.id").value(tokens.userId.toString()))
			.andExpect(jsonPath("$.space.id").value(spaceBefore.toString()))
	}

	@Test
	fun `FR-AUTH-05 AC2 게스트가 로그아웃을 부르면 403 GUEST_NOT_ALLOWED`() {
		val guest = startGuest()

		logout(guest.accessToken).expectError(403, "GUEST_NOT_ALLOWED")
	}

	@Test
	fun `R-17 회원은 로그아웃할 수 있다`() {
		val member = memberWithDevice()

		logout(member.accessToken).andExpect(status().isNoContent)
	}

	@Test
	fun `R-17 연동한 게스트는 같은 access 토큰으로 로그아웃할 수 있다`() {
		val guest = startGuest()
		logout(guest.accessToken).expectError(403, "GUEST_NOT_ALLOWED")

		// 게스트 연동(POST /auth/link)이 계정을 MEMBER로 바꾼다. access 토큰에는 계정 종류가 없으므로 같은 토큰으로 바로 통과한다
		registerSocial(SocialProvider.GOOGLE, "fake-google-token", "fake-google-sub-1", "홍길동")
		link(guest.accessToken, "GOOGLE", "fake-google-token").andExpect(status().isNoContent)

		logout(guest.accessToken).andExpect(status().isNoContent)
	}

	@Test
	fun `R-17 게스트가 로그아웃을 부르면 403 GUEST_NOT_ALLOWED이고 세션과 devices가 그대로다`() {
		val guest = startGuest()
		putDevice(guest.accessToken, """{"fcmToken": "fake-fcm-token-1"}""").andExpect(status().isNoContent)

		logout(guest.accessToken).expectError(403, "GUEST_NOT_ALLOWED")

		assertEquals(1, count("select count(*) from auth_sessions where revoked_at is null and revoked_reason is null"))
		assertEquals(1, count("select count(*) from devices"))
		// 세션이 살아 있어 같은 토큰이 계속 쓰인다
		getMe(guest.accessToken).andExpect(status().isOk)
	}

	@Test
	fun `R-17 로그아웃은 access 토큰이 없으면 401 SESSION_INVALID다`() {
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("$API/auth/logout"))
			.expectError(401, "SESSION_INVALID")
	}
}
