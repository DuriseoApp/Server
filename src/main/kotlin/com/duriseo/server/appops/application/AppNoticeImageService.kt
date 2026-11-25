package com.duriseo.server.appops.application

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.config.DuriseoProperties
import com.duriseo.server.storage.NoticeImageInspector
import com.duriseo.server.storage.NoticeImageKeys
import com.duriseo.server.storage.ProfileImageStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/** 읽은 공지 이미지: 바이트와 `Content-Type` */
class NoticeImageContent(val bytes: ByteArray, val contentType: String)

/**
 * 공지 이미지 올리기·읽기·지우기 (D-181, API.md 3.7, 8.2). DB에 행이 없고 스토리지 `notice/` 접두어에만 있다.
 * 트랜잭션과 무관하다. 자동으로 지우지 않는다 (ERD DB-21): 지우려면 관리자 API로만 지운다.
 */
@Service
class AppNoticeImageService(
	private val storage: ProfileImageStorage,
	private val inspector: NoticeImageInspector,
	properties: DuriseoProperties,
) {

	private val log = LoggerFactory.getLogger(javaClass)
	private val baseUrl = properties.publicBaseUrl.trim().trimEnd('/')

	init {
		if (baseUrl.isEmpty()) {
			log.warn("PUBLIC_BASE_URL is empty: POST /admin/app-notice-images will answer 500 until it is set")
		}
	}

	/**
	 * `POST /admin/app-notice-images`: 1MB 초과는 413, PNG·JPEG·WebP가 아니거나 디코드 실패는 415. 원본 그대로
	 * `notice/{uuid}.{ext}`에 저장하고 `url`을 돌려준다. `PUBLIC_BASE_URL`이 비어 있으면 저장하지 않고 500이다.
	 */
	fun upload(size: Long, read: () -> ByteArray): String {
		if (size > MAX_BYTES) throw ApiException(ErrorCode.IMAGE_TOO_LARGE, "image is larger than 1MB")
		val bytes = read()
		if (bytes.size > MAX_BYTES) throw ApiException(ErrorCode.IMAGE_TOO_LARGE, "image is larger than 1MB")
		val extension = inspector.inspect(bytes)
		if (baseUrl.isEmpty()) throw ApiException(ErrorCode.INTERNAL_ERROR, "PUBLIC_BASE_URL is not set")
		val fileName = NoticeImageKeys.fileName(UUID.randomUUID(), extension)
		storage.put(NoticeImageKeys.objectKey(fileName), bytes)
		return "$baseUrl/api/v1/app-notice-images/$fileName"
	}

	/** `GET /app-notice-images/{fileName}`: 형식이 틀리면 저장소를 찾지 않고 404, 없어도 404 */
	fun read(fileName: String): NoticeImageContent {
		if (!NoticeImageKeys.isValidFileName(fileName)) throw ApiException(ErrorCode.NOT_FOUND)
		val stream = storage.get(NoticeImageKeys.objectKey(fileName)) ?: throw ApiException(ErrorCode.NOT_FOUND)
		val bytes = stream.use { it.readAllBytes() }
		return NoticeImageContent(bytes, NoticeImageKeys.contentType(fileName))
	}

	/** `DELETE /admin/app-notice-images/{fileName}`: 없거나 형식이 틀리면 404. 이 이미지를 쓰는 팝업이 있어도 막지 않는다 */
	fun delete(fileName: String) {
		if (!NoticeImageKeys.isValidFileName(fileName)) throw ApiException(ErrorCode.NOT_FOUND)
		val key = NoticeImageKeys.objectKey(fileName)
		(storage.get(key) ?: throw ApiException(ErrorCode.NOT_FOUND)).close()
		storage.delete(key)
	}

	companion object {
		const val MAX_BYTES = 1024 * 1024
	}
}
