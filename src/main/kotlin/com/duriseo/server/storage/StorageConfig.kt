package com.duriseo.server.storage

import com.duriseo.server.config.DuriseoProperties
import com.duriseo.server.config.DuriseoProperties.StorageType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path

/** `duriseo.storage.type`으로 프로필 사진 저장소를 고른다 (API.md 8장). S3 클라이언트는 `s3`일 때만 만든다 */
@Configuration(proxyBeanMethods = false)
class StorageConfig {

	@Bean
	fun profileImageStorage(properties: DuriseoProperties): ProfileImageStorage {
		val storage = properties.storage
		return when (storage.type) {
			StorageType.LOCAL -> LocalDiskProfileImageStorage(Path.of(storage.localDir))
			StorageType.S3 -> S3ProfileImageStorage.create(storage.s3Bucket, storage.s3Region)
		}
	}
}
