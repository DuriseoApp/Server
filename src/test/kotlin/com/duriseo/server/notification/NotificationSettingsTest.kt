package com.duriseo.server.notification

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.NotificationTestSupport
import com.duriseo.server.support.TestClockConfig
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import kotlin.test.assertEquals

/** 알림 설정 `GET`·`PATCH /me/notification-settings` (FR-NOTI-02, D-62, D-145) */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class NotificationSettingsTest : NotificationTestSupport() {

	@Test
	fun `FR-NOTI-02 AC1 게스트도 알림 설정을 읽고 바꿀 수 있다`() {
		val guest = startGuest()

		getSettings(guest.accessToken)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.eventReminder").value(true))
			.andExpect(jsonPath("$.partnerCreated").value(true))
			.andExpect(jsonPath("$.memoPinned").value(true))
			.andExpect(jsonPath("$.anniversary").value(true))
		patchSettings(guest.accessToken, """{"anniversary":false}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.anniversary").value(false))
		getSettings(guest.accessToken).andExpect(jsonPath("$.anniversary").value(false))
	}

	@Test
	fun `FR-NOTI-02 AC2 PATCH는 보낸 종류만 바꾸고 응답은 전체 설정이다`() {
		val me = startMember()

		patchSettings(me.accessToken, """{"partnerCreated":false}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.eventReminder").value(true))
			.andExpect(jsonPath("$.partnerCreated").value(false))
			.andExpect(jsonPath("$.memoPinned").value(true))
			.andExpect(jsonPath("$.anniversary").value(true))
		patchSettings(me.accessToken, """{"memoPinned":false,"partnerCreated":true}""")
			.andExpect(jsonPath("$.partnerCreated").value(true))
			.andExpect(jsonPath("$.memoPinned").value(false))
		// 빈 본문과 모르는 필드는 아무것도 바꾸지 않는다
		patchSettings(me.accessToken, """{}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.memoPinned").value(false))
		patchSettings(me.accessToken, """{"unknown":true}""").andExpect(status().isOk)
	}

	@Test
	fun `FR-NOTI-02 AC2 PATCH는 null이면 400 REQUIRED이고 boolean이 아니면 INVALID이며 아무것도 바꾸지 않는다`() {
		val me = startMember()
		val before = jdbc.queryForList("select * from notification_settings").toString()

		val nullResult = patchSettings(me.accessToken, """{"eventReminder":null,"memoPinned":false}""").andExpect(status().isBadRequest)
		assertEquals("VALIDATION_FAILED", JsonPath.read<String>(bodyOf(nullResult), "$.code"))
		assertEquals("eventReminder", JsonPath.read<String>(bodyOf(nullResult), "$.details.fields[0].field"))
		assertEquals("REQUIRED", JsonPath.read<String>(bodyOf(nullResult), "$.details.fields[0].reason"))

		val invalid = patchSettings(me.accessToken, """{"partnerCreated":"yes","anniversary":1}""").andExpect(status().isBadRequest)
		assertEquals(
			listOf("partnerCreated", "anniversary"), JsonPath.read<List<String>>(bodyOf(invalid), "$.details.fields[*].field"),
		)
		assertEquals(listOf("INVALID", "INVALID"), JsonPath.read<List<String>>(bodyOf(invalid), "$.details.fields[*].reason"))

		assertEquals(before, jdbc.queryForList("select * from notification_settings").toString())
	}

	@Test
	fun `FR-NOTI-02 AC1 설정은 사용자마다 따로이고 서버에 저장된다`() {
		val a = startMember()
		val b = startMember()

		patchSettings(a.accessToken, """{"eventReminder":false}""").andExpect(status().isOk)

		getSettings(b.accessToken).andExpect(jsonPath("$.eventReminder").value(true))
		// 같은 계정으로 다시 읽어도 유지된다 (재설치 후 같은 계정, D-62)
		clock.advance(Duration.ofMinutes(1))
		getSettings(a.accessToken).andExpect(jsonPath("$.eventReminder").value(false))
	}

	@Test
	fun `FR-NOTI-02 AC2 두 종류를 동시에 바꿔도 둘 다 저장된다`() {
		val me = startMember()

		repeat(10) {
			patchSettings(me.accessToken, """{"eventReminder":true,"memoPinned":true}""").andExpect(status().isOk)

			val results = inParallel(
				listOf(
					{ statusOf(patchSettings(me.accessToken, """{"eventReminder":false}""")) },
					{ statusOf(patchSettings(me.accessToken, """{"memoPinned":false}""")) },
				),
			)

			assertEquals(listOf(200, 200), results)
			getSettings(me.accessToken)
				.andExpect(jsonPath("$.eventReminder").value(false))
				.andExpect(jsonPath("$.memoPinned").value(false))
		}
	}

	@Test
	fun `FR-NOTI-02 AC1 설정 행이 없어도 동시에 읽으면 모두 200이고 기본값이 켜짐이다`() {
		val me = startMember()
		jdbc.update("delete from notification_settings where user_id = ?", me.userId)

		val results = inParallel(List(4) { { statusOf(getSettings(me.accessToken)) } })

		assertEquals(listOf(200, 200, 200, 200), results)
		assertEquals(1, count("select count(*) from notification_settings where user_id = ?", me.userId))
		getSettings(me.accessToken).andExpect(jsonPath("$.eventReminder").value(true))
	}
}
