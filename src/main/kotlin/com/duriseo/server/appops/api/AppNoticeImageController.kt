package com.duriseo.server.appops.api

import com.duriseo.server.appops.application.AppNoticeImageService
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.FieldError
import com.duriseo.server.common.api.FieldErrorDetails
import com.duriseo.server.common.api.FieldErrorReason
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/** 공지 이미지 API (API.md 3.7, 8.2). 업로드·삭제는 관리자 키(R-19), 조회는 로그인 없이 부른다 */
@RestController
class AppNoticeImageController(private val images: AppNoticeImageService) {

	/** 올리기: multipart 필드 `file` 하나, 201 `{url}`. `file`이 없으면 400 `VALIDATION_FAILED`(`file`, `REQUIRED`) */
	@PostMapping("/admin/app-notice-images")
	fun upload(@RequestParam("file", required = false) file: MultipartFile?): ResponseEntity<AppNoticeImageResponse> {
		if (file == null) {
			throw ApiException(
				ErrorCode.VALIDATION_FAILED, "file: REQUIRED",
				FieldErrorDetails(listOf(FieldError("file", FieldErrorReason.REQUIRED))),
			)
		}
		val url = images.upload(file.size) { file.bytes }
		return ResponseEntity.status(HttpStatus.CREATED).body(AppNoticeImageResponse(url))
	}

	@DeleteMapping("/admin/app-notice-images/{fileName}")
	fun delete(@PathVariable("fileName") fileName: String): ResponseEntity<Void> {
		images.delete(fileName)
		return ResponseEntity.noContent().build()
	}

	/**
	 * 조회: 이미지 바이트를 응답 출력에 직접 쓴다(`Accept` 협상이 끼지 않게 한다). 없거나 형식이 틀리면 쓰기 전에 404를 던진다.
	 * `Cache-Control: public, max-age=86400`, `X-Content-Type-Options: nosniff`.
	 */
	@GetMapping("/app-notice-images/{fileName}")
	fun get(@PathVariable("fileName") fileName: String, response: HttpServletResponse) {
		val image = images.read(fileName)
		response.status = HttpServletResponse.SC_OK
		response.contentType = image.contentType
		response.setHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
		response.setHeader("X-Content-Type-Options", "nosniff")
		response.setContentLength(image.bytes.size)
		response.outputStream.write(image.bytes)
	}
}
