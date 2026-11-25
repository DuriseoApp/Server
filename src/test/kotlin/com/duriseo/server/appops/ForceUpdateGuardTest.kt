package com.duriseo.server.appops

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.TestClockConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals

/** 강제 업데이트 차단 (R-20, D-180, FR-APP-02.AC6, API.md 1장 검사 순서) */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class ForceUpdateGuardTest : AppOpsTestSupport() {

	private fun app(
		request: MockHttpServletRequestBuilder,
		platform: String? = "ANDROID",
		build: String? = "9",
		os: String? = "34",
	): ResultActions {
		if (platform != null) request.header("X-Platform", platform)
		if (build != null) request.header("X-App-Build", build)
		if (os != null) request.header("X-OS-Version", os)
		return mockMvc.perform(request)
	}

	private fun forceRule(id: String = "force-1", extra: String = "") =
		putRule(id, """{"mode": "FORCE", "platform": "ANDROID", "build": {"op": "LTE", "value": 9}, "title": "업데이트가 필요해요", "body": "새 버전으로 업데이트해 주세요", "image": "preset:update", "imageAlt": "설명"$extra}""")
			.andExpect(status().isOk)

	@Test
	fun `R_20_맞는_FORCE_규칙이면_426`() {
		val tokens = startGuest()
		forceRule()

		app(get("$API/me").header("Authorization", "Bearer ${tokens.accessToken}"))
			.andExpect(status().isUpgradeRequired)
			.andExpect(jsonPath("$.code").value("APP_VERSION_UNSUPPORTED"))
			.andExpect(jsonPath("$.details.ruleId").value("force-1"))
			.andExpect(jsonPath("$.details.title").value("업데이트가 필요해요"))
			.andExpect(jsonPath("$.details.body").value("새 버전으로 업데이트해 주세요"))
			.andExpect(jsonPath("$.details.image").value("preset:update"))
			.andExpect(jsonPath("$.details.imageAlt").value("설명"))
			.andExpect(jsonPath("$.traceId").isNotEmpty)
		// 더 높은 빌드는 통과한다
		app(get("$API/me").header("Authorization", "Bearer ${tokens.accessToken}"), build = "10").andExpect(status().isOk)
	}

	@Test
	fun `R_20_규칙에_없는_값은_details에서_null`() {
		putRule("force-1", """{"mode": "FORCE", "platform": "ANDROID", "build": {"op": "EQ", "value": 9}, "title": "제목만"}""")
			.andExpect(status().isOk)

		app(get("$API/me"))
			.andExpect(status().isUpgradeRequired)
			.andExpect(jsonPath("$.details.title").value("제목만"))
			.andExpect(jsonPath("$.details.body").isEmpty())
			.andExpect(jsonPath("$.details.image").isEmpty())
			.andExpect(jsonPath("$.details.imageAlt").isEmpty())
	}

	@Test
	fun `R_20_로그인전_요청도_막는다`() {
		forceRule()

		// 인증보다 먼저다: access 토큰이 없어도, 로그인 전 경로도 426이다
		app(get("$API/me")).andExpect(status().isUpgradeRequired)
		app(get("$API/me").header("Authorization", "Bearer broken")).andExpect(status().isUpgradeRequired)
		app(post("$API/auth/guest")).andExpect(status().isUpgradeRequired)
		app(post("$API/auth/refresh")).andExpect(status().isUpgradeRequired)
		assertEquals(0, jdbc.queryForObject("select count(*) from users", Int::class.java))
	}

	@Test
	fun `R_20_헤더가_없으면_통과`() {
		forceRule()

		// X-Platform이나 X-App-Build가 없거나 읽을 수 없으면 통과 (인증 쪽 오류가 나온다)
		app(get("$API/me"), platform = null).andExpect(status().isUnauthorized)
		app(get("$API/me"), build = null).andExpect(status().isUnauthorized)
		app(get("$API/me"), platform = null, build = null, os = null).andExpect(status().isUnauthorized)
		for (badBuild in listOf("abc", "-1", "2147483648", "9.5", " 9", "")) {
			app(get("$API/me"), build = badBuild).andExpect(status().isUnauthorized)
		}
		app(get("$API/me"), platform = "WEB").andExpect(status().isUnauthorized)
		app(post("$API/auth/guest"), platform = null, build = null, os = null).andExpect(status().isCreated)
	}

	@Test
	fun `R_20_다른_플랫폼이면_통과`() {
		forceRule()

		app(get("$API/me"), platform = "IOS").andExpect(status().isUnauthorized)
	}

	@Test
	fun `R_20_예외경로는_막지않는다`() {
		forceRule()

		app(get("$API/app-notices")).andExpect(status().isOk)
		app(get("$API/app-update-rules")).andExpect(status().isOk).andExpect(jsonPath("$.items[0].id").value("force-1"))
		// 이미지가 없으면 426이 아니라 404
		app(get("$API/app-notice-images/${UUID.randomUUID()}.png")).andExpect(status().isNotFound)
		// 관리자 API는 규칙을 잘못 넣어도 지울 수 있어야 한다
		app(get("$API/admin/app-update-rules").header("X-Admin-Key", ADMIN_KEY)).andExpect(status().isOk)
		app(get("$API/admin/app-update-rules")).andExpect(status().isUnauthorized)
	}

	@Test
	fun `R_20_RECOMMEND는_막지않는다`() {
		putRule("rec-1", """{"mode": "RECOMMEND", "platform": "ANDROID", "build": {"op": "LTE", "value": 9}, "title": "권장"}""")
			.andExpect(status().isOk)

		app(get("$API/me")).andExpect(status().isUnauthorized)
	}

	@Test
	fun `R_20_OS헤더없으면_OS조건규칙만_건너뜀`() {
		forceRule("with-os", """, "osVersion": {"min": 26, "max": 30}""")

		// OS 조건이 있는 규칙: 헤더가 없거나 틀리거나 범위 밖이면 건너뛴다
		app(get("$API/me"), os = null).andExpect(status().isUnauthorized)
		app(get("$API/me"), os = "abc").andExpect(status().isUnauthorized)
		app(get("$API/me"), os = "2147483648").andExpect(status().isUnauthorized)
		app(get("$API/me"), os = "25").andExpect(status().isUnauthorized)
		app(get("$API/me"), os = "31").andExpect(status().isUnauthorized)
		app(get("$API/me"), os = "26").andExpect(status().isUpgradeRequired)
		app(get("$API/me"), os = "30").andExpect(status().isUpgradeRequired)

		// OS 조건이 없는 규칙은 OS 헤더가 없어도 판단한다
		forceRule("without-os")
		app(get("$API/me"), os = null)
			.andExpect(status().isUpgradeRequired)
			.andExpect(jsonPath("$.details.ruleId").value("without-os"))
	}

	@Test
	fun `R_20_맞는_규칙이_여럿이면_정렬_첫번째`() {
		forceRule("rule-b")
		clock.advance(Duration.ofSeconds(5))
		forceRule("rule-a")
		app(get("$API/me")).andExpect(jsonPath("$.details.ruleId").value("rule-b"))

		// 같은 created_at이면 id 순: 두 행의 created_at을 맞춘다
		jdbc.update("update app_update_rules set created_at = (select min(created_at) from app_update_rules)")
		forceRules.invalidate()
		app(get("$API/me")).andExpect(jsonPath("$.details.ruleId").value("rule-a"))
	}

	@Test
	fun `R_20_규칙을_30초까지_캐시한다`() {
		forceRule()
		app(get("$API/me")).andExpect(status().isUpgradeRequired)

		// 서버 밖에서(관리자 API를 거치지 않고) 지우면 30초까지는 이전 값으로 판단한다
		jdbc.update("delete from app_update_rules")
		clock.advance(Duration.ofSeconds(29))
		app(get("$API/me")).andExpect(status().isUpgradeRequired)
		clock.advance(Duration.ofSeconds(2))
		app(get("$API/me")).andExpect(status().isUnauthorized)
	}

	@Test
	fun `R_20_관리자가_규칙을_바꾸면_바로_반영한다`() {
		forceRule()
		app(get("$API/me")).andExpect(status().isUpgradeRequired)

		adminDelete("app-update-rules/force-1").andExpect(status().isNoContent)

		app(get("$API/me")).andExpect(status().isUnauthorized)
	}
}
