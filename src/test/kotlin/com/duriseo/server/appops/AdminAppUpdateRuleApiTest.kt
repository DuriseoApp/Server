package com.duriseo.server.appops

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.TestClockConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import kotlin.test.assertEquals

/** 관리자 업데이트 규칙 API (D-177, D-183, API.md 3.7) */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class AdminAppUpdateRuleApiTest : AppOpsTestSupport() {

	private fun invalid(json: String, expected: Map<String, String>, id: String = "r-1") {
		val result = putRule(id, json).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
		assertEquals(expected, fieldErrors(result), json)
		assertEquals(0, jdbc.queryForObject("select count(*) from app_update_rules", Int::class.java))
	}

	/** 제목·이미지를 따로 정하지 않으면 제목을 붙인다(제목 없는 팝업은 https 이미지가 필요하다, D-183) */
	private fun rule(build: String, extra: String = ""): String {
		val hasContent = listOf("\"title\"", "\"body\"", "\"image\"").any { it in extra }
		val title = if (hasContent) "" else """, "title": "t""""
		return """{"mode": "FORCE", "platform": "ANDROID", "build": $build$extra$title}"""
	}

	@Test
	fun `D_177_PUT은_없으면_만들고_있으면_바꾼다`() {
		putRule("r-1", """{"id": "ignored", "mode": "FORCE", "platform": "ANDROID", "build": {"op": "RANGE", "min": 5, "max": 9}, "osVersion": {"min": 26, "max": 34}, "title": "업데이트", "body": "본문", "image": "preset:update"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.id").value("r-1"))
			.andExpect(jsonPath("$.build.op").value("RANGE"))
			.andExpect(jsonPath("$.build.min").value(5))
			.andExpect(jsonPath("$.build.value").isEmpty())
			.andExpect(jsonPath("$.build.values").isEmpty())
			.andExpect(jsonPath("$.osVersion.max").value(34))
		val createdAt = jdbc.queryForObject("select created_at from app_update_rules", java.sql.Timestamp::class.java)

		clock.advance(Duration.ofMinutes(5))
		putRule("r-1", """{"mode": "RECOMMEND", "platform": "IOS", "build": {"op": "IN", "values": [3, 4]}, "title": "t"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.mode").value("RECOMMEND"))
			.andExpect(jsonPath("$.build.min").isEmpty())
			.andExpect(jsonPath("$.build.values[1]").value(4))
			.andExpect(jsonPath("$.osVersion").isEmpty())
			.andExpect(jsonPath("$.title").value("t"))
			.andExpect(jsonPath("$.body").isEmpty())
			.andExpect(jsonPath("$.image").isEmpty())

		assertEquals(1, jdbc.queryForObject("select count(*) from app_update_rules", Int::class.java))
		assertEquals(createdAt, jdbc.queryForObject("select created_at from app_update_rules", java.sql.Timestamp::class.java))
		assertEquals(null, jdbc.queryForObject("select build_min from app_update_rules", Int::class.javaObjectType))
	}

	@Test
	fun `D_177_관리자_조회는_전부_주고_DELETE는_지우며_없으면_404`() {
		putRule("r-1", VALID_RULE).andExpect(status().isOk)
		adminGet("app-update-rules").andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(1))

		adminDelete("app-update-rules/r-1").andExpect(status().isNoContent)
		adminDelete("app-update-rules/r-1").andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("NOT_FOUND"))
		adminDelete("app-update-rules/BAD.ID").andExpect(status().isNotFound)
		adminGet("app-update-rules").andExpect(jsonPath("$.items.length()").value(0))
	}

	@Test
	fun `D_177_경로_id_형식이_틀리면_400_id_INVALID`() {
		invalid(VALID_RULE, mapOf("id" to "INVALID"), "BAD ID")
	}

	@Test
	fun `D_177_mode_platform_build는_필수`() {
		invalid("{}", mapOf("mode" to "REQUIRED", "platform" to "REQUIRED", "build" to "REQUIRED", "image" to "REQUIRED", "imageAlt" to "REQUIRED"))
		invalid(rule("{}").replace(""""mode": "FORCE", """, ""), mapOf("mode" to "REQUIRED", "build.op" to "REQUIRED"))
		invalid("""{"mode": "BLOCK", "platform": "WEB", "build": "x"}""", mapOf("mode" to "INVALID", "platform" to "INVALID", "build" to "INVALID", "image" to "REQUIRED", "imageAlt" to "REQUIRED"))
		invalid(rule("""{"op": "NE", "value": 1}"""), mapOf("build.op" to "INVALID"))
	}

	@Test
	fun `D_177_op에_필요한_값이_없으면_REQUIRED`() {
		for (op in listOf("EQ", "LTE", "GTE")) {
			invalid(rule("""{"op": "$op"}"""), mapOf("build.value" to "REQUIRED"))
		}
		invalid(rule("""{"op": "RANGE"}"""), mapOf("build.min" to "REQUIRED", "build.max" to "REQUIRED"))
		invalid(rule("""{"op": "RANGE", "min": 3}"""), mapOf("build.max" to "REQUIRED"))
		invalid(rule("""{"op": "IN"}"""), mapOf("build.values" to "REQUIRED"))
		invalid(rule("""{"op": "IN", "values": []}"""), mapOf("build.values" to "REQUIRED"))
	}

	@Test
	fun `D_177_op에_맞지_않는_필드가_null이_아니면_INVALID`() {
		invalid(rule("""{"op": "EQ", "value": 1, "min": 1}"""), mapOf("build.min" to "INVALID"))
		invalid(rule("""{"op": "RANGE", "min": 1, "max": 2, "value": 3}"""), mapOf("build.value" to "INVALID"))
		invalid(rule("""{"op": "IN", "values": [1], "max": 2}"""), mapOf("build.max" to "INVALID"))
		invalid(rule("""{"op": "LTE", "value": 1, "values": [1]}"""), mapOf("build.values" to "INVALID"))
		// null은 허용
		putRule("r-1", rule("""{"op": "EQ", "value": 1, "min": null, "max": null, "values": null}""")).andExpect(status().isOk)
	}

	@Test
	fun `D_177_정수_범위와_min_max_순서를_검사한다`() {
		invalid(rule("""{"op": "EQ", "value": -1}"""), mapOf("build.value" to "INVALID"))
		invalid(rule("""{"op": "EQ", "value": 2147483648}"""), mapOf("build.value" to "INVALID"))
		invalid(rule("""{"op": "EQ", "value": 1.5}"""), mapOf("build.value" to "INVALID"))
		invalid(rule("""{"op": "EQ", "value": "1"}"""), mapOf("build.value" to "INVALID"))
		invalid(rule("""{"op": "RANGE", "min": 9, "max": 5}"""), mapOf("build.min" to "INVALID"))
		invalid(rule("""{"op": "IN", "values": [1, -2, 3, "x"]}"""), mapOf("build.values[1]" to "INVALID", "build.values[3]" to "INVALID"))
		invalid(rule("""{"op": "IN", "values": [${(1..51).joinToString()}]}"""), mapOf("build.values" to "TOO_LONG"))
		putRule("r-1", rule("""{"op": "IN", "values": [${(1..50).joinToString()}]}""")).andExpect(status().isOk)
		putRule("r-2", rule("""{"op": "RANGE", "min": 0, "max": 2147483647}""")).andExpect(status().isOk)
		putRule("r-3", rule("""{"op": "RANGE", "min": 4, "max": 4}""")).andExpect(status().isOk)
	}

	@Test
	fun `D_177_osVersion은_둘_중_하나_이상_정수여야_한다`() {
		invalid(rule("""{"op": "EQ", "value": 1}""", """, "osVersion": {}"""), mapOf("osVersion" to "INVALID"))
		invalid(rule("""{"op": "EQ", "value": 1}""", """, "osVersion": {"min": null, "max": null}"""), mapOf("osVersion" to "INVALID"))
		invalid(rule("""{"op": "EQ", "value": 1}""", """, "osVersion": {"min": -1, "max": 2147483648}"""), mapOf("osVersion.min" to "INVALID", "osVersion.max" to "INVALID"))
		invalid(rule("""{"op": "EQ", "value": 1}""", """, "osVersion": 34"""), mapOf("osVersion" to "INVALID"))
		putRule("r-1", rule("""{"op": "EQ", "value": 1}""", """, "osVersion": {"max": 30}""")).andExpect(status().isOk)
		putRule("r-2", rule("""{"op": "EQ", "value": 1}""", """, "osVersion": null""")).andExpect(status().isOk)
	}

	@Test
	fun `D_183_업데이트_규칙도_제목_본문_이미지_조합을_검사한다`() {
		invalid(rule("""{"op": "EQ", "value": 1}""", """, "image": "preset:update""""), mapOf("image" to "REQUIRED", "imageAlt" to "REQUIRED"))
		invalid(rule("""{"op": "EQ", "value": 1}""", """, "body": "본문", "image": "https://e.com/a.png", "imageAlt": "설명""""), mapOf("title" to "REQUIRED"))
		invalid(rule("""{"op": "EQ", "value": 1}""", """, "title": "", "body": """""), mapOf("title" to "INVALID", "body" to "INVALID"))
		invalid(rule("""{"op": "EQ", "value": 1}""", """, "title": "t", "imageAlt": "${"a".repeat(101)}""""), mapOf("imageAlt" to "TOO_LONG"))
		putRule("r-1", rule("""{"op": "EQ", "value": 1}""", """, "image": "https://e.com/a.png", "imageAlt": "설명"""")).andExpect(status().isOk)
		// 제목이 있고 본문·이미지가 없는 것은 허용 (앱이 preset:update를 쓴다)
		putRule("r-2", rule("""{"op": "EQ", "value": 1}""", """, "title": "제목만"""")).andExpect(status().isOk)
	}
}
