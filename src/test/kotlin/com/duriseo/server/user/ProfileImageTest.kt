package com.duriseo.server.user

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.storage.ProfileImageKeys
import com.duriseo.server.storage.ProfileImageProcessor
import com.duriseo.server.storage.ProfileImageStorage
import com.duriseo.server.support.LogCapture
import com.duriseo.server.support.NotificationTestSupport
import com.duriseo.server.support.TestClockConfig
import com.duriseo.server.support.TestImages
import com.jayway.jsonpath.JsonPath
import org.hamcrest.Matchers.nullValue
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.awt.image.BufferedImage
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 프로필 사진 API (FR-AUTH-07 AC3·AC5·AC7, NFR-FILE-01, D-147, D-153, R-11, API.md 3.1·3.2·6장·8장).
 * 저장소는 임시 폴더의 로컬 디스크다 (`application.properties`). 이미지는 코드로 만든다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class ProfileImageTest : NotificationTestSupport() {

	@Autowired
	private lateinit var storage: ProfileImageStorage

	@BeforeEach
	fun clearStorage() {
		val keys = mutableListOf<String>()
		storage.list(ProfileImageKeys.PREFIX) { keys += it.key }
		keys.forEach { storage.delete(it) }
	}

	// ── 도구 ────────────────────────────────────────────

	private fun photo(width: Int = 800, height: Int = 600): ByteArray = TestImages.jpeg(TestImages.solid(width, height))

	/** `PUT /me/profile-image`. [bytes]가 null이면 `file` 부분 없이 보낸다 */
	private fun putPhoto(
		token: String,
		bytes: ByteArray?,
		fileName: String = "photo.jpg",
		contentType: String = "image/jpeg",
	): ResultActions {
		val request = multipart(HttpMethod.PUT, "$API/me/profile-image")
		if (bytes != null) request.file(MockMultipartFile("file", fileName, contentType, bytes))
		request.header("Authorization", "Bearer $token")
		return mockMvc.perform(request)
	}

	private fun deletePhoto(token: String): ResultActions =
		mockMvc.perform(delete("$API/me/profile-image").header("Authorization", "Bearer $token"))

	private fun getPhoto(token: String, userId: Any): ResultActions =
		mockMvc.perform(get("$API/users/$userId/profile-image").header("Authorization", "Bearer $token"))

	private fun versionOf(userId: UUID): String? =
		jdbc.queryForObject("select profile_image_key from users where id = ?", String::class.java, userId)

	private fun filesOf(userId: UUID): List<String> {
		val keys = mutableListOf<String>()
		storage.list("${ProfileImageKeys.PREFIX}$userId/") { keys += it.key }
		return keys.sorted()
	}

	private fun fileOf(userId: UUID, version: String) = ProfileImageKeys.objectKey(userId, version)

	private fun syncSpace(spaceId: UUID) = mapOf("type" to "SYNC", "resource" to "SPACE", "id" to spaceId.toString(), "action" to "UPDATED")

	// ── PUT /me/profile-image ───────────────────────────

	@Test
	fun `FR-AUTH-07 AC3 사진을 올리면 204이고 profileImageVersion이 새 값이며 이전 파일이 지워진다`() {
		val me = startMember()

		putPhoto(me.accessToken, photo()).andExpect(status().isNoContent).andExpect(content().string(""))
		val first = versionOf(me.userId)
		assertNotNull(first)
		UUID.fromString(first) // 컬럼에는 uuid만 있다
		assertEquals(listOf(fileOf(me.userId, first)), filesOf(me.userId))
		getMe(me.accessToken)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.hasProfileImage").value(true))
			.andExpect(jsonPath("$.profileImageVersion").value(first))

		putPhoto(me.accessToken, photo(400, 400)).andExpect(status().isNoContent)
		val second = versionOf(me.userId)
		assertNotNull(second)
		assertNotEquals(first, second)
		// 이전 파일은 커밋 뒤 지워졌고 새 파일만 남았다
		assertEquals(listOf(fileOf(me.userId, second)), filesOf(me.userId))
		getMe(me.accessToken).andExpect(jsonPath("$.profileImageVersion").value(second))
	}

	@Test
	fun `FR-AUTH-07 AC3 올린 사진은 긴 변 512px JPEG로 저장된다`() {
		val me = startMember()

		putPhoto(me.accessToken, TestImages.png(TestImages.solid(2000, 1000)), "photo.png", "image/png").andExpect(status().isNoContent)

		val stored = storage.get(fileOf(me.userId, versionOf(me.userId)!!))!!.use { it.readAllBytes() }
		assertTrue(TestImages.isJpeg(stored))
		TestImages.decode(stored).let { assertEquals(512 to 256, it.width to it.height) }
	}

	@Test
	fun `FR-AUTH-07 AC3 file이 없으면 400 VALIDATION_FAILED (file REQUIRED)`() {
		val me = startMember()

		putPhoto(me.accessToken, null)
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.details.fields[0].field").value("file"))
			.andExpect(jsonPath("$.details.fields[0].reason").value("REQUIRED"))
		assertNull(versionOf(me.userId))
	}

	@Test
	fun `FR-AUTH-07 AC7 10MB를 넘으면 413 IMAGE_TOO_LARGE다`() {
		val me = startMember()

		putPhoto(me.accessToken, ByteArray(ProfileImageProcessor.MAX_BYTES + 1))
			.andExpect(status().isContentTooLarge)
			.andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"))
			.andExpect(jsonPath("$.details").value(nullValue()))
		assertNull(versionOf(me.userId))
		assertEquals(emptyList(), filesOf(me.userId))
	}

	@Test
	fun `FR-AUTH-07 AC7 25MP를 넘으면 413이다`() {
		val me = startMember()
		val over = TestImages.png(TestImages.image(5001, 5000, BufferedImage.TYPE_BYTE_GRAY))

		putPhoto(me.accessToken, over, "big.png", "image/png")
			.andExpect(status().isContentTooLarge)
			.andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"))
		assertNull(versionOf(me.userId))
	}

	@Test
	fun `FR-AUTH-07 AC7 이름만 jpg인 GIF나 깨진 파일은 415다`() {
		val me = startMember()

		putPhoto(me.accessToken, TestImages.gif(TestImages.solid(40, 40)), "photo.jpg", "image/jpeg")
			.andExpect(status().isUnsupportedMediaType)
			.andExpect(jsonPath("$.code").value("IMAGE_TYPE_NOT_ALLOWED"))
		putPhoto(me.accessToken, "not an image at all".toByteArray(), "photo.png", "image/png")
			.andExpect(status().isUnsupportedMediaType)
			.andExpect(jsonPath("$.code").value("IMAGE_TYPE_NOT_ALLOWED"))
		assertNull(versionOf(me.userId))
		assertEquals(emptyList(), filesOf(me.userId))
	}

	@Test
	fun `FR-AUTH-07 AC7 확장자와 Content-Type이 틀려도 실제 PNG면 받는다`() {
		val me = startMember()

		putPhoto(me.accessToken, TestImages.png(TestImages.solid(60, 60)), "photo.gif", "application/octet-stream")
			.andExpect(status().isNoContent)
		assertNotNull(versionOf(me.userId))
	}

	@Test
	fun `FR-AUTH-07 AC3 키 갱신이 실패하면 방금 올린 파일을 지운다`() {
		val me = startMember()
		// 세션은 유효한데 사용자 행이 탈퇴 상태가 된 경우(드문 경합): 파일은 저장됐지만 키 갱신이 거부된다
		jdbc.update("update users set deleted_at = ? where id = ?", ts(clock.instant()), me.userId)

		putPhoto(me.accessToken, photo()).andExpect(status().isUnauthorized)

		assertEquals(emptyList(), filesOf(me.userId))
	}

	@Test
	fun `FR-AUTH-07 동시에 두 번 올려도 키는 하나이고 진 쪽 파일은 남지 않거나 정리 대상이다`() {
		val me = startMember()

		val statuses = inParallel(
			listOf(
				{ statusOf(putPhoto(me.accessToken, photo(900, 700))) },
				{ statusOf(putPhoto(me.accessToken, photo(700, 900))) },
			),
		)

		assertEquals(listOf(204, 204), statuses)
		val version = versionOf(me.userId)
		assertNotNull(version)
		// 진 쪽 파일은 이긴 쪽이 이전 파일로 지웠다. 혹시 남은 파일이 있더라도 DB가 가리키지 않아 정리 작업 대상이다
		val files = filesOf(me.userId)
		assertTrue(fileOf(me.userId, version) in files)
		assertEquals(listOf(fileOf(me.userId, version)), files)
	}

	// ── DELETE /me/profile-image ────────────────────────

	@Test
	fun `FR-AUTH-07 AC5 사진을 지우면 파일과 키가 지워지고 다시 지워도 204다`() {
		val me = startMember()
		putPhoto(me.accessToken, photo()).andExpect(status().isNoContent)
		assertEquals(1, filesOf(me.userId).size)

		deletePhoto(me.accessToken).andExpect(status().isNoContent).andExpect(content().string(""))

		assertNull(versionOf(me.userId))
		assertEquals(emptyList(), filesOf(me.userId))
		getMe(me.accessToken)
			.andExpect(jsonPath("$.hasProfileImage").value(false))
			.andExpect(jsonPath("$.profileImageVersion").value(nullValue()))
		// 멱등: 사진이 없어도 204
		deletePhoto(me.accessToken).andExpect(status().isNoContent)
	}

	// ── GET /users/{id}/profile-image (R-11) ────────────

	@Test
	fun `R-11 본인과 같은 공간 상대는 사진을 받는다`() {
		val couple = newCouple()
		putPhoto(couple.a.accessToken, photo()).andExpect(status().isNoContent)
		val stored = storage.get(fileOf(couple.a.userId, versionOf(couple.a.userId)!!))!!.use { it.readAllBytes() }

		for (token in listOf(couple.a.accessToken, couple.b.accessToken)) {
			val body = getPhoto(token, couple.a.userId)
				.andExpect(status().isOk)
				.andExpect(header().string("Content-Type", "image/jpeg"))
				.andExpect(header().string("Cache-Control", "private, max-age=31536000, immutable"))
				.andReturn().response.contentAsByteArray
			assertTrue(body.contentEquals(stored))
		}
	}

	@Test
	fun `R-11 다른 공간 사용자와 해제한 상대의 사진은 404다`() {
		val couple = newCouple()
		val stranger = startMember("낯선이")
		putPhoto(couple.a.accessToken, photo()).andExpect(status().isNoContent)

		getPhoto(stranger.accessToken, couple.a.userId).expectError(404, "NOT_FOUND")
		// 없는 사용자와 uuid가 아닌 값도 같은 404다
		getPhoto(stranger.accessToken, UUID.randomUUID()).expectError(404, "NOT_FOUND")
		getPhoto(stranger.accessToken, "not-a-uuid").expectError(404, "NOT_FOUND")

		unlinkCouple(couple.b.accessToken).andExpect(status().isNoContent)
		getPhoto(couple.b.accessToken, couple.a.userId).expectError(404, "NOT_FOUND")
		// 본인은 해제 뒤에도 자기 사진을 받는다
		getPhoto(couple.a.accessToken, couple.a.userId).andExpect(status().isOk)
	}

	@Test
	fun `R-11 사진이 없으면 404다`() {
		val couple = newCouple()

		getPhoto(couple.a.accessToken, couple.a.userId).expectError(404, "NOT_FOUND")
		getPhoto(couple.b.accessToken, couple.a.userId).expectError(404, "NOT_FOUND")
	}

	@Test
	fun `R-11 DB에는 키가 있는데 파일이 없으면 404이고 경고 로그를 남긴다`() {
		val me = startMember()
		val version = UUID.randomUUID().toString()
		jdbc.update("update users set profile_image_key = ? where id = ?", version, me.userId)

		LogCapture().use { logs ->
			getPhoto(me.accessToken, me.userId).expectError(404, "NOT_FOUND")
			assertTrue(logs.text().contains("profile image file is missing"), logs.text())
		}
	}

	@Test
	fun `R-11 로그인하지 않으면 사진을 받을 수 없다`() {
		val me = startMember()

		mockMvc.perform(get("$API/users/${me.userId}/profile-image")).andExpect(status().isUnauthorized)
	}

	// ── 응답 필드 ───────────────────────────────────────

	@Test
	fun `FR-AUTH-07 GET me와 GET space는 profileImageVersion을 준다`() {
		val couple = newCouple()
		// 사진이 없을 때는 null
		getMe(couple.a.accessToken)
			.andExpect(jsonPath("$.hasProfileImage").value(false))
			.andExpect(jsonPath("$.profileImageVersion").value(nullValue()))
			.andExpect(jsonPath("$.space.partner.hasProfileImage").value(false))
			.andExpect(jsonPath("$.space.partner.profileImageVersion").value(nullValue()))

		putPhoto(couple.a.accessToken, photo()).andExpect(status().isNoContent)
		val version = versionOf(couple.a.userId)!!

		getMe(couple.a.accessToken).andExpect(jsonPath("$.profileImageVersion").value(version))
		getMe(couple.b.accessToken)
			.andExpect(jsonPath("$.profileImageVersion").value(nullValue()))
			.andExpect(jsonPath("$.space.partner.id").value(couple.a.userId.toString()))
			.andExpect(jsonPath("$.space.partner.hasProfileImage").value(true))
			.andExpect(jsonPath("$.space.partner.profileImageVersion").value(version))
		for (token in listOf(couple.a.accessToken, couple.b.accessToken)) {
			val body = bodyOf(getSpace(token).andExpect(status().isOk))
			val members = JsonPath.read<List<Map<String, Any?>>>(body, "$.members")
			val a = members.single { it["userId"] == couple.a.userId.toString() }
			val b = members.single { it["userId"] == couple.b.userId.toString() }
			assertEquals(true, a["hasProfileImage"])
			assertEquals(version, a["profileImageVersion"])
			assertEquals(false, b["hasProfileImage"])
			assertTrue(b.containsKey("profileImageVersion") && b["profileImageVersion"] == null)
		}
	}

	// ── 동기화 신호 (6장) ───────────────────────────────

	@Test
	fun `6장 커플 공간에서 사진을 바꾸면 상대에게 SPACE UPDATED가 간다`() {
		val couple = newCouple()

		putPhoto(couple.a.accessToken, photo()).andExpect(status().isNoContent)
		assertEquals(listOf(syncSpace(couple.spaceId)), push.to(TOKEN_B).map { it.data })
		// 요청자 본인 기기에는 가지 않는다
		assertTrue(push.to(TOKEN_A).isEmpty())

		push.reset()
		deletePhoto(couple.a.accessToken).andExpect(status().isNoContent)
		assertEquals(listOf(syncSpace(couple.spaceId)), push.to(TOKEN_B).map { it.data })

		// 사진이 없는데 지우면 바뀐 게 없어 신호가 없다 (멱등 삭제)
		push.reset()
		deletePhoto(couple.a.accessToken).andExpect(status().isNoContent)
		assertTrue(push.to(TOKEN_B).isEmpty())
	}

	@Test
	fun `6장 개인 공간에서 사진을 바꾸면 신호가 없고 거부된 업로드도 신호가 없다`() {
		val couple = newCouple()
		val solo = startMember("혼자")
		registerDevice(solo, "fcm-token-solo-fake")
		push.reset()

		putPhoto(solo.accessToken, photo()).andExpect(status().isNoContent)
		putPhoto(couple.a.accessToken, "not an image".toByteArray()).andExpect(status().isUnsupportedMediaType)

		assertTrue(push.to("fcm-token-solo-fake").isEmpty())
		assertTrue(push.to(TOKEN_B).isEmpty())
	}
}
