package com.duriseo.server.appops

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.storage.NoticeImageKeys
import com.duriseo.server.storage.ProfileImageStorage
import com.duriseo.server.support.TestClockConfig
import com.duriseo.server.support.TestImages
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Base64
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 공지 이미지 올리기·조회·삭제 (D-181, API.md 3.7, 8.2) */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class AppNoticeImageApiTest : AppOpsTestSupport() {

	@Autowired
	private lateinit var storage: ProfileImageStorage

	@BeforeEach
	fun clearNoticeFiles() {
		val keys = mutableListOf<String>()
		storage.list(NoticeImageKeys.PREFIX) { keys += it.key }
		keys.forEach { storage.delete(it) }
	}

	private fun upload(bytes: ByteArray?, key: String? = ADMIN_KEY, contentType: String = "application/octet-stream"): ResultActions {
		val request = multipart(HttpMethod.POST, "$API/admin/app-notice-images")
		if (bytes != null) request.file(MockMultipartFile("file", "image", contentType, bytes))
		if (key != null) request.header("X-Admin-Key", key)
		return mockMvc.perform(request)
	}

	private fun storedKeys(): List<String> {
		val keys = mutableListOf<String>()
		storage.list(NoticeImageKeys.PREFIX) { keys += it.key }
		return keys
	}

	private fun fileNameOf(result: ResultActions): String =
		JsonPath.read<String>(bodyOf(result), "$.url").substringAfterLast('/')

	@Test
	fun `D_181_PNG를_원본_그대로_저장하고_url을_준다`() {
		val png = TestImages.png(TestImages.quadrants(40, 30))

		val result = upload(png, contentType = "image/jpeg") // Content-Type이 거짓이어도 실제 디코드로 판정한다
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.matchesPattern("https://api\\.test\\.invalid/api/v1/app-notice-images/[0-9a-f-]{36}\\.png")))

		val fileName = fileNameOf(result)
		assertTrue(NoticeImageKeys.isValidFileName(fileName))
		assertEquals(listOf("notice/$fileName"), storedKeys())
		assertContentEquals(png, storage.get("notice/$fileName")!!.use { it.readAllBytes() })
	}

	@Test
	fun `D_181_JPEG는_jpg_WebP는_webp_확장자로_저장한다`() {
		val jpeg = TestImages.jpeg(TestImages.solid(20, 20))
		val webp = Base64.getDecoder().decode(TINY_WEBP)

		val jpegName = fileNameOf(upload(jpeg).andExpect(status().isCreated))
		val webpName = fileNameOf(upload(webp).andExpect(status().isCreated))

		assertTrue(jpegName.endsWith(".jpg"))
		assertTrue(webpName.endsWith(".webp"))
		assertContentEquals(webp, storage.get("notice/$webpName")!!.use { it.readAllBytes() })
	}

	@Test
	fun `D_181_조회는_로그인없이_Content_Type과_캐시_헤더를_준다`() {
		val png = TestImages.png(TestImages.solid(10, 10))
		val fileName = fileNameOf(upload(png).andExpect(status().isCreated))

		val response = mockMvc.perform(get("$API/app-notice-images/$fileName"))
			.andExpect(status().isOk)
			.andExpect(header().string("Content-Type", "image/png"))
			.andExpect(header().string("Cache-Control", "public, max-age=86400"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andReturn().response
		assertContentEquals(png, response.contentAsByteArray)
	}

	@Test
	fun `D_181_file이_없으면_400_file_REQUIRED`() {
		upload(null)
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.details.fields[0].field").value("file"))
			.andExpect(jsonPath("$.details.fields[0].reason").value("REQUIRED"))
	}

	@Test
	fun `D_181_1MB를_넘으면_413_정확히_1MB는_저장한다`() {
		val png = TestImages.png(TestImages.solid(10, 10))
		val exactly = png + ByteArray(1024 * 1024 - png.size) // 뒤에 덧붙은 바이트는 디코드에 영향이 없다

		upload(exactly + byteArrayOf(0)).andExpect(status().isContentTooLarge).andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"))
		assertEquals(0, storedKeys().size)
		upload(exactly).andExpect(status().isCreated)
		assertEquals(1, storedKeys().size)
	}

	@Test
	fun `D_181_PNG_JPEG_WebP가_아니거나_디코드에_실패하면_415`() {
		val png = TestImages.png(TestImages.quadrants(60, 60))
		val cases = listOf(
			"GIF" to TestImages.gif(TestImages.solid(10, 10)),
			"텍스트" to "not an image".toByteArray(),
			"빈 파일" to ByteArray(0),
			"잘린 PNG" to png.copyOf(png.size / 2),
		)
		for ((name, bytes) in cases) {
			upload(bytes)
				.andExpect(status().isUnsupportedMediaType)
				.andExpect(jsonPath("$.code").value("IMAGE_TYPE_NOT_ALLOWED"))
			assertEquals(0, storedKeys().size, name)
		}
	}

	@Test
	fun `D_181_키가_없거나_틀리면_401이고_저장하지_않는다`() {
		val png = TestImages.png(TestImages.solid(10, 10))

		upload(png, key = null).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("ADMIN_KEY_INVALID"))
		upload(png, key = "wrong-key").andExpect(status().isUnauthorized)
		assertEquals(0, storedKeys().size)
		mockMvc.perform(delete("$API/admin/app-notice-images/${UUID.randomUUID()}.png")).andExpect(status().isUnauthorized)
	}

	@Test
	fun `D_181_없거나_형식이_틀린_fileName은_404`() {
		val uuid = UUID.randomUUID()
		for (name in listOf("$uuid.png", "$uuid.gif", "$uuid", "${uuid.toString().uppercase()}.png", "abc.png", "$uuid.png.png", "..%2Fprofile", "%2e%2e")) {
			mockMvc.perform(get("$API/app-notice-images/$name"))
				.andExpect(status().isNotFound)
				.andExpect(jsonPath("$.code").value("NOT_FOUND"))
		}
	}

	@Test
	fun `D_181_Accept가_image만이어도_없는_이미지는_404_JSON_오류`() {
		val result = mockMvc.perform(get("$API/app-notice-images/${UUID.randomUUID()}.png").accept("image/*"))
			.andExpect(status().isNotFound)
			.andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/json")))
			.andExpect(jsonPath("$.code").value("NOT_FOUND"))
		assertNotNull(bodyOf(result))
		mockMvc.perform(get("$API/app-notice-images/bad-name").accept("image/png")).andExpect(status().isNotFound)
	}

	@Test
	fun `D_181_삭제는_204이고_없으면_404`() {
		val fileName = fileNameOf(upload(TestImages.png(TestImages.solid(10, 10))).andExpect(status().isCreated))
		// 이 이미지를 쓰는 팝업이 있어도 막지 않는다
		putNotice("n-1", """{"closable": true, "title": "t", "image": "https://api.test.invalid/api/v1/app-notice-images/$fileName"}""")
			.andExpect(status().isOk)

		adminDelete("app-notice-images/$fileName").andExpect(status().isNoContent)

		assertEquals(0, storedKeys().size)
		mockMvc.perform(get("$API/app-notice-images/$fileName")).andExpect(status().isNotFound)
		adminDelete("app-notice-images/$fileName").andExpect(status().isNotFound)
		adminDelete("app-notice-images/not-a-file-name").andExpect(status().isNotFound)
		assertNotNull(jdbc.queryForObject("select image from app_notices where id = 'n-1'", String::class.java))
	}

	private companion object {
		/** 1x1 무손실 WebP (잘 알려진 최소 샘플). 이 도구가 WebP를 쓰지 못해 고정 바이트로 둔다 */
		const val TINY_WEBP = "UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA=="
	}
}
