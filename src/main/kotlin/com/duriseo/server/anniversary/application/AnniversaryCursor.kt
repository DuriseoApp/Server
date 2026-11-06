package com.duriseo.server.anniversary.application

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.FieldError
import com.duriseo.server.common.api.FieldErrorDetails
import com.duriseo.server.common.api.FieldErrorReason
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

/** `upcoming` 커서 키: (`date`, 같은 날 순번, `anniversaryId`). 자동은 `anniversaryId`가 null이다 (API.md 3.3 "커서 정렬 키") */
data class UpcomingCursorKey(val date: LocalDate, val rank: Int, val anniversaryId: UUID?)

/** `past` 커서 키: (`date`, `anniversaryId`) */
data class PastCursorKey(val date: LocalDate, val anniversaryId: UUID)

/** 이미 검사한 `cursor`·`size` ([cursor]는 처음 페이지면 null) */
data class PageQuery<K>(val cursor: K?, val size: Int)

/**
 * 기념일 목록의 `cursor`·`size` 규칙 (API.md 1.1 "커서·`size` 공통 규칙").
 *
 * - `size`: 정수 1 ~ 100, 생략하면 20. 정수가 아니거나 범위 밖이면 400 `VALIDATION_FAILED`(`size`, `INVALID`)
 * - `cursor`: 서버가 만든 불투명 문자열. 읽지 못하면 400 `VALIDATION_FAILED`(`cursor`, `INVALID`). 앱은 해석하지 않는다
 *   (이 구현은 종류 표시와 정렬 키를 base64url로 감싼 것이다. 다른 경로의 커서를 보내면 읽지 못한 것으로 본다)
 * - 틀린 필드는 모두 `details.fields`에 담는다
 */
object AnniversaryCursor {

	const val DEFAULT_SIZE = 20
	const val MAX_SIZE = 100

	private const val UPCOMING_TAG = "u"
	private const val PAST_TAG = "p"
	private const val SEPARATOR = "|"

	private val DATE_FORMAT = Regex("""\d{4}-\d{2}-\d{2}""")
	private val UUID_FORMAT = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")

	fun encode(key: UpcomingCursorKey): String =
		encodeParts(UPCOMING_TAG, key.date.toString(), key.rank.toString(), key.anniversaryId?.toString().orEmpty())

	fun encode(key: PastCursorKey): String = encodeParts(PAST_TAG, key.date.toString(), key.anniversaryId.toString())

	fun parseUpcoming(rawCursor: String?, rawSize: String?): PageQuery<UpcomingCursorKey> =
		parse(rawCursor, rawSize, ::decodeUpcoming)

	fun parsePast(rawCursor: String?, rawSize: String?): PageQuery<PastCursorKey> =
		parse(rawCursor, rawSize, ::decodePast)

	private fun <K> parse(rawCursor: String?, rawSize: String?, decode: (String) -> K?): PageQuery<K> {
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
		return PageQuery(cursor, size)
	}

	private fun decodeUpcoming(raw: String): UpcomingCursorKey? {
		val parts = split(raw, UPCOMING_TAG, 4) ?: return null
		val date = dateOf(parts[1]) ?: return null
		val rank = parts[2].toIntOrNull()?.takeIf { it in 0..2 } ?: return null
		val id = if (parts[3].isEmpty()) null else uuidOf(parts[3]) ?: return null
		// 직접 기념일(순번 2)만 ID가 있고 자동(순번 0·1)은 ID가 없다
		if ((rank == CUSTOM_RANK) != (id != null)) return null
		return UpcomingCursorKey(date, rank, id)
	}

	private fun decodePast(raw: String): PastCursorKey? {
		val parts = split(raw, PAST_TAG, 3) ?: return null
		return PastCursorKey(dateOf(parts[1]) ?: return null, uuidOf(parts[2]) ?: return null)
	}

	private fun split(raw: String, tag: String, count: Int): List<String>? {
		val text = try {
			String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
		} catch (e: IllegalArgumentException) {
			return null
		}
		val parts = text.split(SEPARATOR)
		return parts.takeIf { it.size == count && it[0] == tag }
	}

	private fun dateOf(text: String): LocalDate? {
		if (!DATE_FORMAT.matches(text)) return null
		return try {
			LocalDate.parse(text)
		} catch (e: DateTimeParseException) {
			null
		}
	}

	private fun uuidOf(text: String): UUID? = if (UUID_FORMAT.matches(text)) UUID.fromString(text) else null

	private fun encodeParts(vararg parts: String): String =
		Base64.getUrlEncoder().withoutPadding().encodeToString(parts.joinToString(SEPARATOR).toByteArray(Charsets.UTF_8))

	/** 직접 기념일의 같은 날 순번. 자동 일수 0, 자동 주년 1 ([AutoAnniversary.rank]) */
	const val CUSTOM_RANK = 2
}
