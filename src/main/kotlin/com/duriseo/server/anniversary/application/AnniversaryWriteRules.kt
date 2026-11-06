package com.duriseo.server.anniversary.application

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.FieldError
import com.duriseo.server.common.api.FieldErrorDetails
import com.duriseo.server.common.api.FieldErrorReason
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** `POST /anniversaries`로 만들 직접 기념일의 값 (검사를 모두 통과한 뒤) */
data class AnniversaryDraft(val name: String, val date: LocalDate, val repeatsYearly: Boolean)

/** `PATCH /anniversaries/{id}`로 바꿀 값 (검사를 모두 통과한 뒤). 요청에 없는 필드는 null이라 바꾸지 않는다 */
data class AnniversaryPatch(val name: String?, val date: LocalDate?, val repeatsYearly: Boolean?)

/**
 * 직접 기념일 쓰기 요청의 값 검사 (API.md 3.3 기념일 "쓰기 규칙", FR-ANNIV-03, D-104, R-15). 권한 검사(R-01·R-05)가 끝난 뒤에 부른다.
 * 본문은 "필드 없음"과 `null`을 구분하려고 `Map`으로 받은 JSON 값 그대로다. 모르는 필드는 무시한다.
 *
 * 틀린 필드는 모두 `details.fields`에 담는다. `VALIDATION_FAILED`가 하나라도 있으면 그것만 응답하고,
 * 날짜 범위(R-15, `DATE_OUT_OF_RANGE`)는 그 뒤에 본다. 한 응답에 두 코드를 섞지 않는다.
 */
object AnniversaryWriteRules {

	const val NAME = "name"
	const val DATE = "date"
	const val REPEATS_YEARLY = "repeatsYearly"

	/** NFR-LIMIT-01. 코드 포인트로 센다 (D-82). 길이는 앞뒤 공백을 지운 뒤 센다 */
	const val NAME_MAX = 30

	/** 날짜 범위 (R-15, D-74) */
	val MIN_DATE: LocalDate = AnniversaryCalculator.MIN_DATE
	val MAX_DATE: LocalDate = AnniversaryCalculator.MAX_DATE

	private val DATE_FORMAT = Regex("""\d{4}-\d{2}-\d{2}""")

	/** `name`·`date` 필수, `repeatsYearly` 생략하면 true (ERD 기본값). `null`은 `REQUIRED` */
	fun parseCreate(body: Map<String, Any?>): AnniversaryDraft {
		val errors = mutableListOf<FieldError>()
		val name = parseName(body, errors)
		val date = parseDate(body, errors)
		val repeatsYearly = if (body.containsKey(REPEATS_YEARLY)) parseRepeatsYearly(body, errors) else true
		if (errors.isNotEmpty()) throw validationFailed(errors)
		val draft = AnniversaryDraft(checkNotNull(name), checkNotNull(date), checkNotNull(repeatsYearly))
		checkRange(draft.date)
		return draft
	}

	/** 부분 수정: 없는 필드는 바꾸지 않는다. 어떤 필드든 `null`이면 `REQUIRED` */
	fun parsePatch(body: Map<String, Any?>): AnniversaryPatch {
		val errors = mutableListOf<FieldError>()
		val name = if (body.containsKey(NAME)) parseName(body, errors) else null
		val date = if (body.containsKey(DATE)) parseDate(body, errors) else null
		val repeatsYearly = if (body.containsKey(REPEATS_YEARLY)) parseRepeatsYearly(body, errors) else null
		if (errors.isNotEmpty()) throw validationFailed(errors)
		date?.let { checkRange(it) }
		return AnniversaryPatch(name, date, repeatsYearly)
	}

	/** 앞뒤 공백을 지운 값으로 저장한다. 지운 뒤 빈 값이면 `REQUIRED`, 코드 포인트 30개 초과면 `TOO_LONG` (D-104, D-82) */
	private fun parseName(body: Map<String, Any?>, errors: MutableList<FieldError>): String? {
		val raw = body[NAME] ?: return fail(NAME, FieldErrorReason.REQUIRED, errors)
		if (raw !is String) return fail(NAME, FieldErrorReason.INVALID, errors)
		val trimmed = raw.trim()
		if (trimmed.isEmpty()) return fail(NAME, FieldErrorReason.REQUIRED, errors)
		if (trimmed.codePointCount(0, trimmed.length) > NAME_MAX) return fail(NAME, FieldErrorReason.TOO_LONG, errors)
		return trimmed
	}

	/** 없거나 `null`이면 `REQUIRED`, `YYYY-MM-DD`가 아니면 `INVALID`. 범위는 [checkRange]가 따로 본다 */
	private fun parseDate(body: Map<String, Any?>, errors: MutableList<FieldError>): LocalDate? {
		val raw = body[DATE] ?: return fail(DATE, FieldErrorReason.REQUIRED, errors)
		if (raw !is String || !DATE_FORMAT.matches(raw)) return fail(DATE, FieldErrorReason.INVALID, errors)
		return try {
			LocalDate.parse(raw)
		} catch (e: DateTimeParseException) {
			fail(DATE, FieldErrorReason.INVALID, errors)
		}
	}

	/** `null`이면 `REQUIRED`, boolean이 아니면 `INVALID` (일정 `isTogether`와 같다) */
	private fun parseRepeatsYearly(body: Map<String, Any?>, errors: MutableList<FieldError>): Boolean? =
		when (val raw = body[REPEATS_YEARLY]) {
			null -> fail(REPEATS_YEARLY, FieldErrorReason.REQUIRED, errors)
			is Boolean -> raw
			else -> fail(REPEATS_YEARLY, FieldErrorReason.INVALID, errors)
		}

	/** 날짜가 1950-01-01 ~ 2050-12-31 밖이면 400 `DATE_OUT_OF_RANGE`(`date`, `OUT_OF_RANGE`, R-15) */
	private fun checkRange(date: LocalDate) {
		if (!date.isBefore(MIN_DATE) && !date.isAfter(MAX_DATE)) return
		val errors = listOf(FieldError(DATE, FieldErrorReason.OUT_OF_RANGE))
		throw ApiException(ErrorCode.DATE_OUT_OF_RANGE, "$DATE: ${FieldErrorReason.OUT_OF_RANGE}", FieldErrorDetails(errors))
	}

	private fun fail(field: String, reason: FieldErrorReason, errors: MutableList<FieldError>): Nothing? {
		errors += FieldError(field, reason)
		return null
	}

	private fun validationFailed(errors: List<FieldError>) = ApiException(
		ErrorCode.VALIDATION_FAILED,
		errors.joinToString { "${it.field}: ${it.reason}" },
		FieldErrorDetails(errors),
	)
}
