package com.duriseo.server.appops

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.TestClockConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 관리자 키 환경변수가 비어 있을 때 (R-19). 환경변수가 다른 컨텍스트라 `AdminKeyTest`와 클래스를 나눈다 */
@SpringBootTest(properties = ["duriseo.admin.api-key="])
@Import(TestcontainersConfig::class, TestClockConfig::class)
class AdminKeyDisabledTest : AppOpsTestSupport() {

	@Test
	fun `R_19_환경변수가_비면_404`() {
		// 키가 맞아 보이는 요청도, 키가 없는 요청도 모두 404 NOT_FOUND다
		for (request in listOf(
			get("$API/admin/app-notices"),
			get("$API/admin/app-notices").header("X-Admin-Key", ADMIN_KEY),
			get("$API/admin/app-notices").header("X-Admin-Key", ""),
			get("$API/admin/nothing").header("X-Admin-Key", ADMIN_KEY),
		)) {
			mockMvc.perform(request).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("NOT_FOUND"))
		}
		mockMvc.perform(
			put("$API/admin/app-notices/n-1").header("X-Admin-Key", ADMIN_KEY).contentType(MediaType.APPLICATION_JSON).content(VALID_NOTICE),
		).andExpect(status().isNotFound)
		// 공개 조회는 그대로 동작한다
		mockMvc.perform(get("$API/app-notices")).andExpect(status().isOk)
	}
}
