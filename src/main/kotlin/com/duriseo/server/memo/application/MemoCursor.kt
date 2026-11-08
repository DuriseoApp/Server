package com.duriseo.server.memo.application

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.FieldError
import com.duriseo.server.common.api.FieldErrorDetails
import com.duriseo.server.common.api.FieldErrorReason
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

/** `GET /memos`의 `view` (API.md 3.4). 기본은 [NEWEST] */
enum class MemoListView(val value: String) {
	NEWEST("newest"),
	OLDEST("oldest"),
	FAVORITES("favorites"),
	;

	companion object {
		fun of(raw: String): MemoListView? = entries.firstOrNull { it.value == raw }
	}
}

/** `GET /memos` 커서 키: (`created_at`, `id`) (API.md 3.3 "커서 정렬 키") */
data class MemoCursorKey(val createdAt: Instant, val id: UUID)

/** 이미 검사한 `view`·`cursor`·`size` ([cursor]는 첫 페이지면 null) */
data class MemoPageQuery(val view: MemoListView, val cursor: MemoCursorKey?, val size: Int)

/**
 * `GET /memos`의 `view`·`cursor`·`size` 규칙 (API.md 1.1 "커서·`size` 공통 규칙", 3.4).
 *
 * - `view`: `newest`(기본)·`oldest`·`favorites`. 모르는 값이면 400 `VALIDATION_FAILED`(`view`, `INVALID`) (확인 필요 4)
 * - `size`: 정수 1 ~ 100, 생략하면 20. 아니면 (`size`, `INVALID`)
 * - `cursor`: 서버가 만든 불투명 문자열. 이 구현은 `view`와 정렬 키를 base64url로 감싼 것이다. 읽지 못하거나 다른 `view`의 커서면
 *   (`cursor`, `INVALID`) (확인 필요 4). 앱은 해석하지 않는다
 * - 틀린 필드는 모두 `details.fields`에 담는다
 */
object MemoCursor {

	const val DEFAULT_SIZE = 20
	const val MAX_SIZE = 100

	private const val SEPARATOR = "|"
	private val UUID_FORMAT = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")

	fun encode(view: MemoListView, key: MemoCursorKey): String =
		Base64.getUrlEncoder().withoutPadding()
			.encodeToString(listOf(view.value, key.createdAt.toString(), key.id.toString()).joinToString(SEPARATOR).toByteArray(Charsets.UTF_8))

	fun parse(rawView: String?, rawCursor: String?, rawSize: String?): MemoPageQuery {
		val errors = mutableListOf<FieldError>()
		val view = if (rawView == null) {
			MemoListView.NEWEST
		} else {
			MemoListView.of(rawView) ?: run {
				errors += FieldError("view", FieldErrorReason.INVALID)
				null
			}
		}
		val size = if (rawSize == null) {
			DEFAULT_SIZE
		} else {
			rawSize.toIntOrNull()?.takeIf { it in 1..MAX_SIZE } ?: run {
				errors += FieldError("size", FieldErrorReason.INVALID)
				DEFAULT_SIZE
			}
		}
		val cursor = rawCursor?.let { raw ->
			// view를 알 수 없으면(이미 오류) 커서가 그 view의 것인지 비교할 수 없다. 읽을 수 있는지만 본다
			decode(raw, view) ?: run {
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
		return MemoPageQuery(checkNotNull(view), cursor, size)
	}

	private fun decode(raw: String, expectedView: MemoListView?): MemoCursorKey? {
		val text = try {
			String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
		} catch (e: IllegalArgumentException) {
			return null
		}
		val parts = text.split(SEPARATOR)
		if (parts.size != 3) return null
		val view = MemoListView.of(parts[0]) ?: return null
		if (expectedView != null && view != expectedView) return null
		val createdAt = try {
			Instant.parse(parts[1])
		} catch (e: DateTimeParseException) {
			return null
		}
		if (!UUID_FORMAT.matches(parts[2])) return null
		return MemoCursorKey(createdAt, UUID.fromString(parts[2]))
	}
}
