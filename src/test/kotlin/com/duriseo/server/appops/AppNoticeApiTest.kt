package com.duriseo.server.appops

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.TestClockConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration

/** 공지 공개 조회 (FR-APP-01, D-176, API.md 3.7) */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class AppNoticeApiTest : AppOpsTestSupport() {

	private fun notice(id: String, extra: String = "") =
		putNotice(id, """{"closable": true, "title": "제목 $id", "image": "preset:notice"$extra}""").andExpect(status().isOk)

	@Test
	fun `FR_APP_01_공개조회는_지금_유효한_공지만_정렬해서_준다`() {
		val now = clock.instant()
		notice("b-always")
		clock.advance(Duration.ofSeconds(10))
		notice("a-active", """, "startsAt": "${now.minusSeconds(60)}", "endsAt": "${now.plusSeconds(3600)}"""")
		notice("c-not-started", """, "startsAt": "${now.plusSeconds(3600)}"""")
		notice("d-ended", """, "endsAt": "${now.minusSeconds(60)}"""")
		notice("e-platform", """, "platform": "IOS"""")

		// 로그인 없이, 헤더 없이 부른다. 지금 유효한 것만, 플랫폼은 거르지 않고, created_at 오래된 순
		mockMvc.perform(get("$API/app-notices"))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.items.length()").value(3))
			.andExpect(jsonPath("$.items[0].id").value("b-always"))
			.andExpect(jsonPath("$.items[1].id").value("a-active"))
			.andExpect(jsonPath("$.items[2].id").value("e-platform"))
			.andExpect(jsonPath("$.items[2].platform").value("IOS"))
	}

	@Test
	fun `FR_APP_01_기간_경계는_시작_포함_끝_미포함이다`() {
		val now = clock.instant()
		notice("starts-now", """, "startsAt": "$now"""")
		notice("ends-now", """, "endsAt": "$now"""")

		mockMvc.perform(get("$API/app-notices"))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].id").value("starts-now"))
	}

	@Test
	fun `FR_APP_01_같은_시각에_만든_공지는_id순이다`() {
		notice("n-2")
		notice("n-1")

		mockMvc.perform(get("$API/app-notices"))
			.andExpect(jsonPath("$.items[0].id").value("n-1"))
			.andExpect(jsonPath("$.items[1].id").value("n-2"))
	}

	@Test
	fun `FR_APP_01_값이_없는_필드는_null로_보낸다`() {
		putNotice("full", """{"closable": false, "title": "제목"}""").andExpect(status().isOk)

		mockMvc.perform(get("$API/app-notices"))
			.andExpect(jsonPath("$.items[0].closable").value(false))
			.andExpect(jsonPath("$.items[0].body").isEmpty())
			.andExpect(jsonPath("$.items[0].platform").isEmpty())
			.andExpect(jsonPath("$.items[0].startsAt").isEmpty())
	}
}
