package com.duriseo.server.storage

import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * S3 저장소가 SDK를 올바르게 부르는지 가짜 클라이언트(`Proxy`)로 확인한다 (네트워크 없음). 목록은 SDK의 페이지 넘김 객체(`ListObjectsV2Iterable`)를
 * 가짜로 만들 수 없어 로컬 디스크와 같은 인터페이스 테스트(`LocalDiskProfileImageStorageTest`)가 대신하고,
 * 실제 S3는 운영 배포 뒤 사람이 확인한다 (docs/SETUP.md 3.8).
 */
class S3ProfileImageStorageTest {

	private val calls = mutableListOf<Pair<String, Any?>>()
	private var onGet: (GetObjectRequest) -> Any? = { null }

	/** 부른 메서드 이름과 첫 인자를 기록하는 S3Client. `getObject`만 [onGet]으로 답을 정한다 */
	private val client: S3Client = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(S3Client::class.java)) { _, method: Method, args ->
		calls += method.name to args?.firstOrNull()
		when (method.name) {
			"getObject" -> onGet(args!![0] as GetObjectRequest)
			else -> null
		}
	} as S3Client

	private val storage = S3ProfileImageStorage(client, "fake-profile-bucket")

	@Test
	fun `NFR-FILE-01 저장은 버킷과 키와 image jpeg로 올린다`() {
		storage.put("profile/u/v.jpg", byteArrayOf(1, 2, 3))

		val request = calls.single { it.first == "putObject" }.second as PutObjectRequest
		assertEquals("fake-profile-bucket", request.bucket())
		assertEquals("profile/u/v.jpg", request.key())
		assertEquals("image/jpeg", request.contentType())
	}

	@Test
	fun `NFR-FILE-01 없는 키를 읽으면 null이고 다른 오류는 그대로 던진다`() {
		onGet = { throw NoSuchKeyException.builder().message("fake").build() }
		assertNull(storage.get("profile/u/missing.jpg"))

		onGet = { throw IllegalStateException("fake failure") }
		assertFailsWith<IllegalStateException> { storage.get("profile/u/v.jpg") }

		val request = calls.first { it.first == "getObject" }.second as GetObjectRequest
		assertEquals("fake-profile-bucket", request.bucket())
		assertEquals("profile/u/missing.jpg", request.key())
	}

	@Test
	fun `NFR-FILE-01 삭제는 버킷과 키로 부른다`() {
		storage.delete("profile/u/v.jpg")

		val request = calls.single { it.first == "deleteObject" }.second as DeleteObjectRequest
		assertEquals("fake-profile-bucket", request.bucket())
		assertEquals("profile/u/v.jpg", request.key())
	}

	@Test
	fun `NFR-FILE-01 버킷 이름 없이는 S3 저장소를 만들 수 없다`() {
		assertFailsWith<IllegalArgumentException> { S3ProfileImageStorage.create("", "ap-northeast-2") }
	}
}
