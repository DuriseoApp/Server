package com.duriseo.server.notification.application

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.FieldError
import com.duriseo.server.common.api.FieldErrorDetails
import com.duriseo.server.common.api.FieldErrorReason
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

/** `GET /notifications` 커서 키: (`created_at`, `id`) (API.md 3.5, 3.3 "커서 정렬 키") */
data class NotificationCursorKey(val createdAt: Instant, val id: UUID)

/** 이미 검사한 `cursor`·`size` ([cursor]는 첫 페이지면 null) */
data class NotificationPageQuery(val cursor: NotificationCursorKey?, val size: Int)

/**
 * `GET /notifications`의 `cursor`·`size` 규칙 (API.md 1.1 "커서·`size` 공통 규칙").
 * `size`는 정수 1 ~ 100(생략하면 20), 아니면 (`size`, `INVALID`). `cursor`는 서버가 만든 불투명 문자열(이 구현은 정렬 키를 base64url로 감싼 것)이고
 * 읽지 못하면 (`cursor`, `INVALID`). 틀린 필드는 모두 `details.fields`에 담는다.
 */
object NotificationCursor {

	const val DEFAULT_SIZE = 20
	const val MAX_SIZE = 100

	private const val SEPARATOR = "|"
	private val UUID_FORMAT = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")

	fun encode(key: NotificationCursorKey): String =
		Base64.getUrlEncoder().withoutPadding()
			.encodeToString("${key.createdAt}$SEPARATOR${key.id}".toByteArray(Charsets.UTF_8))

	fun parse(rawCursor: String?, rawSize: String?): NotificationPageQuery {
		val errors = mutableListOf<FieldError>()
		val size = if (rawSize == null) {
			DEFAULT_SIZE
		} else {
			rawSize.toIntOrNull()?.takeIf { it in 1..MAX_SIZE } ?: run {
				errors += FieldError("size", FieldErrorReason.INVALID)
				DEFAULT_SIZE
			}
		}
		val cursor = rawCursor?.let { raw ->
			decode(raw) ?: run {
				errors += FieldError("cursor", FieldErrorReason.INVALID)
				null
			}
		}
		if (errors.isNotEmpty()) {
			throw ApiException(
				ErrorCode.VALIDATION_FAILED,
				errors.joinToString { "${it.field}: ${it.reason}" },
				FieldErrorDetails(errors),
			)
		}
		return NotificationPageQuery(cursor, size)
	}

	private fun decode(raw: String): NotificationCursorKey? {
		val text = try {
			String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
		} catch (e: IllegalArgumentException) {
			return null
		}
		val parts = text.split(SEPARATOR)
		if (parts.size != 2) return null
		val createdAt = try {
			Instant.parse(parts[0])
		} catch (e: DateTimeParseException) {
			return null
		}
		if (!UUID_FORMAT.matches(parts[1])) return null
		return NotificationCursorKey(createdAt, UUID.fromString(parts[1]))
	}
}
