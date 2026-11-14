package com.duriseo.server.user

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.job.CleanupJob
import com.duriseo.server.config.DuriseoProperties.StorageType
import com.duriseo.server.job.CleanupStore
import com.duriseo.server.storage.StoredObject
import com.duriseo.server.storage.ProfileImageKeys
import com.duriseo.server.storage.ProfileImageStorage
import com.duriseo.server.support.IntegrationTestSupport
import com.duriseo.server.support.TestClockConfig
import com.duriseo.server.support.TestImages
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 프로필 사진 파일의 탈퇴 삭제와 정리 작업 8단계 (ERD 4.3·4.4, NFR-FILE-01, NFR-PRIV-02, API.md 8장).
 * 저장소는 임시 폴더의 로컬 디스크이고, 파일의 마지막 수정 시각은 테스트 시계(`MutableClock`) 기준으로 직접 맞춘다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class ProfileImageCleanupTest : IntegrationTestSupport() {

	@Autowired
	private lateinit var storage: ProfileImageStorage

	@Autowired
	private lateinit var job: CleanupJob

	@Autowired
	private lateinit var cleanupStore: CleanupStore

	@BeforeEach
	fun clearStorage() {
		val keys = mutableListOf<String>()
		storage.list(ProfileImageKeys.PREFIX) { keys += it.key }
		keys.forEach { storage.delete(it) }
	}

	private fun uploadPhoto(token: String) {
		val bytes = TestImages.jpeg(TestImages.solid(300, 300))
		val request = multipart(HttpMethod.PUT, "$API/me/profile-image").file(MockMultipartFile("file", "p.jpg", "image/jpeg", bytes))
		request.header("Authorization", "Bearer $token")
		mockMvc.perform(request).andExpect(status().isNoContent)
	}

	private fun filesOf(userId: UUID): List<String> {
		val keys = mutableListOf<String>()
		storage.list("${ProfileImageKeys.PREFIX}$userId/") { keys += it.key }
		return keys.sorted()
	}

	private fun allFiles(): Set<String> {
		val keys = mutableSetOf<String>()
		storage.list(ProfileImageKeys.PREFIX) { keys += it.key }
		return keys
	}

	/** [userId]의 사진 [version]을 DB가 가리키게 하고(참조 있음) 파일을 만든다. 마지막 수정 시각은 [modified]다 */
	private fun putFile(userId: UUID, version: String, modified: Instant, referenced: Boolean): String {
		val key = ProfileImageKeys.objectKey(userId, version)
		storage.put(key, byteArrayOf(1, 2, 3))
		Files.setLastModifiedTime(Path.of(properties.storage.localDir).toAbsolutePath().normalize().resolve(key), FileTime.from(modified))
		if (referenced) jdbc.update("update users set profile_image_key = ? where id = ?", version, userId)
		return key
	}

	private fun newVersion() = UUID.randomUUID().toString()

	@Test
	fun `ERD 4_4 쓰다 만 임시 파일도 24시간이 지나면 지운다`() {
		val owner = insertUser("MEMBER", "주인")
		val now = clock.instant()
		val key = putFile(owner, newVersion(), now, referenced = true)
		val root = Path.of(properties.storage.localDir).toAbsolutePath().normalize()
		val oldTemp = root.resolve(key).resolveSibling(".old.tmp")
		val recentTemp = root.resolve(key).resolveSibling(".recent.tmp")
		Files.write(oldTemp, byteArrayOf(1))
		Files.write(recentTemp, byteArrayOf(1))
		Files.setLastModifiedTime(oldTemp, FileTime.from(now.minus(Duration.ofHours(48))))
		Files.setLastModifiedTime(recentTemp, FileTime.from(now.minus(Duration.ofHours(1))))

		job.run()

		assertTrue(!Files.exists(oldTemp))
		assertTrue(Files.exists(recentTemp))
		assertTrue(key in allFiles())
	}

	@Test
	fun `ERD 4_4 저장소가 S3이면 cleanup-enabled가 true일 때만 사진 정리를 돈다`() {
		val listCalls = AtomicInteger()
		val fake = object : ProfileImageStorage {
			override fun put(key: String, bytes: ByteArray) = Unit
			override fun get(key: String): InputStream? = null
			override fun delete(key: String) = Unit
			override fun list(prefix: String, action: (StoredObject) -> Unit) {
				listCalls.incrementAndGet()
			}
		}
		fun jobWith(type: StorageType, enabled: Boolean) = CleanupJob(
			cleanupStore, fake, properties.copy(storage = properties.storage.copy(type = type, cleanupEnabled = enabled)), clock,
		)

		jobWith(StorageType.S3, enabled = false).run()
		assertEquals(0, listCalls.get(), "S3 + cleanup-enabled=false must not list (another environment could point at the prod bucket)")

		jobWith(StorageType.S3, enabled = true).run()
		assertEquals(1, listCalls.get())

		// 로컬 디스크는 플래그와 관계없이 돈다
		jobWith(StorageType.LOCAL, enabled = false).run()
		assertEquals(2, listCalls.get())
	}

	@Test
	fun `FR-AUTH-06 탈퇴하면 프로필 사진 파일을 바로 지운다`() {
		val member = startMember()
		uploadPhoto(member.accessToken)
		assertEquals(1, filesOf(member.userId).size)

		deleteMe(member.accessToken).andExpect(status().isNoContent)

		assertEquals(emptyList(), filesOf(member.userId))
		assertNull(jdbc.queryForObject("select profile_image_key from users where id = ?", String::class.java, member.userId))
	}

	@Test
	fun `FR-AUTH-06 게스트 정리로 지워지는 게스트의 사진 파일도 바로 지운다`() {
		val guest = startGuest()
		uploadPhoto(guest.accessToken)
		assertEquals(1, filesOf(guest.userId).size)
		// 게스트의 refresh 토큰(365일)이 만료돼 정리 대상이 된다 (ERD 4.4)
		clock.advance(Duration.ofDays(366))

		assertTrue(cleanupStore.eraseGuest(guest.userId, clock.instant()))

		assertEquals(emptyList(), filesOf(guest.userId))
	}

	@Test
	fun `ERD 4_4 참조되지 않고 24시간 지난 사진 파일만 지운다`() {
		val owner = insertUser("MEMBER", "주인")
		val other = insertUser("MEMBER", "다른이")
		val now = clock.instant()
		val old = now.minus(Duration.ofHours(48))
		val recent = now.minus(Duration.ofHours(1))
		val justUnder = now.minus(Duration.ofHours(23))

		// 참조 중인 파일은 오래됐어도 남긴다
		val referenced = putFile(owner, newVersion(), old, referenced = true)
		// 참조 없음 + 24시간 지남 → 지운다 (교체·삭제·탈퇴가 지우지 못하고 남긴 파일)
		val orphanOld = putFile(owner, newVersion(), old, referenced = false)
		val orphanOfOther = putFile(other, newVersion(), old, referenced = false)
		// 참조 없음 + 24시간이 안 됨 → 남긴다 (방금 올라와 아직 키를 갱신하는 중일 수 있다)
		val orphanRecent = putFile(owner, newVersion(), recent, referenced = false)
		val orphanJustUnder = putFile(other, newVersion(), justUnder, referenced = false)

		job.run()

		assertEquals(setOf(referenced, orphanRecent, orphanJustUnder), allFiles())
		assertTrue(orphanOld !in allFiles() && orphanOfOther !in allFiles())

		// 24시간이 지나면 방금 남겨 둔 파일도 지우고, 참조 중인 파일은 계속 남긴다
		clock.advance(Duration.ofHours(30))
		job.run()
		assertEquals(setOf(referenced), allFiles())
		// 다시 돌려도 같다
		job.run()
		assertEquals(setOf(referenced), allFiles())
	}
}
