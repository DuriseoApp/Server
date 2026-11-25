package com.duriseo.server.appops

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.storage.NoticeImageKeys
import com.duriseo.server.storage.ProfileImageStorage
import com.duriseo.server.support.TestClockConfig
import com.duriseo.server.support.TestImages
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals

/** `PUBLIC_BASE_URL`이 비어 있으면 서버는 뜨고 업로드만 저장하지 않고 500이다 (진행 기록 단위 8 결정 2) */
@SpringBootTest(properties = ["duriseo.public-base-url="])
@Import(TestcontainersConfig::class, TestClockConfig::class)
class AppNoticeImageNoBaseUrlTest : AppOpsTestSupport() {

	@Autowired
	private lateinit var storage: ProfileImageStorage

	@Test
	fun `D_181_PUBLIC_BASE_URL이_비면_업로드는_저장하지_않고_500`() {
		val request = multipart(HttpMethod.POST, "$API/admin/app-notice-images")
			.file(MockMultipartFile("file", "a.png", "image/png", TestImages.png(TestImages.solid(10, 10))))
			.header("X-Admin-Key", ADMIN_KEY)

		mockMvc.perform(request).andExpect(status().isInternalServerError).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))

		val keys = mutableListOf<String>()
		storage.list(NoticeImageKeys.PREFIX) { keys += it.key }
		assertEquals(0, keys.size)
	}
}
