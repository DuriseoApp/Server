package com.duriseo.server.auth

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.IntegrationTestSupport
import com.duriseo.server.support.LogCapture
import com.duriseo.server.support.TestClockConfig
import com.duriseo.server.support.Tokens
import com.duriseo.server.user.application.UserWithdrawn
import com.duriseo.server.user.domain.SocialProvider
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val KAKAO_ID = "kakao-fake-id-1"

/**
 * Kakao 연결 해제 알림 웹훅 `GET`·`POST /webhooks/kakao/unlink` (FR-AUTH-06.AC4, API.md 3.6·4.8, ERD DB-15, PRD D-87).
 * 인증은 access 토큰이 아니라 `Authorization: KakaoAK <어드민 키>`와 `app_id`다. 인증 실패만 401이고 그 밖은 모두 200 빈 본문이다.
 * 테스트의 어드민 키와 앱 ID는 `src/test/resources/application.properties`의 가짜 값이다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
@RecordApplicationEvents
class KakaoWebhookTest : IntegrationTestSupport() {

	@Autowired
	private lateinit var events: ApplicationEvents

	private val validAuthorization get() = "KakaoAK ${properties.kakao.adminKey}"

	private val validAppId get() = properties.kakao.appId

	/** 웹훅 요청. 값이 null인 헤더·파라미터는 보내지 않는다. 기본값은 인증을 통과하는 Kakao 요청이다 */
	private fun webhook(
		method: String = "POST",
		authorization: String? = validAuthorization,
		appId: String? = validAppId,
		kakaoUserId: String? = KAKAO_ID,
		referrerType: String? = "UNLINK_FROM_APPS",
	): ResultActions {
		val builder = if (method == "GET") get("$API/webhooks/kakao/unlink") else post("$API/webhooks/kakao/unlink")
		if (method == "POST") builder.contentType(MediaType.APPLICATION_FORM_URLENCODED)
		if (authorization != null) builder.header("Authorization", authorization)
		if (appId != null) builder.param("app_id", appId)
		if (kakaoUserId != null) builder.param("user_id", kakaoUserId)
		if (referrerType != null) builder.param("referrer_type", referrerType)
		return mockMvc.perform(builder)
	}

	private fun ResultActions.expectEmptyOk(): ResultActions = andExpect(status().isOk).andExpect(content().string(""))

	/** Kakao로만 가입한 회원 (닉네임 "카카오닉", `provider_user_id` [id]) */
	private fun kakaoMember(id: String = KAKAO_ID, token: String = "fake-kakao-token-$id"): Tokens {
		registerSocial(SocialProvider.KAKAO, token, id, "카카오닉")
		return socialTokens("KAKAO", token)
	}

	private fun withdrawnUsers(): Int = count("select count(*) from users where deleted_at is not null and nickname = '탈퇴한 사용자'")

	// ── FR-AUTH-06 AC4: 처리 ──────────────────────────────

	@Test
	fun `FR-AUTH-06 AC4 Kakao만 쓰는 회원에게 연결 해제 알림이 오면 탈퇴 처리하고 200 빈 본문을 돌려준다`() {
		val member = kakaoMember()
		val spaceId = activeSpaceIdOfUser(member.userId)
		insertMemo(spaceId, member.userId)
		putDevice(member.accessToken, """{"fcmToken": "fake-fcm-token-1"}""").andExpect(status().isNoContent)

		webhook().expectEmptyOk()

		assertEquals(1, withdrawnUsers())
		assertEquals(0, count("select count(*) from social_accounts"))
		assertEquals(0, count("select count(*) from auth_sessions"))
		assertEquals(0, count("select count(*) from devices"))
		assertEquals(0, count("select count(*) from spaces"))
		assertEquals(0, count("select count(*) from memos"))
		// 탈퇴된 사용자의 기기는 다음 요청에서 401 SESSION_INVALID를 받는다
		getMe(member.accessToken).expectError(401, "SESSION_INVALID")
		// Kakao가 이미 끊었으므로 연결 끊기를 부르지 않는다
		assertEquals(emptyList<String>(), fakeKakao.calls.toList())
		val withdrawn = events.stream(UserWithdrawn::class.java).toList().single()
		assertEquals(member.userId, withdrawn.userId)
		assertNull(withdrawn.kakaoProviderUserId)
	}

	@Test
	fun `FR-AUTH-06 AC4 GET으로 와도 같게 처리한다 (쿼리 문자열)`() {
		kakaoMember()

		webhook(method = "GET").expectEmptyOk()

		assertEquals(1, withdrawnUsers())
		assertEquals(0, count("select count(*) from social_accounts"))
		assertEquals(emptyList<String>(), fakeKakao.calls.toList())
	}

	@Test
	fun `FR-AUTH-06 AC4 access 토큰 없이 어드민 키만으로 부를 수 있다`() {
		kakaoMember()

		// webhook()은 Bearer 헤더를 보내지 않는다. Authorization은 KakaoAK 값 하나뿐이다
		webhook().andExpect(status().isOk)

		assertEquals(1, withdrawnUsers())
	}

	@Test
	fun `FR-AUTH-06 AC4 Google도 쓰는 회원이면 KAKAO 행만 지우고 세션·기기·데이터는 그대로다`() {
		val member = kakaoMember()
		jdbc.update(
			"insert into social_accounts (id, user_id, provider, provider_user_id, created_at) values (gen_random_uuid(), ?, 'GOOGLE', 'fake-google-sub-9', ?)",
			member.userId, ts(clock.instant()),
		)
		val spaceId = activeSpaceIdOfUser(member.userId)
		val memoId = insertMemo(spaceId, member.userId)
		putDevice(member.accessToken, """{"fcmToken": "fake-fcm-token-1"}""").andExpect(status().isNoContent)

		webhook().expectEmptyOk()

		assertEquals(0, count("select count(*) from social_accounts where provider = 'KAKAO'"))
		assertEquals(1, count("select count(*) from social_accounts where provider = 'GOOGLE' and user_id = ?", member.userId))
		assertEquals(0, withdrawnUsers())
		assertEquals(1, count("select count(*) from users where id = ? and deleted_at is null and nickname = '카카오닉'", member.userId))
		assertEquals(1, count("select count(*) from auth_sessions where user_id = ? and revoked_at is null", member.userId))
		assertEquals(1, count("select count(*) from devices where user_id = ?", member.userId))
		assertEquals(1, count("select count(*) from memos where id = ?", memoId))
		// 같은 access 토큰을 계속 쓰고 Me.providers에서 KAKAO가 빠진다
		getMe(member.accessToken).andExpect(status().isOk).andExpect(jsonPath("$.providers[0]").value("GOOGLE")).andExpect(jsonPath("$.providers.length()").value(1))
		assertEquals(emptyList<String>(), fakeKakao.calls.toList())
		assertTrue(events.stream(UserWithdrawn::class.java).toList().isEmpty())
	}

	@Test
	fun `FR-AUTH-06 AC4 커플 회원이면 연결 해제와 상대의 탈퇴 전용 COUPLE_UNLINKED 알림과 purge_at +90일까지 탈퇴와 같다`() {
		val member = kakaoMember()
		val partner = startMember("상대방")
		val spaceId = connect(member, partner)
		clock.advance(Duration.ofMinutes(10))

		webhook().expectEmptyOk()

		assertEquals(1, withdrawnUsers())
		assertEquals(1, count("select count(*) from spaces where id = ? and status = 'UNLINKED'", spaceId))
		assertEquals(clock.instant().plus(Duration.ofDays(90)), instantOf("select purge_at from spaces where id = ?", spaceId))
		val notification = jdbc.queryForList("select * from notifications where user_id = ? and type = 'COUPLE_UNLINKED'", partner.userId).single()
		assertEquals("카카오닉님이 탈퇴해 연결이 해제됐어요", notification["title"])
		assertEquals("함께 쓴 기록은 더 이상 볼 수 없어요", notification["body"])
		assertNull(notification["space_id"])
		assertEquals("SETTINGS", notification["target_type"])
		getMe(partner.accessToken).andExpect(jsonPath("$.space.type").value("PERSONAL"))
		assertEquals(partner.userId, events.stream(UserWithdrawn::class.java).toList().single().partnerUserId)
		assertTrue(coupleEvents.unlinked.single().byWithdrawal)
		assertEquals(emptyList<String>(), fakeKakao.calls.toList())
	}

	@Test
	fun `FR-AUTH-06 AC4 referrer_type 값과 관계없이 모두 같게 처리한다`() {
		val types = listOf("ACCOUNT_DELETE", "FORCED_ACCOUNT_DELETE", "UNLINK_FROM_ADMIN", "UNLINK_FROM_APPS", "INCOMPLETE_SIGN_UP")
		types.forEachIndexed { index, type ->
			kakaoMember(id = "kakao-fake-id-$index")

			webhook(kakaoUserId = "kakao-fake-id-$index", referrerType = type).expectEmptyOk()
		}

		assertEquals(types.size, withdrawnUsers())
		assertEquals(0, count("select count(*) from social_accounts"))
	}

	@Test
	fun `FR-AUTH-06 AC4 모르는 사용자와 이미 처리한 알림은 200이고 아무것도 바꾸지 않는다`() {
		kakaoMember()
		// 모르는 Kakao 회원번호
		val beforeUnknown = accountSnapshot()
		webhook(kakaoUserId = "kakao-unknown-id").expectEmptyOk()
		assertEquals(beforeUnknown, accountSnapshot())

		// 같은 알림을 두 번 받아도 결과가 같다
		webhook().expectEmptyOk()
		val afterFirst = accountSnapshot()
		webhook().expectEmptyOk()
		webhook(method = "GET").expectEmptyOk()

		assertEquals(afterFirst, accountSnapshot())
		assertEquals(1, withdrawnUsers())
	}

	@Test
	fun `FR-AUTH-06 AC4 같은 알림이 동시에 두 번 와도 한 번만 처리하고 둘 다 200이다`() {
		val member = kakaoMember()
		val partner = startMember("상대방")
		connect(member, partner)

		val statuses = inParallel(listOf({ statusOf(webhook()) }, { statusOf(webhook(method = "GET")) }))

		assertEquals(listOf(200, 200), statuses)
		assertEquals(1, withdrawnUsers())
		assertEquals(1, count("select count(*) from notifications where type = 'COUPLE_UNLINKED'"))
	}

	@Test
	fun `FR-AUTH-06 AC4 user_id가 없거나 비어 있으면 200이고 아무것도 바꾸지 않고 경고 로그만 남긴다`() {
		kakaoMember()
		val before = accountSnapshot()

		LogCapture().use { logs ->
			webhook(kakaoUserId = null).expectEmptyOk()
			webhook(kakaoUserId = "").expectEmptyOk()
			webhook(kakaoUserId = "   ").expectEmptyOk()

			assertTrue(logs.text().contains("without user_id"), "경고 로그가 없어요")
		}

		assertEquals(before, accountSnapshot())
		assertEquals(0, withdrawnUsers())
	}

	// ── 인증 (NFR-SEC-01) ─────────────────────────────────

	@Test
	fun `FR-AUTH-06 AC4 어드민 키나 app_id가 틀리면 401 WEBHOOK_UNAUTHORIZED이고 아무것도 바꾸지 않는다`() {
		kakaoMember()
		val before = accountSnapshot()
		val key = properties.kakao.adminKey
		val wrongKey = "wrong-admin-key-for-tests"

		LogCapture().use { logs ->
			val rejected = listOf(
				// 헤더 없음, 접두어가 다름, 키가 다름, 대소문자가 다름, 키만 있음
				webhook(authorization = null),
				webhook(authorization = "KakaoAK $wrongKey"),
				webhook(authorization = "Bearer $key"),
				webhook(authorization = "kakaoak $key"),
				webhook(authorization = key),
				webhook(authorization = "KakaoAK "),
				// app_id가 다르거나 없음
				webhook(appId = "111111"),
				webhook(appId = null),
				webhook(appId = ""),
				// 둘 다 틀림, GET
				webhook(authorization = "KakaoAK $wrongKey", appId = "111111"),
				webhook(method = "GET", authorization = "KakaoAK $wrongKey"),
				webhook(method = "GET", appId = "111111"),
			)
			rejected.forEach { result ->
				result.expectError(401, "WEBHOOK_UNAUTHORIZED").andExpect(jsonPath("$.traceId").isNotEmpty())
			}

			// 키와 헤더 값은 로그에 남지 않는다
			val text = logs.text()
			assertFalse(text.contains(key), "어드민 키가 로그에 있어요")
			assertFalse(text.contains(wrongKey), "헤더 값이 로그에 있어요")
		}

		assertEquals(before, accountSnapshot())
		assertEquals(0, withdrawnUsers())
	}

	@Test
	fun `FR-AUTH-06 AC4 인증이 틀리면 user_id가 맞아도 처리하지 않는다 (Google도 쓰는 회원 포함)`() {
		val member = kakaoMember()
		jdbc.update(
			"insert into social_accounts (id, user_id, provider, provider_user_id, created_at) values (gen_random_uuid(), ?, 'GOOGLE', 'fake-google-sub-9', ?)",
			member.userId, ts(clock.instant()),
		)
		val before = accountSnapshot()

		webhook(authorization = "KakaoAK wrong-admin-key-for-tests").expectError(401, "WEBHOOK_UNAUTHORIZED")

		assertEquals(before, accountSnapshot())
		assertEquals(1, count("select count(*) from social_accounts where provider = 'KAKAO'"))
	}

	// ── 서버 오류 (API.md 4.8 7단계) ──────────────────────

	@Test
	fun `FR-AUTH-06 AC4 처리 중 오류가 나면 롤백하고 ERROR 로그에 user_id·referrer_type·traceId를 남기고도 200이다`() {
		val member = kakaoMember()
		val partner = startMember("상대방")
		connect(member, partner)
		val before = accountSnapshot() + coupleSnapshot()

		LogCapture().use { logs ->
			// 소셜 계정 행을 지우는 단계에서 DB가 실패한다. 앞 단계(연결 해제 등)까지 모두 되돌아가야 한다
			withFailingTrigger("social_accounts", "delete") {
				webhook(referrerType = "ACCOUNT_DELETE").expectEmptyOk()
			}

			val text = logs.text()
			assertTrue(text.contains("Kakao unlink notice was not processed"), "ERROR 로그가 없어요")
			assertTrue(text.contains("user_id=$KAKAO_ID"), "Kakao user_id가 로그에 없어요")
			assertTrue(text.contains("referrer_type=ACCOUNT_DELETE"), "referrer_type이 로그에 없어요")
			assertTrue(Regex("traceId=[0-9a-f]{32}").containsMatchIn(text), "traceId가 로그에 없어요")
			// 키와 헤더 값은 로그에 쓰지 않는다
			assertFalse(text.contains(properties.kakao.adminKey), "어드민 키가 로그에 있어요")
			assertFalse(text.contains("KakaoAK"), "Authorization 헤더 값이 로그에 있어요")
		}

		assertEquals(before, accountSnapshot() + coupleSnapshot())
		assertEquals(0, withdrawnUsers())
		assertTrue(coupleEvents.unlinked.isEmpty())
		getMe(member.accessToken).andExpect(status().isOk).andExpect(jsonPath("$.space.type").value("COUPLE"))

		// 오류가 사라진 뒤 같은 알림을 다시 받으면 처리된다 (운영자가 다시 처리하는 경우)
		webhook().expectEmptyOk()
		assertEquals(1, withdrawnUsers())
	}

	@Test
	fun `FR-AUTH-06 AC4 user_id 형식이 이상해도 200이다`() {
		kakaoMember()
		val before = accountSnapshot()

		webhook(kakaoUserId = "not-a-number\n\u0000‮").expectEmptyOk()
		webhook(kakaoUserId = "9".repeat(500)).expectEmptyOk()

		assertEquals(before, accountSnapshot())
	}

}
