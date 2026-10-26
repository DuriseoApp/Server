package com.duriseo.server.user

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.auth.application.social.KakaoUnavailableException
import com.duriseo.server.support.IntegrationTestSupport
import com.duriseo.server.support.LogCapture
import com.duriseo.server.support.TestClockConfig
import com.duriseo.server.user.application.UserWithdrawn
import com.duriseo.server.user.domain.SocialProvider
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 탈퇴 `DELETE /me` (API.md 4.6, ERD 4.3, FR-AUTH-06). 개인 공간 사용자(게스트 포함)의 탈퇴와
 * 해제가 필요 없는 데이터 단계(UNLINKED 커플 공간 `purge_at` +90일, 가져오기 대기 공간 삭제)를 확인한다.
 * 커플 공간 사용자의 연결 해제·`COUPLE_UNLINKED` 알림은 단위 2, 푸시·SYNC는 단위 6이다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
@RecordApplicationEvents
class WithdrawalTest : IntegrationTestSupport() {

	@Autowired
	private lateinit var events: ApplicationEvents

	private fun registerGoogle() = registerSocial(SocialProvider.GOOGLE, "fake-google-token", "fake-google-sub-1", "홍길동")

	private fun registerKakao(token: String = "fake-kakao-token", id: String = "kakao-fake-id-1") =
		registerSocial(SocialProvider.KAKAO, token, id, "카카오닉")

	private fun personalSpaceOf(userId: UUID): UUID =
		jdbc.queryForObject("select space_id from space_members where user_id = ? and left_at is null", UUID::class.java, userId)!!

	private fun withdrawnUserRow(userId: UUID) = count(
		"select count(*) from users where id = ? and deleted_at = ? and nickname = '탈퇴한 사용자' and profile_image_key is null",
		userId, ts(clock.instant()),
	)

	/** 스프링 트랜잭션과 무관한 새 연결로 센다. 다른 연결에서 보이는 값은 커밋된 값뿐이다 */
	private fun committedCount(sql: String, vararg args: Any): Int =
		jdbc.dataSource!!.connection.use { connection ->
			connection.prepareStatement(sql).use { statement ->
				args.forEachIndexed { index, arg -> statement.setObject(index + 1, arg) }
				statement.executeQuery().use { rows ->
					rows.next()
					rows.getInt(1)
				}
			}
		}

	// ── FR-AUTH-06 AC1 ──────────────────────────────────

	@Test
	fun `FR-AUTH-06 AC1 개인 공간 회원이 탈퇴하면 개인 공간과 콘텐츠가 지워지고 사용자는 익명화된다`() {
		registerGoogle()
		val member = socialTokens("GOOGLE", "fake-google-token")
		val spaceId = personalSpaceOf(member.userId)
		insertMemo(spaceId, member.userId)
		insertEvent(spaceId, member.userId)
		jdbc.update("update users set profile_image_key = 'fake/profile.jpg' where id = ?", member.userId)
		clock.advance(Duration.ofMinutes(5))

		deleteMe(member.accessToken).andExpect(status().isNoContent).andExpect(content().string(""))

		// 사용자 행은 남기고 익명화한다
		assertEquals(1, count("select count(*) from users"))
		assertEquals(1, withdrawnUserRow(member.userId))
		// 개인 공간과 콘텐츠는 즉시 지워진다
		assertEquals(0, count("select count(*) from spaces"))
		assertEquals(0, count("select count(*) from space_members"))
		assertEquals(0, count("select count(*) from memos"))
		assertEquals(0, count("select count(*) from events"))
	}

	@Test
	fun `FR-AUTH-06 AC1 게스트도 탈퇴할 수 있다`() {
		val guest = startGuest()
		val spaceId = personalSpaceOf(guest.userId)
		insertMemo(spaceId, guest.userId)
		putDevice(guest.accessToken, """{"fcmToken": "fake-fcm-token-1"}""").andExpect(status().isNoContent)

		deleteMe(guest.accessToken).andExpect(status().isNoContent)

		assertEquals(1, withdrawnUserRow(guest.userId))
		assertEquals(0, count("select count(*) from spaces"))
		assertEquals(0, count("select count(*) from memos"))
		assertEquals(0, count("select count(*) from auth_sessions"))
		assertEquals(0, count("select count(*) from devices"))
		assertEquals(0, count("select count(*) from notification_settings"))
		// 게스트에게는 Kakao 연결이 없다
		assertEquals(emptyList<String>(), fakeKakao.calls.toList())
	}

	@Test
	fun `FR-AUTH-06 AC1 탈퇴 뒤 social_accounts·auth_sessions·devices·notification_settings·notifications·memo_favorites·event_reminders·유효한 invite_codes가 남지 않는다`() {
		registerGoogle()
		val member = socialTokens("GOOGLE", "fake-google-token")
		putDevice(member.accessToken, """{"fcmToken": "fake-fcm-token-1"}""").andExpect(status().isNoContent)
		val personalSpace = personalSpaceOf(member.userId)

		// 상대(다른 사용자)와 함께 쓰던 해제된 커플 공간: 이 공간의 콘텐츠는 보관되지만 내 즐겨찾기·일정 알림은 지워진다
		val partner = insertUser("MEMBER", "상대방")
		jdbc.update("insert into notification_settings (user_id) values (?)", partner)
		val coupleSpace = insertSpace("COUPLE", unlinked = true)
		insertMember(coupleSpace, member.userId, "pick-orange", left = true)
		insertMember(coupleSpace, partner, "pick-blue", left = true)
		val partnerMemo = insertMemo(coupleSpace, partner, "상대의 메모")
		val partnerEvent = insertEvent(coupleSpace, partner, "상대의 일정")
		insertMemoFavorite(partnerMemo, member.userId)
		insertEventReminder(partnerEvent, member.userId)
		insertNotification(member.userId, personalSpace)
		insertNotification(member.userId, null)
		insertNotification(partner, null)
		insertInviteCode(member.userId, "AAAAAA")
		insertInviteCode(member.userId, "BBBBBB", used = true)
		insertInviteCode(partner, "CCCCCC")

		deleteMe(member.accessToken).andExpect(status().isNoContent)

		assertEquals(0, count("select count(*) from social_accounts"))
		assertEquals(0, count("select count(*) from auth_sessions where user_id = ?", member.userId))
		assertEquals(0, count("select count(*) from devices"))
		assertEquals(0, count("select count(*) from notification_settings where user_id = ?", member.userId))
		assertEquals(0, count("select count(*) from notifications where user_id = ?", member.userId))
		assertEquals(0, count("select count(*) from memo_favorites where user_id = ?", member.userId))
		assertEquals(0, count("select count(*) from event_reminders where user_id = ?", member.userId))
		assertEquals(0, count("select count(*) from invite_codes where creator_user_id = ? and used_at is null and revoked_at is null", member.userId))
		// 이미 사용된 코드는 유효한 코드가 아니라 그대로 두고, 다른 사용자의 행은 건드리지 않는다
		assertEquals(1, count("select count(*) from invite_codes where creator_user_id = ?", member.userId))
		assertEquals(1, count("select count(*) from invite_codes where creator_user_id = ?", partner))
		assertEquals(1, count("select count(*) from notification_settings where user_id = ?", partner))
		assertEquals(1, count("select count(*) from notifications where user_id = ?", partner))
		// 보관 중인 커플 공간의 콘텐츠와 작성자 참조(users 행)는 남는다
		assertEquals(1, count("select count(*) from memos where id = ?", partnerMemo))
		assertEquals(1, count("select count(*) from events where id = ?", partnerEvent))
		assertEquals(1, count("select count(*) from users where id = ?", member.userId))
	}

	@Test
	fun `FR-AUTH-06 AC1 탈퇴할 때 세션은 WITHDRAWN으로 무효화한 뒤 지운다`() {
		registerGoogle()
		val member = socialTokens("GOOGLE", "fake-google-token")
		// 세션 행이 지워지는 순간의 revoked_reason을 DB 트리거로 기록해 본다
		jdbc.execute("create table test_session_delete_audit (revoked_reason varchar(10), revoked_at timestamptz)")
		jdbc.execute(
			"create function test_audit_session_delete() returns trigger language plpgsql as \$\$ " +
				"begin insert into test_session_delete_audit values (old.revoked_reason, old.revoked_at); return old; end; \$\$",
		)
		jdbc.execute(
			"create trigger test_audit_session_delete before delete on auth_sessions " +
				"for each row execute function test_audit_session_delete()",
		)
		try {
			clock.advance(Duration.ofMinutes(5))

			deleteMe(member.accessToken).andExpect(status().isNoContent)

			assertEquals(0, count("select count(*) from auth_sessions"))
			assertEquals(1, count("select count(*) from test_session_delete_audit"))
			assertEquals(
				1,
				count("select count(*) from test_session_delete_audit where revoked_reason = 'WITHDRAWN' and revoked_at = ?", ts(clock.instant())),
			)
		} finally {
			jdbc.execute("drop trigger if exists test_audit_session_delete on auth_sessions")
			jdbc.execute("drop function if exists test_audit_session_delete()")
			jdbc.execute("drop table if exists test_session_delete_audit")
		}
	}

	@Test
	fun `FR-AUTH-06 AC1 탈퇴 뒤 access 토큰은 401 SESSION_INVALID`() {
		registerGoogle()
		val member = socialTokens("GOOGLE", "fake-google-token")

		deleteMe(member.accessToken).andExpect(status().isNoContent)

		getMe(member.accessToken).expectError(401, "SESSION_INVALID")
		deleteMe(member.accessToken).expectError(401, "SESSION_INVALID")
		refresh(member.refreshToken).expectError(401, "SESSION_INVALID")
	}

	@Test
	fun `FR-AUTH-06 AC1 탈퇴 뒤 같은 소셜 계정으로 로그인하면 새 회원이 된다`() {
		registerGoogle()
		val old = socialTokens("GOOGLE", "fake-google-token")
		deleteMe(old.accessToken).andExpect(status().isNoContent)

		val result = social("GOOGLE", "fake-google-token")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.isNewUser").value(true))
			.andReturn()

		val fresh = tokensOf(result.response.getContentAsString(Charsets.UTF_8))
		assertNotEquals(old.userId, fresh.userId)
		// 이전 사용자 행은 익명화된 채로 남고, 새 회원과 새 개인 공간이 생긴다
		assertEquals(2, count("select count(*) from users"))
		assertEquals(1, count("select count(*) from users where id = ? and deleted_at is not null", old.userId))
		assertEquals(1, count("select count(*) from social_accounts where user_id = ?", fresh.userId))
		assertEquals(1, count("select count(*) from spaces"))
		getMe(fresh.accessToken).andExpect(jsonPath("$.nickname").value("홍길동"))
	}

	@Test
	fun `FR-AUTH-06 AC1 탈퇴하면 가져오기 대기 중인 개인 공간도 지운다`() {
		registerGoogle()
		val member = socialTokens("GOOGLE", "fake-google-token")
		// 커플 연결 때 혼자 쓴 기록이 있어 남겨 둔 개인 공간 (소속에 left_at이 있음)
		val pendingSpace = insertSpace("PERSONAL")
		insertMember(pendingSpace, member.userId, left = true)
		val pendingMemo = insertMemo(pendingSpace, member.userId, "대기 중인 메모")

		deleteMe(member.accessToken).andExpect(status().isNoContent)

		assertEquals(0, count("select count(*) from spaces"))
		assertEquals(0, count("select count(*) from space_members"))
		assertEquals(0, count("select count(*) from memos where id = ?", pendingMemo))
	}

	@Test
	fun `FR-AUTH-06 AC1 탈퇴하면 UserWithdrawn 이벤트에 익명화 전 닉네임과 프로필 사진 키와 Kakao ID를 담는다`() {
		registerKakao()
		val member = socialTokens("KAKAO", "fake-kakao-token")
		jdbc.update("update users set profile_image_key = 'fake/profile.jpg' where id = ?", member.userId)

		deleteMe(member.accessToken).andExpect(status().isNoContent)

		val published = events.stream(UserWithdrawn::class.java).toList()
		assertEquals(1, published.size)
		assertEquals(member.userId, published[0].userId)
		assertEquals("카카오닉", published[0].nicknameBeforeWithdrawal)
		assertEquals("fake/profile.jpg", published[0].profileImageKey)
		assertEquals("kakao-fake-id-1", published[0].kakaoProviderUserId)
		// 커플 해제는 단위 2가 채울 때까지 상대가 없다
		assertNull(published[0].partnerUserId)
	}

	@Test
	fun `FR-AUTH-06 AC1 DELETE me는 access 토큰이 없으면 401 SESSION_INVALID다`() {
		deleteMe("not-a-token").expectError(401, "SESSION_INVALID")
	}

	// ── FR-AUTH-06 AC2: 단위 1이 맡은 부분 ───────────────

	/** 탈퇴하는 사용자와 상대가 속했다가 해제한 커플 공간 */
	private fun unlinkedCoupleSpaceOf(userId: UUID): UUID {
		val partner = insertUser("MEMBER", "상대방")
		val coupleSpace = insertSpace("COUPLE", unlinked = true)
		insertMember(coupleSpace, userId, "pick-orange", left = true)
		insertMember(coupleSpace, partner, "pick-blue", left = true)
		return coupleSpace
	}

	@Test
	fun `FR-AUTH-06 AC2 탈퇴하면 속했던 UNLINKED 커플 공간의 purge_at을 탈퇴 시각 + 90일로 늦춘다`() {
		registerGoogle()
		val member = socialTokens("GOOGLE", "fake-google-token")
		val coupleSpace = unlinkedCoupleSpaceOf(member.userId)
		// 탈퇴와 상관없는 다른 커플의 UNLINKED 공간은 건드리지 않는다
		val strangers = insertSpace("COUPLE", unlinked = true)
		val strangerPurgeAt = instantOf("select purge_at from spaces where id = ?", strangers)
		assertEquals(START.plus(Duration.ofDays(30)), instantOf("select purge_at from spaces where id = ?", coupleSpace))
		clock.advance(Duration.ofDays(3))

		deleteMe(member.accessToken).andExpect(status().isNoContent)

		assertEquals(clock.instant().plus(Duration.ofDays(90)), instantOf("select purge_at from spaces where id = ?", coupleSpace))
		assertEquals(1, count("select count(*) from spaces where id = ? and status = 'UNLINKED' and unlinked_at is not null", coupleSpace))
		assertEquals(strangerPurgeAt, instantOf("select purge_at from spaces where id = ?", strangers))
		// 공간은 보관되고 지워지지 않는다
		assertEquals(1, count("select count(*) from spaces where id = ?", coupleSpace))
	}

	@Test
	fun `FR-AUTH-06 AC2 purge_at이 이미 90일보다 늦으면 그대로 둔다`() {
		registerGoogle()
		val member = socialTokens("GOOGLE", "fake-google-token")
		val coupleSpace = unlinkedCoupleSpaceOf(member.userId)
		val later = START.plus(Duration.ofDays(120))
		jdbc.update("update spaces set purge_at = ? where id = ?", ts(later), coupleSpace)
		clock.advance(Duration.ofDays(3))

		deleteMe(member.accessToken).andExpect(status().isNoContent)

		assertEquals(later, instantOf("select purge_at from spaces where id = ?", coupleSpace))
	}

	@Test
	fun `FR-AUTH-06 AC2 활성 커플 공간 사용자의 탈퇴는 연결 해제(단위 2)가 생기기 전까지 500이고 아무것도 바꾸지 않는다`() {
		registerGoogle()
		val member = socialTokens("GOOGLE", "fake-google-token")
		val partner = insertUser("MEMBER", "상대방")
		// 연결된 상태: 개인 공간 소속은 left_at, 커플 공간 소속이 활성
		jdbc.update("update space_members set left_at = ? where user_id = ?", ts(clock.instant()), member.userId)
		val coupleSpace = insertSpace("COUPLE")
		insertMember(coupleSpace, member.userId, "pick-orange")
		insertMember(coupleSpace, partner, "pick-blue")
		val before = accountSnapshot()

		deleteMe(member.accessToken).expectError(500, "INTERNAL_ERROR")

		assertEquals(before, accountSnapshot())
		getMe(member.accessToken).andExpect(status().isOk).andExpect(jsonPath("$.space.type").value("COUPLE"))
	}

	// ── FR-AUTH-06 AC3: Kakao 연결 끊기 ──────────────────

	@Test
	fun `FR-AUTH-06 AC3 Kakao 회원이 탈퇴하면 커밋 뒤 provider_user_id로 Kakao 연결 끊기를 부른다`() {
		registerKakao()
		val member = socialTokens("KAKAO", "fake-kakao-token")
		// 연결 끊기를 부르는 순간 요청과 별개의 새 DB 연결에서 탈퇴 결과가 보이면 이미 커밋된 뒤다
		val seenAtCall = mutableListOf<String>()
		fakeKakao.onCall = {
			val withdrawn = committedCount("select count(*) from users where id = ? and deleted_at is not null", member.userId)
			val socialRows = committedCount("select count(*) from social_accounts")
			seenAtCall.add("withdrawn=$withdrawn socialRows=$socialRows")
		}

		deleteMe(member.accessToken).andExpect(status().isNoContent)

		assertEquals(listOf("kakao-fake-id-1"), fakeKakao.calls.toList())
		assertEquals(listOf("withdrawn=1 socialRows=0"), seenAtCall)
	}

	@Test
	fun `FR-AUTH-06 AC3 Kakao 연결 끊기가 실패하거나 시간이 초과돼도 탈퇴는 되돌리지 않는다`() {
		registerKakao(token = "fake-kakao-token-1", id = "kakao-fake-id-1")
		registerKakao(token = "fake-kakao-token-2", id = "kakao-fake-id-2")
		val first = socialTokens("KAKAO", "fake-kakao-token-1")
		val second = socialTokens("KAKAO", "fake-kakao-token-2")

		LogCapture().use { logs ->
			fakeKakao.failure = RuntimeException("simulated failure")
			deleteMe(first.accessToken).andExpect(status().isNoContent)
			fakeKakao.failure = KakaoUnavailableException("simulated timeout")
			deleteMe(second.accessToken).andExpect(status().isNoContent)

			// 실패했다는 사실만 로그에 남고 Kakao 사용자 ID·토큰은 남지 않는다
			val text = logs.text()
			assertTrue(text.contains("Kakao unlink failed"), "실패 로그가 없어요")
			assertFalse(text.contains("kakao-fake-id-1"))
			assertFalse(text.contains("kakao-fake-id-2"))
			assertFalse(text.contains("fake-kakao-token"))
		}

		assertEquals(listOf("kakao-fake-id-1", "kakao-fake-id-2"), fakeKakao.calls.toList())
		assertEquals(1, withdrawnUserRow(first.userId))
		assertEquals(1, withdrawnUserRow(second.userId))
		assertEquals(0, count("select count(*) from social_accounts"))
	}

	@Test
	fun `FR-AUTH-06 AC3 Google 회원과 게스트는 Kakao 연결 끊기를 부르지 않는다`() {
		registerGoogle()
		val google = socialTokens("GOOGLE", "fake-google-token")
		val guest = startGuest()

		deleteMe(google.accessToken).andExpect(status().isNoContent)
		deleteMe(guest.accessToken).andExpect(status().isNoContent)

		assertEquals(emptyList<String>(), fakeKakao.calls.toList())
	}

	@Test
	fun `FR-AUTH-06 AC3 탈퇴 트랜잭션이 실패하면 Kakao 연결 끊기를 부르지 않는다`() {
		registerKakao()
		val member = socialTokens("KAKAO", "fake-kakao-token")
		val before = accountSnapshot()

		// 소셜 계정 행을 지우는 단계에서 DB가 실패한다. 앞 단계(공간 삭제 등)까지 모두 되돌아가야 한다
		withFailingTrigger("social_accounts", "delete") {
			deleteMe(member.accessToken).expectError(500, "INTERNAL_ERROR")
		}

		assertEquals(emptyList<String>(), fakeKakao.calls.toList())
		assertEquals(before, accountSnapshot())
		assertEquals(1, count("select count(*) from spaces"))
		getMe(member.accessToken).andExpect(status().isOk)
	}

	@Test
	fun `FR-AUTH-06 AC3 이미 탈퇴한 Kakao 회원의 두 번째 탈퇴 요청은 401이고 연결 끊기를 다시 부르지 않는다`() {
		registerKakao()
		val member = socialTokens("KAKAO", "fake-kakao-token")
		deleteMe(member.accessToken).andExpect(status().isNoContent)

		deleteMe(member.accessToken).expectError(401, "SESSION_INVALID")

		assertEquals(1, fakeKakao.calls.size)
	}
}
