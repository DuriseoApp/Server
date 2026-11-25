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

/** 관리자 공지 API (D-176, D-183, API.md 3.7) */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class AdminAppNoticeApiTest : AppOpsTestSupport() {

	private fun invalid(json: String, expected: Map<String, String>, id: String = "n-1") {
		val result = putNotice(id, json).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
		assertEquals(expected, fieldErrors(result), json)
		assertEquals(0, jdbc.queryForObject("select count(*) from app_notices", Int::class.java))
	}

	@Test
	fun `D_176_PUT은_없으면_만들고_있으면_바꾼다`() {
		putNotice("n-1", """{"id": "ignored", "closable": true, "title": "처음", "body": "줄1\n줄2", "image": "preset:error", "linkText": "보기", "linkUrl": "https://example.com/a", "platform": "ANDROID", "startsAt": "2026-10-20T00:00:00Z", "endsAt": "2026-12-01T09:30:00Z"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.id").value("n-1"))
			.andExpect(jsonPath("$.title").value("처음"))
			.andExpect(jsonPath("$.body").value("줄1\n줄2"))
			.andExpect(jsonPath("$.platform").value("ANDROID"))
			.andExpect(jsonPath("$.startsAt").value("2026-10-20T00:00:00Z"))
			.andExpect(jsonPath("$.endsAt").value("2026-12-01T09:30:00Z"))
		val createdAt = jdbc.queryForObject("select created_at from app_notices where id = 'n-1'", java.sql.Timestamp::class.java)

		clock.advance(Duration.ofMinutes(5))
		putNotice("n-1", """{"closable": false, "title": "바뀜"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.closable").value(false))
			.andExpect(jsonPath("$.title").value("바뀜"))
			.andExpect(jsonPath("$.body").isEmpty())
			.andExpect(jsonPath("$.linkUrl").isEmpty())

		assertEquals(1, jdbc.queryForObject("select count(*) from app_notices", Int::class.java))
		assertEquals(createdAt, jdbc.queryForObject("select created_at from app_notices", java.sql.Timestamp::class.java))
		assertEquals(
			clock.instant(),
			jdbc.queryForObject("select updated_at from app_notices", java.sql.Timestamp::class.java)!!.toInstant(),
		)
	}

	@Test
	fun `D_176_관리자_조회는_끝난것도_모두_준다`() {
		val now = clock.instant()
		putNotice("ended", """{"closable": true, "title": "끝남", "endsAt": "${now.minusSeconds(10)}"}""").andExpect(status().isOk)
		putNotice("future", """{"closable": true, "title": "예정", "startsAt": "${now.plusSeconds(3600)}"}""").andExpect(status().isOk)

		adminGet("app-notices").andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(2))
	}

	@Test
	fun `D_176_DELETE는_지우고_없으면_404이고_형식이_틀린_id도_404`() {
		putNotice("n-1", VALID_NOTICE).andExpect(status().isOk)

		adminDelete("app-notices/n-1").andExpect(status().isNoContent)
		assertEquals(0, jdbc.queryForObject("select count(*) from app_notices", Int::class.java))
		adminDelete("app-notices/n-1").andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("NOT_FOUND"))
		adminDelete("app-notices/BAD.ID").andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("NOT_FOUND"))
	}

	@Test
	fun `D_176_경로_id_형식이_틀리면_400_id_INVALID`() {
		for (id in listOf("UPPER", "has.dot", "a".repeat(51))) {
			invalid(VALID_NOTICE, mapOf("id" to "INVALID"), id)
		}
		putNotice("a".repeat(50), VALID_NOTICE).andExpect(status().isOk)
		putNotice("a_b-9", VALID_NOTICE).andExpect(status().isOk)
	}

	@Test
	fun `D_176_closable은_필수이고_boolean이어야_한다`() {
		invalid("""{"title": "t"}""", mapOf("closable" to "REQUIRED"))
		invalid("""{"closable": null, "title": "t"}""", mapOf("closable" to "REQUIRED"))
		invalid("""{"closable": "true", "title": "t"}""", mapOf("closable" to "INVALID"))
	}

	@Test
	fun `D_176_길이_초과는_TOO_LONG_틀린_필드는_모두_돌려준다`() {
		invalid(
			"""{"closable": true, "title": "${"가".repeat(41)}", "body": "${"b".repeat(501)}", "imageAlt": "${"a".repeat(101)}", "linkText": "${"l".repeat(21)}", "linkUrl": "https://e.com/${"u".repeat(500)}"}""",
			mapOf("title" to "TOO_LONG", "body" to "TOO_LONG", "imageAlt" to "TOO_LONG", "linkText" to "TOO_LONG", "linkUrl" to "TOO_LONG"),
		)
		putNotice(
			"n-1",
			"""{"closable": true, "title": "${"가".repeat(40)}", "body": "${"b".repeat(500)}", "imageAlt": "${"a".repeat(100)}", "linkText": "${"l".repeat(20)}"}""",
		).andExpect(status().isOk)
	}

	@Test
	fun `D_183_빈_문자열은_null이_아니라_INVALID`() {
		invalid(
			"""{"closable": true, "title": "", "body": "", "imageAlt": "", "linkText": ""}""",
			mapOf("title" to "INVALID", "body" to "INVALID", "imageAlt" to "INVALID", "linkText" to "INVALID"),
		)
	}

	@Test
	fun `D_183_제목이_없으면_https_이미지와_imageAlt가_필요하다`() {
		// preset만으로는 안 된다 + imageAlt 없음
		invalid("""{"closable": true, "image": "preset:notice"}""", mapOf("image" to "REQUIRED", "imageAlt" to "REQUIRED"))
		invalid("""{"closable": true}""", mapOf("image" to "REQUIRED", "imageAlt" to "REQUIRED"))
		// 이미지는 https인데 imageAlt가 없음
		invalid("""{"closable": true, "image": "https://example.com/a.png"}""", mapOf("imageAlt" to "REQUIRED"))
		// 제목 없이 본문만
		invalid(
			"""{"closable": true, "body": "본문", "image": "https://example.com/a.png", "imageAlt": "설명"}""",
			mapOf("title" to "REQUIRED"),
		)
		putNotice("n-1", """{"closable": true, "image": "https://example.com/a.png", "imageAlt": "설명"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.title").isEmpty())
	}

	@Test
	fun `D_183_제목이_있고_본문이_없는_것은_허용한다`() {
		putNotice("n-1", """{"closable": true, "title": "제목만"}""").andExpect(status().isOk)
	}

	@Test
	fun `D_176_image_platform_linkUrl_형식이_틀리면_INVALID`() {
		invalid(
			"""{"closable": true, "title": "t", "image": "http://example.com/a.png", "platform": "WEB", "linkUrl": "http://example.com"}""",
			mapOf("image" to "INVALID", "platform" to "INVALID", "linkUrl" to "INVALID"),
		)
		invalid("""{"closable": true, "title": "t", "image": "preset:other"}""", mapOf("image" to "INVALID"))
		invalid("""{"closable": true, "title": "t", "image": "https://e.com/${"i".repeat(500)}"}""", mapOf("image" to "TOO_LONG"))
		putNotice("n-1", """{"closable": true, "title": "t", "image": "preset:update", "platform": "IOS"}""").andExpect(status().isOk)
	}

	@Test
	fun `D_176_일시_형식과_기간_순서를_검사한다`() {
		invalid(
			"""{"closable": true, "title": "t", "startsAt": "2026-10-20 00:00:00", "endsAt": "2026-13-01T00:00:00Z"}""",
			mapOf("startsAt" to "INVALID", "endsAt" to "INVALID"),
		)
		invalid(
			"""{"closable": true, "title": "t", "startsAt": "2026-10-20T00:00:00Z", "endsAt": "2026-10-20T00:00:00Z"}""",
			mapOf("endsAt" to "INVALID"),
		)
		invalid(
			"""{"closable": true, "title": "t", "startsAt": "2026-10-20T00:00:00Z", "endsAt": "2026-10-19T00:00:00Z"}""",
			mapOf("endsAt" to "INVALID"),
		)
	}

	@Test
	fun `D_176_linkText와_linkUrl은_하나만_있어도_저장한다`() {
		putNotice("n-1", """{"closable": true, "title": "t", "linkText": "보기"}""").andExpect(status().isOk)
		putNotice("n-2", """{"closable": true, "title": "t", "linkUrl": "https://example.com"}""").andExpect(status().isOk)
	}
}
