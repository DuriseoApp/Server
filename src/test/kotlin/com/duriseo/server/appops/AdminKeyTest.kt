package com.duriseo.server.appops

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.TestClockConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 관리자 키 (R-19, NFR-SEC-04, API.md 3.7) */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class AdminKeyTest : AppOpsTestSupport() {

	@Test
	fun `R_19_키가_맞으면_허용`() {
		mockMvc.perform(get("$API/admin/app-notices").header("X-Admin-Key", ADMIN_KEY))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.items.length()").value(0))
		// access 토큰 없이도 쓴다
		putNotice("n-1", VALID_NOTICE).andExpect(status().isOk)
	}

	@Test
	fun `R_19_키가_없거나_틀리면_401`() {
		for (request in listOf(
			get("$API/admin/app-notices"),
			get("$API/admin/app-notices").header("X-Admin-Key", ""),
			get("$API/admin/app-notices").header("X-Admin-Key", "wrong-key"),
			get("$API/admin/app-notices").header("X-Admin-Key", "$ADMIN_KEY "),
			get("$API/admin/app-update-rules").header("X-Admin-Key", ADMIN_KEY.uppercase()),
		)) {
			mockMvc.perform(request)
				.andExpect(status().isUnauthorized)
				.andExpect(jsonPath("$.code").value("ADMIN_KEY_INVALID"))
		}
		mockMvc.perform(
			put("$API/admin/app-notices/n-1").header("X-Admin-Key", "wrong-key").contentType(MediaType.APPLICATION_JSON).content(VALID_NOTICE),
		).andExpect(status().isUnauthorized)
		mockMvc.perform(delete("$API/admin/app-notices/n-1")).andExpect(status().isUnauthorized)
		// 아무것도 바꾸지 않는다
		assertEquals(0, jdbc.queryForObject("select count(*) from app_notices", Int::class.java))
	}

	@Test
	fun `R_19_키가_틀리면_본문검사보다_먼저_401`() {
		// 본문이 틀렸고 id 형식도 틀렸지만 VALIDATION_FAILED가 아니라 401이다
		mockMvc.perform(
			put("$API/admin/app-notices/BAD.ID").header("X-Admin-Key", "wrong-key").contentType(MediaType.APPLICATION_JSON).content("{}"),
		).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("ADMIN_KEY_INVALID"))
		// 본문을 읽을 수 없어도 같다
		mockMvc.perform(
			put("$API/admin/app-update-rules/r-1").header("X-Admin-Key", "wrong-key").contentType(MediaType.APPLICATION_JSON).content("not json"),
		).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("ADMIN_KEY_INVALID"))
		// 없는 경로도 경로 존재를 알려 주지 않는다. 키가 맞으면 404
		mockMvc.perform(get("$API/admin/nothing").header("X-Admin-Key", "wrong-key"))
			.andExpect(status().isUnauthorized)
		mockMvc.perform(get("$API/admin/nothing").header("X-Admin-Key", ADMIN_KEY))
			.andExpect(status().isNotFound)
			.andExpect(jsonPath("$.code").value("NOT_FOUND"))
	}

	@Test
	fun `R_19_메서드가_틀린_요청도_키가_틀리면_401_맞으면_404`() {
		val wrong = listOf(post("$API/admin/app-notices/x"), delete("$API/admin/app-notices"), put("$API/admin/app-notice-images/x"))
		for (request in wrong) {
			mockMvc.perform(request.header("X-Admin-Key", "wrong-key"))
				.andExpect(status().isUnauthorized)
				.andExpect(jsonPath("$.code").value("ADMIN_KEY_INVALID"))
		}
		for (request in listOf(post("$API/admin/app-notices/x"), delete("$API/admin/app-notices"))) {
			mockMvc.perform(request.header("X-Admin-Key", ADMIN_KEY))
				.andExpect(status().isNotFound)
				.andExpect(jsonPath("$.code").value("NOT_FOUND"))
		}
	}

	@Test
	fun `R_19_인코딩과_슬래시로_경로를_숨겨도_키가_틀리면_401`() {
		for (path in listOf(
			"/api/v1/%61dmin/app-notices", "/api/v1/admin/", "/api/v1//admin/app-notices", "/api/v1/./admin/app-notices",
			"/api/v1/x/../admin/app-notices", "/api/v1/ADMIN/app-notices", "/api/v1/admin",
		)) {
			mockMvc.perform(get(URI.create(path)))
				.andExpect(status().isUnauthorized)
				.andExpect(jsonPath("$.code").value("ADMIN_KEY_INVALID"))
		}
	}

	@Test
	fun `R_19_관리자_경로_판단은_정규화한_경로로_한다`() {
		assertTrue(com.duriseo.server.appops.api.AdminKeyFilter.isAdminPath("/api/v1/admin"))
		assertTrue(com.duriseo.server.appops.api.AdminKeyFilter.isAdminPath("/api/v1/admin/app-notices"))
		assertTrue(com.duriseo.server.appops.api.AdminKeyFilter.isAdminPath("//api/v1//admin//x/"))
		assertFalse(com.duriseo.server.appops.api.AdminKeyFilter.isAdminPath("/api/v1/administrator"))
		assertFalse(com.duriseo.server.appops.api.AdminKeyFilter.isAdminPath("/api/v1/app-notices"))
		assertFalse(com.duriseo.server.appops.api.AdminKeyFilter.isAdminPath("/api/v1/me"))
	}

	@Test
	fun `R_19_키가_공백뿐이면_꺼진_것으로_본다`() {
		// 설정 값이 공백뿐인 경우의 판단은 AdminKeyDisabledTest가 본다. 여기서는 공백이 붙은 헤더가 통과하지 않는 것만 확인한다
		mockMvc.perform(get("$API/admin/app-notices").header("X-Admin-Key", " $ADMIN_KEY"))
			.andExpect(status().isUnauthorized)
	}
}
