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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 게스트 → 기존 계정 로그인 `POST /auth/switch` (API.md 4.7, ERD 4.3·4.6, DB-12, FR-AUTH-03 AC3, R-14).
 * 소셜 제공자는 가짜로 바꿨다 (`FakeSocialTokenVerifier`).
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class AuthSwitchTest : IntegrationTestSupport() {

	/** 대상 회원: Google 계정으로 가입해 개인 공간에 메모를 하나 쓴 상태 */
	private class Member(val tokens: Tokens, val spaceId: UUID, val memoId: UUID)

	private fun registerGoogle(token: String = "fake-google-token", sub: String = "fake-google-sub-1", name: String? = "홍길동") =
		registerSocial(SocialProvider.GOOGLE, token, sub, name)

	private fun signUpMember(provider: String = "GOOGLE", token: String = "fake-google-token"): Member {
		val tokens = socialTokens(provider, token)
		val spaceId = jdbc.queryForObject(
			"select space_id from space_members where user_id = ? and left_at is null",
			UUID::class.java,
			tokens.userId,
		)!!
		return Member(tokens, spaceId, insertMemo(spaceId, tokens.userId, "회원의 메모"))
	}

	/** 게스트: 개인 공간에 메모·일정이 있고 기기를 등록한 상태 */
	private class Guest(val tokens: Tokens, val spaceId: UUID)

	private fun startGuestWithRecords(): Guest {
		val tokens = startGuest()
		val spaceId = jdbc.queryForObject("select space_id from space_members where user_id = ?", UUID::class.java, tokens.userId)!!
		insertMemo(spaceId, tokens.userId, "게스트의 메모")
		insertEvent(spaceId, tokens.userId, "게스트의 일정")
		putDevice(tokens.accessToken, """{"fcmToken": "fake-fcm-token-guest"}""").andExpect(status().isNoContent)
		return Guest(tokens, spaceId)
	}

	// ── FR-AUTH-03 AC3 ──────────────────────────────────

	@Test
	fun `FR-AUTH-03 AC3 switch는 게스트 계정과 기록을 지우고 기존 회원으로 로그인한다`() {
		registerGoogle()
		val member = signUpMember()
		val guest = startGuestWithRecords()
		clock.advance(Duration.ofMinutes(5))

		val result = switchAccount(guest.tokens.accessToken, "GOOGLE", "fake-google-token")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.isNewUser").value(false))
			.andExpect(jsonPath("$.accessToken").isNotEmpty)
			.andExpect(jsonPath("$.refreshToken").isNotEmpty)
			.andReturn()

		// 그 회원으로 로그인한다. 새 사용자·새 공간은 만들지 않는다
		val tokens = tokensOf(result.response.getContentAsString(Charsets.UTF_8))
		assertEquals(member.tokens.userId, tokens.userId)
		assertEquals(2, count("select count(*) from users"))
		assertEquals(
			clock.instant().plus(Duration.ofDays(30)).toString(),
			tokens.refreshTokenExpiresAt,
		)
		getMe(tokens.accessToken)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.id").value(member.tokens.userId.toString()))
			.andExpect(jsonPath("$.nickname").value("홍길동"))
			.andExpect(jsonPath("$.space.id").value(member.spaceId.toString()))

		// 게스트는 탈퇴처럼 익명화된다 (행은 남고 로그인 수단·기록은 없다)
		assertEquals(
			1,
			count(
				"select count(*) from users where id = ? and deleted_at = ? and nickname = '탈퇴한 사용자' and profile_image_key is null",
				guest.tokens.userId, ts(clock.instant()),
			),
		)
		assertEquals(0, count("select count(*) from spaces where id = ?", guest.spaceId))
		assertEquals(0, count("select count(*) from space_members where user_id = ?", guest.tokens.userId))
		assertEquals(0, count("select count(*) from memos where author_id = ?", guest.tokens.userId))
		assertEquals(0, count("select count(*) from events where author_id = ?", guest.tokens.userId))
		assertEquals(0, count("select count(*) from notification_settings where user_id = ?", guest.tokens.userId))
		assertEquals(0, count("select count(*) from auth_sessions where user_id = ?", guest.tokens.userId))
		assertEquals(0, count("select count(*) from devices where user_id = ?", guest.tokens.userId))
		// 회원의 공간과 기록은 그대로다 (데이터를 합치지 않는다)
		assertEquals(1, count("select count(*) from spaces where id = ?", member.spaceId))
		assertEquals(1, count("select count(*) from memos where id = ?", member.memoId))
	}

	@Test
	fun `FR-AUTH-03 AC3 switch 뒤 이전 게스트 access 토큰은 401 SESSION_INVALID`() {
		registerGoogle()
		signUpMember()
		val guest = startGuestWithRecords()

		switchAccount(guest.tokens.accessToken, "GOOGLE", "fake-google-token").andExpect(status().isOk)

		getMe(guest.tokens.accessToken).expectError(401, "SESSION_INVALID")
		refresh(guest.tokens.refreshToken).expectError(401, "SESSION_INVALID")
		// 같은 게스트 토큰으로 switch를 다시 부를 수도 없다
		switchAccount(guest.tokens.accessToken, "GOOGLE", "fake-google-token").expectError(401, "SESSION_INVALID")
	}

	@Test
	fun `FR-AUTH-03 AC3 switch는 그 회원의 다른 기기 세션을 REPLACED로 끊고 devices를 지운다`() {
		registerGoogle()
		val member = signUpMember()
		putDevice(member.tokens.accessToken, """{"fcmToken": "fake-fcm-token-member"}""").andExpect(status().isNoContent)
		val guest = startGuestWithRecords()
		assertEquals(2, count("select count(*) from devices"))
		clock.advance(Duration.ofMinutes(5))

		val result = switchAccount(guest.tokens.accessToken, "GOOGLE", "fake-google-token").andExpect(status().isOk).andReturn()

		// 회원의 이전 기기는 다음 요청에서 SESSION_REPLACED를 받는다
		getMe(member.tokens.accessToken).expectError(401, "SESSION_REPLACED")
		refresh(member.tokens.refreshToken).expectError(401, "SESSION_REPLACED")
		assertEquals(
			1,
			count(
				"select count(*) from auth_sessions where id = ? and revoked_reason = 'REPLACED' and revoked_at = ?",
				member.tokens.sessionId, ts(clock.instant()),
			),
		)
		// 기기는 게스트의 것도 회원의 것도 남지 않는다. 새 기기가 PUT /me/devices로 다시 등록한다
		assertEquals(0, count("select count(*) from devices"))
		// 유효한 세션은 새 세션 하나뿐이다
		assertEquals(
			1,
			count("select count(*) from auth_sessions where user_id = ? and revoked_at is null", member.tokens.userId),
		)
		val tokens = tokensOf(result.response.getContentAsString(Charsets.UTF_8))
		assertNotEquals(member.tokens.sessionId, tokens.sessionId)
		putDevice(tokens.accessToken, """{"fcmToken": "fake-fcm-token-new"}""").andExpect(status().isNoContent)
	}

	@Test
	fun `FR-AUTH-03 AC3 그 소셜 계정으로 가입한 회원이 없으면 404 SOCIAL_NOT_REGISTERED이고 게스트는 그대로다`() {
		registerGoogle()
		val guest = startGuestWithRecords()
		val before = accountSnapshot()
		val memoCount = count("select count(*) from memos")

		switchAccount(guest.tokens.accessToken, "GOOGLE", "fake-google-token").expectError(404, "SOCIAL_NOT_REGISTERED")

		assertEquals(before, accountSnapshot())
		assertEquals(memoCount, count("select count(*) from memos"))
		// 게스트는 그대로 쓸 수 있고, 앱은 이어서 /auth/link로 연동하면 된다
		getMe(guest.tokens.accessToken).andExpect(status().isOk).andExpect(jsonPath("$.accountType").value("GUEST"))
		link(guest.tokens.accessToken, "GOOGLE", "fake-google-token").andExpect(status().isNoContent)
	}

	@Test
	fun `FR-AUTH-03 AC3 대상 회원이 그 사이 탈퇴했으면 롤백하고 404 SOCIAL_NOT_REGISTERED`() {
		registerGoogle()
		val member = signUpMember()
		val guest = startGuestWithRecords()
		// 소셜 계정 조회 뒤 트랜잭션이 대상 행을 잠그기 전에 그 회원이 탈퇴해 버린 상태를 만든다
		// (조회에는 소셜 계정 행이 보이지만 잠근 사용자 행은 이미 탈퇴함)
		jdbc.update("update users set deleted_at = ?, nickname = '탈퇴한 사용자' where id = ?", ts(clock.instant()), member.tokens.userId)
		val before = accountSnapshot()

		switchAccount(guest.tokens.accessToken, "GOOGLE", "fake-google-token").expectError(404, "SOCIAL_NOT_REGISTERED")

		// 게스트는 지워지지 않고 그대로다
		assertEquals(before, accountSnapshot())
		assertEquals(1, count("select count(*) from spaces where id = ?", guest.spaceId))
		assertEquals(1, count("select count(*) from memos where author_id = ?", guest.tokens.userId))
		getMe(guest.tokens.accessToken).andExpect(status().isOk)
	}

	@Test
	fun `FR-AUTH-03 AC3 switch 도중 실패하면 게스트 계정이 그대로 남는다`() {
		registerGoogle()
		val member = signUpMember()
		val guest = startGuestWithRecords()
		val before = accountSnapshot()

		// 마지막 단계(새 세션 만들기)에서 DB가 실패한다. 그 앞의 게스트 삭제·이전 세션 정리까지 모두 되돌아가야 한다
		withFailingTrigger("auth_sessions", "insert") {
			switchAccount(guest.tokens.accessToken, "GOOGLE", "fake-google-token").expectError(500, "INTERNAL_ERROR")
		}

		assertEquals(before, accountSnapshot())
		assertEquals(1, count("select count(*) from spaces where id = ?", guest.spaceId))
		assertEquals(1, count("select count(*) from memos where author_id = ?", guest.tokens.userId))
		assertEquals(1, count("select count(*) from devices where user_id = ?", guest.tokens.userId))
		// 게스트도 대상 회원도 세션이 살아 있다
		getMe(guest.tokens.accessToken).andExpect(status().isOk).andExpect(jsonPath("$.accountType").value("GUEST"))
		getMe(member.tokens.accessToken).andExpect(status().isOk)
	}

	@Test
	fun `FR-AUTH-03 AC3 switch는 닉네임이 필요 없으므로 Kakao 프로필 이름을 받지 않는다`() {
		registerSocial(SocialProvider.KAKAO, "fake-kakao-token", "9001", "카카오닉")
		signUpMember("KAKAO", "fake-kakao-token")
		val lookupsAfterSignUp = fakeSocial.nameLookups.get()
		val guest = startGuestWithRecords()

		switchAccount(guest.tokens.accessToken, "KAKAO", "fake-kakao-token").andExpect(status().isOk)

		assertEquals(lookupsAfterSignUp, fakeSocial.nameLookups.get())
	}

	@Test
	fun `FR-AUTH-06 AC3 switch로 지운 게스트는 Kakao 연결 끊기를 부르지 않는다`() {
		registerSocial(SocialProvider.KAKAO, "fake-kakao-token", "9001", "카카오닉")
		signUpMember("KAKAO", "fake-kakao-token")
		val guest = startGuestWithRecords()

		switchAccount(guest.tokens.accessToken, "KAKAO", "fake-kakao-token").andExpect(status().isOk)

		// 게스트에게는 소셜 계정이 없다. 대상 회원의 Kakao 연결도 그대로 둔다
		assertEquals(emptyList<String>(), fakeKakao.calls.toList())
		assertEquals(1, count("select count(*) from social_accounts where provider = 'KAKAO' and provider_user_id = '9001'"))
	}

	// ── R-14 ────────────────────────────────────────────

	@Test
	fun `R-14 게스트는 auth 아래 switch를 부를 수 있다`() {
		registerGoogle()
		signUpMember()
		val guest = startGuest()

		switchAccount(guest.accessToken, "GOOGLE", "fake-google-token").andExpect(status().isOk)
	}

	@Test
	fun `R-14 회원이 auth 아래 switch를 부르면 403 GUEST_ONLY이고 아무것도 바뀌지 않는다`() {
		registerGoogle()
		registerSocial(SocialProvider.GOOGLE, "fake-google-token-2", "fake-google-sub-2", "김철수")
		signUpMember()
		val other = socialTokens("GOOGLE", "fake-google-token-2")
		putDevice(other.accessToken, """{"fcmToken": "fake-fcm-token-other"}""").andExpect(status().isNoContent)
		val before = accountSnapshot()
		val verifyCallsBefore = fakeSocial.verifyCalls.get()

		switchAccount(other.accessToken, "GOOGLE", "fake-google-token").expectError(403, "GUEST_ONLY")

		assertEquals(before, accountSnapshot())
		// R-14는 소셜 토큰 검증보다 먼저다: 검증기를 부르지 않았고, 틀린 토큰이어도 401이 아니라 403이다
		assertEquals(verifyCallsBefore, fakeSocial.verifyCalls.get())
		switchAccount(other.accessToken, "GOOGLE", "unknown-token").expectError(403, "GUEST_ONLY")
		assertEquals(verifyCallsBefore, fakeSocial.verifyCalls.get())
		getMe(other.accessToken).andExpect(status().isOk)
	}

	@Test
	fun `R-14 연동한 직후 같은 access 토큰으로 auth 아래 switch를 부르면 403 GUEST_ONLY`() {
		registerGoogle()
		registerSocial(SocialProvider.GOOGLE, "fake-google-token-2", "fake-google-sub-2", "김철수")
		signUpMember()
		val guest = startGuest()
		link(guest.accessToken, "GOOGLE", "fake-google-token-2").andExpect(status().isNoContent)
		val before = accountSnapshot()

		switchAccount(guest.accessToken, "GOOGLE", "fake-google-token").expectError(403, "GUEST_ONLY")

		assertEquals(before, accountSnapshot())
	}

	@Test
	fun `R-14 auth 아래 switch는 access 토큰이 없으면 401 SESSION_INVALID다`() {
		registerGoogle()
		signUpMember()

		switchAccount("not-a-token", "GOOGLE", "fake-google-token").expectError(401, "SESSION_INVALID")
	}
}
