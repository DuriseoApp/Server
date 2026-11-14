package com.duriseo.server.storage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.nio.file.attribute.FileTime
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 로컬 디스크 저장소 (API.md 8장, 개발 환경). 임시 폴더에서 확인한다 */
class LocalDiskProfileImageStorageTest {

	@TempDir
	lateinit var dir: Path

	private val userId = UUID.randomUUID()
	private fun key(version: String = UUID.randomUUID().toString()) = ProfileImageKeys.objectKey(userId, version)

	private fun listed(storage: ProfileImageStorage, prefix: String = ProfileImageKeys.PREFIX): List<StoredObject> =
		buildList { storage.list(prefix) { add(it) } }

	@Test
	fun `NFR-FILE-01 저장한 사진을 같은 키로 읽고 지우면 없어진다`() {
		val storage = LocalDiskProfileImageStorage(dir)
		val key = key()

		storage.put(key, byteArrayOf(1, 2, 3))

		assertContentEquals(byteArrayOf(1, 2, 3), storage.get(key)!!.use { it.readAllBytes() })
		storage.delete(key)
		assertNull(storage.get(key))
		storage.delete(key) // 없는 키를 지워도 오류가 아니다
	}

	@Test
	fun `NFR-FILE-01 같은 키로 다시 저장하면 덮어쓴다`() {
		val storage = LocalDiskProfileImageStorage(dir)
		val key = key()

		storage.put(key, byteArrayOf(1))
		storage.put(key, byteArrayOf(2, 2))

		assertContentEquals(byteArrayOf(2, 2), storage.get(key)!!.use { it.readAllBytes() })
		assertEquals(1, listed(storage).size)
	}

	@Test
	fun `ERD 4_4 목록은 접두어 아래 객체를 키와 마지막 수정 시각으로 돌려주고 쓰다 만 임시 파일도 포함한다`() {
		val storage = LocalDiskProfileImageStorage(dir)
		val first = key()
		val second = ProfileImageKeys.objectKey(UUID.randomUUID(), UUID.randomUUID().toString())
		storage.put(first, byteArrayOf(1))
		storage.put(second, byteArrayOf(2))
		val old = Instant.parse("2026-01-01T00:00:00Z")
		Files.setLastModifiedTime(dir.resolve(first), FileTime.from(old))
		val temp = dir.resolve(first).resolveSibling(".half-written.tmp")
		Files.write(temp, byteArrayOf(9))
		val tempKey = dir.relativize(temp).joinToString("/")

		val all = listed(storage)

		assertEquals(setOf(first, second, tempKey), all.map { it.key }.toSet())
		assertEquals(old, all.single { it.key == first }.lastModified)
		assertTrue(all.single { it.key == second }.lastModified.isAfter(Instant.now().minus(Duration.ofMinutes(5))))
		assertEquals(setOf(first, tempKey), listed(storage, "profile/$userId/").map { it.key }.toSet())
	}

	@Test
	fun `NFR-FILE-01 아무것도 저장하지 않았으면 목록은 비어 있다`() {
		assertEquals(emptyList(), listed(LocalDiskProfileImageStorage(dir.resolve("not-created-yet"))))
	}

	@Test
	fun `NFR-FILE-01 저장소 밖으로 나가는 키는 거부한다`() {
		val storage = LocalDiskProfileImageStorage(dir)

		assertFailsWith<IllegalArgumentException> { storage.put("../escape.jpg", byteArrayOf(1)) }
		assertFailsWith<IllegalArgumentException> { storage.get("profile/../../escape.jpg") }
	}
}
