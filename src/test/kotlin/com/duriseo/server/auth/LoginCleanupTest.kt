package com.duriseo.server.auth

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.auth.application.LoginService
import com.duriseo.server.support.IntegrationTestSupport
import com.duriseo.server.support.TestClockConfig
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
import kotlin.test.assertEquals

/**
 * 로그인 3종 공통 "이전 세션·기기 정리" (API.md 4.2 4단계, ERD 4.6, DB-09).
 * 1부에는 `/auth/social`·`/auth/switch` 경로가 없어 공통 서비스 메서드(`LoginService.login`)를 직접 불러 확인한다.
 * 2부에서 경로가 생기면 같은 확인을 경로로 다시 해도 된다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class LoginCleanupTest : IntegrationTestSupport() {

	@Autowired
	private lateinit var loginService: LoginService

	@Autowired
	private lateinit var userService: UserService

	@Autowired
	private lateinit var transactionManager: PlatformTransactionManager

	/** 다른 기기에서 같은 회원으로 로그인한 것처럼 새 세션을 만든다 */
	private fun loginAgain(userId: java.util.UUID) {
		TransactionTemplate(transactionManager).executeWithoutResult {
			loginService.login(checkNotNull(userService.findActive(userId)), isNewUser = false)
		}
	}

	private fun firstDevice(tokens: com.duriseo.server.support.Tokens) {
		putDevice(tokens.accessToken, """{"fcmToken": "fake-fcm-token-1"}""").andExpect(status().isNoContent)
	}

	@Test
	fun `FR-AUTH-02 AC3 다른 기기에서 로그인하면 이전 세션은 REPLACED가 되고 devices가 모두 지워진다`() {
		val oldDevice = startGuest()
		makeMember(oldDevice.userId)
		firstDevice(oldDevice)
		assertEquals(1, count("select count(*) from devices"))
		clock.advance(Duration.ofMinutes(5))

		loginAgain(oldDevice.userId)

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
		val oldDevice = startGuest()
		makeMember(oldDevice.userId)

		loginAgain(oldDevice.userId)

		getMe(oldDevice.accessToken).expectError(401, "SESSION_REPLACED")
	}

	@Test
	fun `FR-AUTH-02 AC3 이전 기기의 refresh 요청은 401 SESSION_REPLACED`() {
		val oldDevice = startGuest()
		makeMember(oldDevice.userId)

		loginAgain(oldDevice.userId)

		refresh(oldDevice.refreshToken).expectError(401, "SESSION_REPLACED")
	}

	@Test
	fun `FR-AUTH-02 AC3 REPLACED 세션은 access 토큰이 만료된 뒤에도 SESSION_REPLACED를 준다 (DB-11)`() {
		val oldDevice = startGuest()
		makeMember(oldDevice.userId)
		loginAgain(oldDevice.userId)
		clock.advance(Duration.ofDays(3))

		// access 토큰은 만료됐지만 만료 검사는 형식·서명 다음, 세션 검사 앞이므로 먼저 TOKEN_EXPIRED다
		getMe(oldDevice.accessToken).expectError(401, "TOKEN_EXPIRED")
		// 앱은 refresh를 부르고, 그 기준으로 SESSION_REPLACED 안내를 받는다
		refresh(oldDevice.refreshToken).expectError(401, "SESSION_REPLACED")
	}

	@Test
	fun `FR-AUTH-02 AC3 로그인한 사용자에게 유효한 세션은 하나뿐이다`() {
		val first = startGuest()
		makeMember(first.userId)

		repeat(3) { loginAgain(first.userId) }

		assertEquals(4, count("select count(*) from auth_sessions where user_id = ?", first.userId))
		assertEquals(
			1,
			count("select count(*) from auth_sessions where user_id = ? and revoked_at is null and expires_at > ?", first.userId, ts(clock.instant())),
		)
		assertEquals(3, count("select count(*) from auth_sessions where user_id = ? and revoked_reason = 'REPLACED'", first.userId))
	}

	@Test
	fun `FR-AUTH-02 AC3 새 기기의 세션은 정상으로 쓸 수 있다`() {
		val oldDevice = startGuest()
		makeMember(oldDevice.userId)
		loginAgain(oldDevice.userId)

		val newSessionId = jdbc.queryForObject(
			"select id from auth_sessions where revoked_at is null",
			java.util.UUID::class.java,
		)
		// 새 세션의 sid로 만든 access 토큰으로 요청하면 통과한다
		val token = craftToken(sub = oldDevice.userId.toString(), sid = newSessionId.toString())
		getMe(token).andExpect(status().isOk).andExpect(jsonPath("$.id").value(oldDevice.userId.toString()))
	}

	@Test
	fun `FR-AUTH-01 AC1 게스트 시작은 새 사용자라 끊을 세션과 지울 기기가 없다`() {
		val first = startGuest()
		firstDevice(first)

		val second = startGuest()

		// 다른 게스트의 세션·기기는 건드리지 않는다
		getMe(first.accessToken).andExpect(status().isOk)
		getMe(second.accessToken).andExpect(status().isOk)
		assertEquals(1, count("select count(*) from devices"))
		assertEquals(0, count("select count(*) from auth_sessions where revoked_at is not null"))
	}
}
