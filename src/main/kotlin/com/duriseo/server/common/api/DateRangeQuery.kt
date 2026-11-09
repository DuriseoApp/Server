package com.duriseo.server.common.api

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 날짜 범위 조회(`from`·`to`)의 검사와 잘라내기 (API.md 3.3 "조회 규칙", R-15). `GET /memos/dated`가 쓴다.
 * `GET /events`·`GET /anniversaries`는 같은 규칙의 자기 복사본을 갖고 있다.
 *
 * `from`·`to`는 모두 필수 `YYYY-MM-DD`이고 `to`를 포함한다. 없으면 `REQUIRED`, 날짜 형식이 아니면 `INVALID`,
 * `from` > `to`이거나 `to` > `from` + 3개월(달력 월로 더함)이면 (`to`, `INVALID`). 틀린 필드를 모두 담는다.
 * 이 검사는 요청받은 값 그대로 먼저 하고, 그 뒤 [min]~[max]로 잘라낸다. 잘라낸 구간이 비면 null이다.
 */
object DateRangeQuery {

	private const val RANGE_MONTHS = 3L
	private val QUERY_DATE_FORMAT = Regex("""\d{4}-\d{2}-\d{2}""")

	fun parseAndClamp(rawFrom: String?, rawTo: String?, min: LocalDate, max: LocalDate): Pair<LocalDate, LocalDate>? {
		val errors = mutableListOf<FieldError>()
		val from = parseDate("from", rawFrom, errors)
		val to = parseDate("to", rawTo, errors)
		if (from != null && to != null) {
			if (from.isAfter(to) || to.isAfter(from.plusMonths(RANGE_MONTHS))) errors += FieldError("to", FieldErrorReason.INVALID)
		}
		if (errors.isNotEmpty()) {
			throw ApiException(
				ErrorCode.VALIDATION_FAILED,
				errors.joinToString { "${it.field}: ${it.reason}" },
				FieldErrorDetails(errors),
			)
		}
		val clampedFrom = if (checkNotNull(from).isBefore(min)) min else from
		val clampedTo = if (checkNotNull(to).isAfter(max)) max else to
		return if (clampedFrom.isAfter(clampedTo)) null else clampedFrom to clampedTo
	}

	private fun parseDate(field: String, raw: String?, errors: MutableList<FieldError>): LocalDate? {
		if (raw == null) {
			errors += FieldError(field, FieldErrorReason.REQUIRED)
			return null
		}
		if (!QUERY_DATE_FORMAT.matches(raw)) {
			errors += FieldError(field, FieldErrorReason.INVALID)
			return null
		}
		return try {
			LocalDate.parse(raw)
		} catch (e: DateTimeParseException) {
			errors += FieldError(field, FieldErrorReason.INVALID)
			null
		}
	}
}
