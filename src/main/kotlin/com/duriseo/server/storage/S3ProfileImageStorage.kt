package com.duriseo.server.storage

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.InputStream

/**
 * Amazon S3 저장소 (운영, 서울 리전). 자격 증명은 SDK 기본 체인이 EC2 역할(인스턴스 프로파일)에서 받는다. 키를 코드·설정에 두지 않는다.
 * 권한은 `deploy/aws/duriseo-stack.yml`의 `ServerRole`: 사진 버킷의 `GetObject`·`PutObject`·`DeleteObject`·`ListBucket`.
 * `ListBucket` 권한이 있어야 없는 키를 읽을 때 403이 아니라 `NoSuchKey`(404)가 온다.
 */
class S3ProfileImageStorage(private val s3: S3Client, private val bucket: String) : ProfileImageStorage, AutoCloseable {

	override fun put(key: String, bytes: ByteArray) {
		s3.putObject(
			PutObjectRequest.builder().bucket(bucket).key(key).contentType("image/jpeg").build(),
			RequestBody.fromBytes(bytes),
		)
	}

	override fun get(key: String): InputStream? =
		try {
			s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())
		} catch (e: NoSuchKeyException) {
			null
		}

	override fun delete(key: String) {
		// 없는 키를 지워도 S3는 성공으로 답한다
		s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
	}

	override fun list(prefix: String, action: (StoredObject) -> Unit) {
		val request = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build()
		// 페이지 넘김(continuation token)은 paginator가 한다
		s3.listObjectsV2Paginator(request).contents().forEach { action(StoredObject(it.key(), it.lastModified())) }
	}

	override fun close() = s3.close()

	companion object {
		/** 서울 리전 등 [region]의 클라이언트. 자격 증명은 기본 체인(코드에 키 없음) */
		fun create(bucket: String, region: String): S3ProfileImageStorage {
			require(bucket.isNotBlank()) { "duriseo.storage.s3-bucket must be set when duriseo.storage.type=s3" }
			return S3ProfileImageStorage(S3Client.builder().region(Region.of(region)).build(), bucket)
		}
	}
}
