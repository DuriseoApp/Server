package com.duriseo.server.memo.application

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.FieldError
import com.duriseo.server.common.api.FieldErrorDetails
import com.duriseo.server.common.api.FieldErrorReason
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** `POST /memos`로 만들 메모의 값 (검사를 모두 통과한 뒤). 제목·본문 중 하나 이상은 null이 아니다 */
data class MemoDraft(val title: String?, val body: String?, val memoDate: LocalDate?, val notifyPartner: Boolean)

/** 요청에 있던 필드의 값. 값이 null이면 그 필드를 지우라는 뜻이다 (`PATCH`) */
data class MemoChange<T : Any>(val value: T?)

/** `PATCH /memos/{id}`로 바꿀 값 (검사를 모두 통과한 뒤). 요청에 없는 필드는 null이라 바꾸지 않는다 */
data class MemoPatch(val title: MemoChange<String>?, val body: MemoChange<String>?, val memoDate: MemoChange<LocalDate>?)

/**
 * 메모 쓰기 요청의 값 검사 (API.md 3.4 "메모 쓰기 규칙", FR-MEMO-02·03, D-132, R-15). 권한 검사(R-01·R-03)가 끝난 뒤에 부른다.
 * 본문은 "필드 없음"과 `null`을 구분하려고 `Map`으로 받은 JSON 값 그대로다. 모르는 필드는 무시한다.
 *
 * - 제목: 앞뒤 공백을 지운 값. 지운 뒤 빈 값이면 비어 있는 것, 코드 포인트 50개 초과면 `TOO_LONG`
 * - 본문: 공백·줄바꿈을 보존한다. `trim` 했을 때 비면 비어 있는 것(`null`)으로 보고 그때는 길이를 보지 않는다(확인 필요 2). 5000개 초과면 `TOO_LONG`
 * - 제목·본문의 `null`은 `POST`에서는 생략과 같고 `PATCH`에서는 지움. 문자열이 아니면 `INVALID`
 * - 결과(`PATCH`는 수정 뒤)가 제목·본문 둘 다 비면 `title`·`body` 모두 `REQUIRED`
 * - `memoDate`: `YYYY-MM-DD`가 아니면 `INVALID`, 생략·`null`은 없음(`PATCH`는 지움). 범위(1950-01-01 ~ 2050-12-31) 밖은 `DATE_OUT_OF_RANGE`
 * - `notifyPartner`(`POST`만): 생략 true, `null`은 `REQUIRED`, boolean이 아니면 `INVALID`. `PATCH`는 무시한다
 *
 * 틀린 필드는 모두 `details.fields`에 담는다. `VALIDATION_FAILED`가 하나라도 있으면 그것만 응답하고,
 * 날짜 범위(`DATE_OUT_OF_RANGE`)는 그 뒤에 본다. 한 응답에 두 코드를 섞지 않는다.
 */
object MemoWriteRules {

	const val TITLE = "title"
	const val BODY = "body"
	const val MEMO_DATE = "memoDate"
	const val NOTIFY_PARTNER = "notifyPartner"

	/** NFR-LIMIT-01. 코드 포인트로 센다 (D-82) */
	const val TITLE_MAX = 50
	const val BODY_MAX = 5000

	/** 날짜 범위 (R-15, D-74) */
	val MIN_DATE: LocalDate = LocalDate.of(1950, 1, 1)
	val MAX_DATE: LocalDate = LocalDate.of(2050, 12, 31)

	private val DATE_FORMAT = Regex("""\d{4}-\d{2}-\d{2}""")

	fun parseCreate(body: Map<String, Any?>): MemoDraft {
		val errors = mutableListOf<FieldError>()
		val title = parseTitle(body, errors)
		val text = parseBody(body, errors)
		checkNotBothEmpty(title, text, errors)
		val memoDate = parseMemoDate(body, errors)
		val notifyPartner = parseNotifyPartner(body, errors)
		if (errors.isNotEmpty()) throw validationFailed(errors)
		memoDate?.let { checkRange(it) }
		return MemoDraft(title, text, memoDate, checkNotNull(notifyPartner))
	}

	/** 부분 수정: 없는 필드는 바꾸지 않는다. [storedTitle]·[storedBody]는 수정 결과가 둘 다 비는지 보려는 저장된 값이다 */
	fun parsePatch(body: Map<String, Any?>, storedTitle: String?, storedBody: String?): MemoPatch {
		val errors = mutableListOf<FieldError>()
		val title = if (body.containsKey(TITLE)) MemoChange(parseTitle(body, errors)) else null
		val text = if (body.containsKey(BODY)) MemoChange(parseBody(body, errors)) else null
		// 수정 결과가 둘 다 비는지 본다. 제목·본문 값이 틀렸으면(이미 오류가 담김) 결과를 알 수 없어 보지 않는다
		checkNotBothEmpty(if (title != null) title.value else storedTitle, if (text != null) text.value else storedBody, errors)
		val memoDate = if (body.containsKey(MEMO_DATE)) MemoChange(parseMemoDate(body, errors)) else null
		if (errors.isNotEmpty()) throw validationFailed(errors)
		memoDate?.value?.let { checkRange(it) }
		return MemoPatch(title, text, memoDate)
	}

	/** 없거나 `null`이면 비어 있음. 문자열이 아니면 `INVALID`, 지운 뒤 50 코드 포인트 초과면 `TOO_LONG` (D-132, D-82) */
	private fun parseTitle(body: Map<String, Any?>, errors: MutableList<FieldError>): String? {
		val raw = body[TITLE] ?: return null
		if (raw !is String) return fail(TITLE, FieldErrorReason.INVALID, errors)
		val trimmed = raw.trim()
		if (trimmed.isEmpty()) return null
		if (codePoints(trimmed) > TITLE_MAX) return fail(TITLE, FieldErrorReason.TOO_LONG, errors)
		return trimmed
	}

	/** 없거나 `null`이면 비어 있음. 문자열이 아니면 `INVALID`. 공백뿐이면 `null`, 아니면 그대로 두고 5000 코드 포인트 초과면 `TOO_LONG` (D-132) */
	private fun parseBody(body: Map<String, Any?>, errors: MutableList<FieldError>): String? {
		val raw = body[BODY] ?: return null
		if (raw !is String) return fail(BODY, FieldErrorReason.INVALID, errors)
		if (raw.trim().isEmpty()) return null
		if (codePoints(raw) > BODY_MAX) return fail(BODY, FieldErrorReason.TOO_LONG, errors)
		return raw
	}

	/** 제목·본문 값에 오류가 없는데 둘 다 비면 `title`·`body` 모두 `REQUIRED` (D-132) */
	private fun checkNotBothEmpty(title: String?, body: String?, errors: MutableList<FieldError>) {
		if (errors.any { it.field == TITLE || it.field == BODY }) return
		if (title == null && body == null) {
			errors += FieldError(TITLE, FieldErrorReason.REQUIRED)
			errors += FieldError(BODY, FieldErrorReason.REQUIRED)
		}
	}

	private fun parseMemoDate(body: Map<String, Any?>, errors: MutableList<FieldError>): LocalDate? {
		val raw = body[MEMO_DATE] ?: return null
		if (raw !is String || !DATE_FORMAT.matches(raw)) return fail(MEMO_DATE, FieldErrorReason.INVALID, errors)
		return try {
			LocalDate.parse(raw)
		} catch (e: DateTimeParseException) {
			fail(MEMO_DATE, FieldErrorReason.INVALID, errors)
		}
	}

	/** 생략하면 true. `null`이면 `REQUIRED`, boolean이 아니면 `INVALID` (일정 `notifyPartner`와 같다) */
	private fun parseNotifyPartner(body: Map<String, Any?>, errors: MutableList<FieldError>): Boolean? {
		if (!body.containsKey(NOTIFY_PARTNER)) return true
		return when (val raw = body[NOTIFY_PARTNER]) {
			null -> fail(NOTIFY_PARTNER, FieldErrorReason.REQUIRED, errors)
			is Boolean -> raw
			else -> fail(NOTIFY_PARTNER, FieldErrorReason.INVALID, errors)
		}
	}

	/** `memoDate`가 1950-01-01 ~ 2050-12-31 밖이면 400 `DATE_OUT_OF_RANGE`(`memoDate`, `OUT_OF_RANGE`, R-15) */
	private fun checkRange(date: LocalDate) {
		if (!date.isBefore(MIN_DATE) && !date.isAfter(MAX_DATE)) return
		val errors = listOf(FieldError(MEMO_DATE, FieldErrorReason.OUT_OF_RANGE))
		throw ApiException(ErrorCode.DATE_OUT_OF_RANGE, "$MEMO_DATE: ${FieldErrorReason.OUT_OF_RANGE}", FieldErrorDetails(errors))
	}

	private fun fail(field: String, reason: FieldErrorReason, errors: MutableList<FieldError>): Nothing? {
		errors += FieldError(field, reason)
		return null
	}

	private fun codePoints(value: String): Int = value.codePointCount(0, value.length)

	private fun validationFailed(errors: List<FieldError>) = ApiException(
		ErrorCode.VALIDATION_FAILED,
		errors.joinToString { "${it.field}: ${it.reason}" },
		FieldErrorDetails(errors),
	)
}
