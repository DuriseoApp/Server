package com.duriseo.server.user

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.IntegrationTestSupport
import com.duriseo.server.support.TestClockConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `PUT /me/devices`의 기기 정보 (FR-APP-03, D-182, ERD DB-22, API.md 3.1) */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class DeviceApiTest : IntegrationTestSupport() {

	private fun row() = jdbc.queryForMap("select os_version, app_version, app_build from devices")

	@Test
	fun `FR_APP_03_AC2_기기정보를_저장한다`() {
		val tokens = startGuest()

		putDevice(tokens.accessToken, """{"fcmToken": "fake-fcm-token-1", "osVersion": "34", "appVersion": "1.0.0", "appBuild": 10}""")
			.andExpect(status().isNoContent)

		val row = row()
		assertEquals("34", row["os_version"])
		assertEquals("1.0.0", row["app_version"])
		assertEquals(10, row["app_build"])
	}

	@Test
	fun `FR_APP_03_AC2_안보낸값은_null로_덮어쓴다`() {
		val tokens = startGuest()
		putDevice(tokens.accessToken, """{"fcmToken": "fake-fcm-token-1", "osVersion": "34", "appVersion": "1.0.0", "appBuild": 10}""")
			.andExpect(status().isNoContent)

		putDevice(tokens.accessToken, """{"fcmToken": "fake-fcm-token-1", "appVersion": "1.0.1"}""").andExpect(status().isNoContent)

		val row = row()
		assertNull(row["os_version"])
		assertEquals("1.0.1", row["app_version"])
		assertNull(row["app_build"])
	}

	@Test
	fun `FR_APP_03_AC2_경계값은_저장한다`() {
		val tokens = startGuest()

		putDevice(
			tokens.accessToken,
			"""{"fcmToken": "fake-fcm-token-1", "osVersion": "${"1".repeat(20)}", "appVersion": "${"v".repeat(50)}", "appBuild": 2147483647}""",
		).andExpect(status().isNoContent)
		putDevice(tokens.accessToken, """{"fcmToken": "fake-fcm-token-1", "appBuild": 0}""").andExpect(status().isNoContent)

		assertEquals(0, row()["app_build"])
	}

	@Test
	fun `FR_APP_03_AC2_범위밖은_400`() {
		val tokens = startGuest()

		putDevice(tokens.accessToken, """{"fcmToken": "fake-fcm-token-1", "osVersion": "${"1".repeat(21)}"}""")
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.details.fields[0].field").value("osVersion"))
			.andExpect(jsonPath("$.details.fields[0].reason").value("TOO_LONG"))
		putDevice(tokens.accessToken, """{"fcmToken": "fake-fcm-token-1", "appVersion": "${"v".repeat(51)}"}""")
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.details.fields[0].field").value("appVersion"))
			.andExpect(jsonPath("$.details.fields[0].reason").value("TOO_LONG"))
		for (build in listOf("-1", "2147483648", "\"10\"", "1.5", "true")) {
			putDevice(tokens.accessToken, """{"fcmToken": "fake-fcm-token-1", "appBuild": $build}""")
				.andExpect(status().isBadRequest)
				.andExpect(jsonPath("$.details.fields[0].field").value("appBuild"))
				.andExpect(jsonPath("$.details.fields[0].reason").value("INVALID"))
		}
		assertEquals(0, jdbc.queryForObject("select count(*) from devices", Int::class.java))
	}
}
