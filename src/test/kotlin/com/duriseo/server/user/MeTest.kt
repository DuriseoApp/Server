package com.duriseo.server.user

import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.hamcrest.Matchers.containsString
import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.IntegrationTestSupport
import com.duriseo.server.support.TestClockConfig
import com.duriseo.server.user.application.UserTimeZoneChanged
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

/** 내 정보 (FR-SET-01, FR-AUTH-07, NFR-TIME-01, API.md 3.1) */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
@RecordApplicationEvents
class MeTest : IntegrationTestSupport() {

	@Autowired
	private lateinit var events: ApplicationEvents

	// ── GET /me ─────────────────────────────────────────

	@Test
	fun `FR-SET-01 GET me는 내 정보와 개인 공간 요약을 돌려준다`() {
		val tokens = startGuest()
		val spaceId = jdbc.queryForObject("select id from spaces", UUID::class.java)

		getMe(tokens.accessToken)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.id").value(tokens.userId.toString()))
			.andExpect(jsonPath("$.accountType").value("GUEST"))
			.andExpect(jsonPath("$.nickname").value("게스트"))
			.andExpect(jsonPath("$.timeZone").value("Asia/Seoul"))
			.andExpect(jsonPath("$.providers").isEmpty)
			.andExpect(jsonPath("$.hasProfileImage").value(false))
			.andExpect(jsonPath("$.space.id").value(spaceId.toString()))
			.andExpect(jsonPath("$.space.type").value("PERSONAL"))
			.andExpect(jsonPath("$.space.myColor").value("pick-orange"))
			.andExpect(jsonPath("$.space.partner").value(nullValue()))
			.andExpect(jsonPath("$.space.togetherColor").value(nullValue()))
			.andExpect(jsonPath("$.space.startDate").value(nullValue()))
			.andExpect(jsonPath("$.space.importPending").value(false))
	}

	@Test
	fun `FR-SET-01 GET me는 값이 없는 필드를 빼지 않고 null로 보낸다`() {
		val tokens = startGuest()

		getMe(tokens.accessToken)
			// jsonPath(...).exists()는 값이 null이면 "없음"으로 보므로 본문 문자열로 확인한다
			.andExpect(content().string(containsString("\"partner\":null")))
			.andExpect(content().string(containsString("\"togetherColor\":null")))
			.andExpect(content().string(containsString("\"startDate\":null")))
	}

	@Test
	fun `FR-SET-01 회원의 GET me는 연결된 로그인 수단을 providers로 돌려준다`() {
		val tokens = startGuest()
		makeMember(tokens.userId, "KAKAO")
		makeMember(tokens.userId, "GOOGLE")

		getMe(tokens.accessToken)
			.andExpect(jsonPath("$.accountType").value("MEMBER"))
			.andExpect(jsonPath("$.providers[0]").value("GOOGLE"))
			.andExpect(jsonPath("$.providers[1]").value("KAKAO"))
	}

	@Test
	fun `FR-SET-01 프로필 사진이 있으면 hasProfileImage가 true다`() {
		val tokens = startGuest()
		jdbc.update("update users set profile_image_key = 'fake/key.jpg' where id = ?", tokens.userId)

		getMe(tokens.accessToken).andExpect(jsonPath("$.hasProfileImage").value(true))
	}

	@Test
	fun `FR-COUPLE-02 AC5 GET me는 커플 공간이면 COUPLE 종류와 상대·사람 색을 돌려준다`() {
		val tokens = startGuest()
		makeMember(tokens.userId)
		val partnerId = insertUser("MEMBER", "상대방")
		jdbc.update("update users set profile_image_key = 'fake/partner.jpg' where id = ?", partnerId)
		// 혼자 쓴 기록이 없는 개인 공간은 연결할 때 지워진다 (ERD 4.1)
		val personalSpace = jdbc.queryForObject("select id from spaces", UUID::class.java)
		jdbc.update("delete from spaces where id = ?", personalSpace)
		val coupleSpace = insertSpace("COUPLE")
		jdbc.update(
			"update spaces set together_color = 'pick-purple', start_date = ? where id = ?",
			java.sql.Date.valueOf(LocalDate.of(2024, 5, 1)), coupleSpace,
		)
		insertMember(coupleSpace, tokens.userId, "pick-orange")
		insertMember(coupleSpace, partnerId, "pick-blue")

		getMe(tokens.accessToken)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.space.id").value(coupleSpace.toString()))
			.andExpect(jsonPath("$.space.type").value("COUPLE"))
			.andExpect(jsonPath("$.space.myColor").value("pick-orange"))
			.andExpect(jsonPath("$.space.togetherColor").value("pick-purple"))
			.andExpect(jsonPath("$.space.startDate").value("2024-05-01"))
			.andExpect(jsonPath("$.space.partner.id").value(partnerId.toString()))
			.andExpect(jsonPath("$.space.partner.nickname").value("상대방"))
			.andExpect(jsonPath("$.space.partner.color").value("pick-blue"))
			.andExpect(jsonPath("$.space.partner.hasProfileImage").value(true))
			.andExpect(jsonPath("$.space.importPending").value(false))
	}

	@Test
	fun `FR-COUPLE-03 AC4 GET me는 가져오기 대기 중인 개인 공간이 있으면 importPending이 true다`() {
		val tokens = startGuest()
		makeMember(tokens.userId)
		val partnerId = insertUser("MEMBER", "상대방")
		// 혼자 쓴 기록이 있는 개인 공간은 소속에 left_at만 기록하고 남겨 둔다 (API.md 4.4 5단계)
		jdbc.update("update space_members set left_at = ? where user_id = ?", ts(clock.instant()), tokens.userId)
		val coupleSpace = insertSpace("COUPLE")
		insertMember(coupleSpace, tokens.userId, "pick-orange")
		insertMember(coupleSpace, partnerId, "pick-blue")

		getMe(tokens.accessToken)
			.andExpect(jsonPath("$.space.type").value("COUPLE"))
			.andExpect(jsonPath("$.space.importPending").value(true))
	}

	// ── PATCH /me 닉네임 ────────────────────────────────

	@Test
	fun `FR-AUTH-07 AC2 PATCH me로 닉네임을 바꾸면 GET me에 반영된다`() {
		val tokens = startGuest()

		patchMe(tokens.accessToken, """{"nickname": "새닉네임"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.nickname").value("새닉네임"))

		getMe(tokens.accessToken).andExpect(jsonPath("$.nickname").value("새닉네임"))
		assertEquals("새닉네임", jdbc.queryForObject("select nickname from users", String::class.java))
	}

	@Test
	fun `FR-AUTH-07 AC2 닉네임은 앞뒤 공백을 지운 값으로 저장한다`() {
		val tokens = startGuest()

		patchMe(tokens.accessToken, """{"nickname": "  민수 \t"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.nickname").value("민수"))
		assertEquals("민수", jdbc.queryForObject("select nickname from users", String::class.java))
	}

	@Test
	fun `FR-AUTH-07 AC2 닉네임이 10자를 넘으면 400 VALIDATION_FAILED (nickname TOO_LONG)`() {
		val tokens = startGuest()

		patchMe(tokens.accessToken, """{"nickname": "열한글자가넘는닉네임입니다"}""")
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.details.fields[0].field").value("nickname"))
			.andExpect(jsonPath("$.details.fields[0].reason").value("TOO_LONG"))
		assertEquals("게스트", jdbc.queryForObject("select nickname from users", String::class.java))
	}

	@Test
	fun `FR-AUTH-07 AC2 빈 닉네임은 400 VALIDATION_FAILED (nickname REQUIRED)`() {
		val tokens = startGuest()

		for (body in listOf("""{"nickname": ""}""", """{"nickname": "   "}""", """{"nickname": null}""")) {
			patchMe(tokens.accessToken, body)
				.andExpect(status().isBadRequest)
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.details.fields[0].field").value("nickname"))
				.andExpect(jsonPath("$.details.fields[0].reason").value("REQUIRED"))
		}
		assertEquals("게스트", jdbc.queryForObject("select nickname from users", String::class.java))
	}

	@Test
	fun `FR-AUTH-07 AC2 닉네임 글자 수는 코드 포인트로 센다 (D-82)`() {
		val tokens = startGuest()
		val tenEmojis = "😀".repeat(10) // UTF-16으로는 20, 코드 포인트로는 10

		patchMe(tokens.accessToken, """{"nickname": "$tenEmojis"}""").andExpect(status().isOk)
		assertEquals(tenEmojis, jdbc.queryForObject("select nickname from users", String::class.java))

		patchMe(tokens.accessToken, """{"nickname": "${tenEmojis}😀"}""")
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.details.fields[0].reason").value("TOO_LONG"))
	}

	@Test
	fun `FR-AUTH-07 AC2 PATCH me에서 없는 필드는 바꾸지 않는다`() {
		val tokens = startGuest()

		patchMe(tokens.accessToken, "{}")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.nickname").value("게스트"))
			.andExpect(jsonPath("$.timeZone").value("Asia/Seoul"))
		assertEquals(0, events.stream(UserTimeZoneChanged::class.java).count().toInt())
	}

	// ── PATCH /me 시간대 ────────────────────────────────

	@Test
	fun `NFR-TIME-01 PATCH me로 시간대를 바꾸면 users time_zone이 바뀐다`() {
		val tokens = startGuest()

		patchMe(tokens.accessToken, """{"timeZone": "America/New_York"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.timeZone").value("America/New_York"))

		assertEquals("America/New_York", jdbc.queryForObject("select time_zone from users", String::class.java))
		assertEquals("게스트", jdbc.queryForObject("select nickname from users", String::class.java))
	}

	@Test
	fun `NFR-TIME-01 시간대가 실제로 바뀔 때만 UserTimeZoneChanged 이벤트를 발행한다`() {
		val tokens = startGuest()

		patchMe(tokens.accessToken, """{"timeZone": "Asia/Seoul"}""").andExpect(status().isOk) // 그대로
		assertEquals(0, events.stream(UserTimeZoneChanged::class.java).count().toInt())

		patchMe(tokens.accessToken, """{"timeZone": "Europe/London"}""").andExpect(status().isOk)
		val published = events.stream(UserTimeZoneChanged::class.java).toList()
		assertEquals(1, published.size)
		assertEquals(UserTimeZoneChanged(tokens.userId, "Asia/Seoul", "Europe/London"), published[0])
	}

	@Test
	fun `NFR-TIME-01 IANA가 아닌 시간대는 400 VALIDATION_FAILED (timeZone INVALID)`() {
		val tokens = startGuest()

		for (zone in listOf("Mars/Phobos", "KST", "+09:00", "UTC+9", "", " ")) {
			patchMe(tokens.accessToken, """{"timeZone": "$zone"}""")
				.andExpect(status().isBadRequest)
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.details.fields[0].field").value("timeZone"))
				.andExpect(jsonPath("$.details.fields[0].reason").value("INVALID"))
		}
		assertEquals("Asia/Seoul", jdbc.queryForObject("select time_zone from users", String::class.java))
	}

	@Test
	fun `NFR-TIME-01 PATCH me에 null을 보내면 400 VALIDATION_FAILED (REQUIRED)`() {
		val tokens = startGuest()

		patchMe(tokens.accessToken, """{"timeZone": null}""")
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.details.fields[0].field").value("timeZone"))
			.andExpect(jsonPath("$.details.fields[0].reason").value("REQUIRED"))
	}

	@Test
	fun `NFR-TIME-01 한 요청의 잘못된 필드는 모두 details fields에 담는다`() {
		val tokens = startGuest()

		patchMe(tokens.accessToken, """{"nickname": " ", "timeZone": "Nowhere/City"}""")
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.details.fields.length()").value(2))
	}

	// ── PUT /me/devices ─────────────────────────────────

	@Test
	fun `NFR-SYNC-01 PUT me devices는 FCM 토큰을 등록한다 platform 기본값은 ANDROID`() {
		val tokens = startGuest()

		putDevice(tokens.accessToken, """{"fcmToken": "fake-fcm-token-1"}""").andExpect(status().isNoContent)

		assertEquals(1, count("select count(*) from devices where user_id = ? and fcm_token = 'fake-fcm-token-1' and platform = 'ANDROID'", tokens.userId))
	}

	@Test
	fun `NFR-SYNC-01 platform을 IOS로 등록할 수 있다`() {
		val tokens = startGuest()

		putDevice(tokens.accessToken, """{"fcmToken": "fake-fcm-token-1", "platform": "IOS"}""").andExpect(status().isNoContent)

		assertEquals(1, count("select count(*) from devices where platform = 'IOS'"))
	}

	@Test
	fun `NFR-SYNC-01 같은 FCM 토큰을 다시 등록하면 행을 늘리지 않고 updated_at만 갱신한다`() {
		val tokens = startGuest()
		putDevice(tokens.accessToken, """{"fcmToken": "fake-fcm-token-1"}""").andExpect(status().isNoContent)

		clock.advance(Duration.ofMinutes(5))
		putDevice(tokens.accessToken, """{"fcmToken": "fake-fcm-token-1", "platform": "IOS"}""").andExpect(status().isNoContent)

		assertEquals(1, count("select count(*) from devices"))
		assertEquals(clock.instant(), instantOf("select updated_at from devices"))
		assertEquals("IOS", jdbc.queryForObject("select platform from devices", String::class.java))
	}

	@Test
	fun `NFR-SYNC-01 같은 FCM 토큰이 다른 사용자로 오면 그 사용자로 옮긴다`() {
		val first = startGuest()
		val second = startGuest()
		putDevice(first.accessToken, """{"fcmToken": "fake-fcm-token-1"}""").andExpect(status().isNoContent)

		putDevice(second.accessToken, """{"fcmToken": "fake-fcm-token-1"}""").andExpect(status().isNoContent)

		assertEquals(1, count("select count(*) from devices"))
		assertEquals(second.userId, jdbc.queryForObject("select user_id from devices", UUID::class.java))
	}

	@Test
	fun `NFR-SYNC-01 fcmToken이 비었으면 400 VALIDATION_FAILED (fcmToken REQUIRED)`() {
		val tokens = startGuest()

		for (body in listOf("{}", """{"fcmToken": ""}""", """{"fcmToken": "  "}""", """{"fcmToken": null}""")) {
			putDevice(tokens.accessToken, body)
				.andExpect(status().isBadRequest)
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.details.fields[0].field").value("fcmToken"))
				.andExpect(jsonPath("$.details.fields[0].reason").value("REQUIRED"))
		}
		assertEquals(0, count("select count(*) from devices"))
	}

	@Test
	fun `NFR-SYNC-01 fcmToken이 255자를 넘으면 400 VALIDATION_FAILED (fcmToken TOO_LONG)`() {
		val tokens = startGuest()

		putDevice(tokens.accessToken, """{"fcmToken": "${"a".repeat(256)}"}""")
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.details.fields[0].field").value("fcmToken"))
			.andExpect(jsonPath("$.details.fields[0].reason").value("TOO_LONG"))
		putDevice(tokens.accessToken, """{"fcmToken": "${"a".repeat(255)}"}""").andExpect(status().isNoContent)
	}

	@Test
	fun `NFR-SYNC-01 platform이 ANDROID나 IOS가 아니면 400 VALIDATION_FAILED (platform INVALID)`() {
		val tokens = startGuest()

		putDevice(tokens.accessToken, """{"fcmToken": "fake-fcm-token-1", "platform": "WEB"}""")
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.details.fields[0].field").value("platform"))
			.andExpect(jsonPath("$.details.fields[0].reason").value("INVALID"))
		assertEquals(0, count("select count(*) from devices"))
	}

	@Test
	fun `FR-SET-01 me 경로는 access 토큰이 필요하다`() {
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("$API/me"))
			.andExpect(status().isUnauthorized)
			.andExpect(jsonPath("$.code").value("SESSION_INVALID"))
	}
}
