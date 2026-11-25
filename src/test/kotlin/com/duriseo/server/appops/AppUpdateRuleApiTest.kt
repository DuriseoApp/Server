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

/** 업데이트 규칙 공개 조회 (FR-APP-02, D-177, API.md 3.7) */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class AppUpdateRuleApiTest : AppOpsTestSupport() {

	@Test
	fun `FR_APP_02_공개조회는_모든_규칙을_준다`() {
		putRule("rule-b", VALID_RULE).andExpect(status().isOk)
		clock.advance(Duration.ofSeconds(5))
		putRule("rule-a", """{"mode": "RECOMMEND", "platform": "IOS", "build": {"op": "RANGE", "min": 5, "max": 9}, "osVersion": {"min": 26}, "title": "a"}""")
			.andExpect(status().isOk)
		clock.advance(Duration.ofSeconds(5))
		putRule("rule-c", """{"mode": "FORCE", "platform": "ANDROID", "build": {"op": "IN", "values": [1, 2, 3]}, "title": "c"}""")
			.andExpect(status().isOk)

		// 로그인 없이, 플랫폼으로 거르지 않고, 기간이 없어 모두. created_at 오래된 순
		mockMvc.perform(get("$API/app-update-rules"))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.items.length()").value(3))
			.andExpect(jsonPath("$.items[0].id").value("rule-b"))
			.andExpect(jsonPath("$.items[0].mode").value("FORCE"))
			.andExpect(jsonPath("$.items[0].build.op").value("LTE"))
			.andExpect(jsonPath("$.items[0].build.value").value(9))
			.andExpect(jsonPath("$.items[0].build.min").isEmpty())
			.andExpect(jsonPath("$.items[0].osVersion").isEmpty())
			.andExpect(jsonPath("$.items[1].id").value("rule-a"))
			.andExpect(jsonPath("$.items[1].build.min").value(5))
			.andExpect(jsonPath("$.items[1].build.max").value(9))
			.andExpect(jsonPath("$.items[1].osVersion.min").value(26))
			.andExpect(jsonPath("$.items[1].osVersion.max").isEmpty())
			.andExpect(jsonPath("$.items[2].id").value("rule-c"))
			.andExpect(jsonPath("$.items[2].build.values[2]").value(3))
	}
}
