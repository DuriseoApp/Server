package com.duriseo.server.user.api

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.FieldError
import com.duriseo.server.common.api.FieldErrorDetails
import com.duriseo.server.common.api.FieldErrorReason
import com.duriseo.server.common.web.AuthenticatedUser
import com.duriseo.server.common.web.CurrentUser
import com.duriseo.server.user.application.ProfileImageService
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

/** 프로필 사진 API (API.md 3.1, 8장). 권한(R-11)과 처리는 서비스가 한다. 한도 초과(413)·형식 오류(415)는 `ApiException`이다 */
@RestController
class ProfileImageController(private val profileImageService: ProfileImageService) {

	/** 올리기·교체 (FR-AUTH-07 AC3). multipart 필드 `file` 하나, 204. `file`이 없으면 400 `VALIDATION_FAILED`(`file`, `REQUIRED`) */
	@PutMapping("/me/profile-image")
	fun upload(
		@CurrentUser requester: AuthenticatedUser,
		@RequestParam("file", required = false) file: MultipartFile?,
	): ResponseEntity<Void> {
		if (file == null) {
			throw ApiException(
				ErrorCode.VALIDATION_FAILED, "file: REQUIRED",
				FieldErrorDetails(listOf(FieldError("file", FieldErrorReason.REQUIRED))),
			)
		}
		profileImageService.upload(requester.userId, file.size) { file.bytes }
		return ResponseEntity.noContent().build()
	}

	/** 삭제 (FR-AUTH-07 AC5). 사진이 없어도 204 (멱등) */
	@DeleteMapping("/me/profile-image")
	fun delete(@CurrentUser requester: AuthenticatedUser): ResponseEntity<Void> {
		profileImageService.delete(requester.userId)
		return ResponseEntity.noContent().build()
	}

	/** 조회 (R-11). `image/jpeg`를 그대로 흘려 보낸다. 사진이 없거나 권한이 없으면 404 `NOT_FOUND` */
	@GetMapping("/users/{id}/profile-image")
	fun get(@CurrentUser requester: AuthenticatedUser, @PathVariable("id") id: String): ResponseEntity<InputStreamResource> {
		val userId = try {
			UUID.fromString(id)
		} catch (e: IllegalArgumentException) {
			throw ApiException(ErrorCode.NOT_FOUND, "not found")
		}
		val stream = profileImageService.open(requester.userId, userId)
		return ResponseEntity.ok()
			.contentType(MediaType.IMAGE_JPEG)
			// 키(uuid)가 바뀔 때마다 앱이 `?v=<version>`으로 새로 받는다
			.header(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable")
			.body(InputStreamResource(stream))
	}
}
