package com.duriseo.server.event.application

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.FieldError
import com.duriseo.server.common.api.FieldErrorDetails
import com.duriseo.server.common.api.FieldErrorReason
import com.duriseo.server.event.domain.Event
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** `POST /events`로 만들 일정의 값 (검사를 모두 통과한 뒤) */
data class EventDraft(
	val title: String,
	val note: String?,
	val isAllDay: Boolean,
	val startAt: Instant?,
	val endAt: Instant?,
	val startDate: LocalDate?,
	val endDate: LocalDate?,
	val isTogether: Boolean,
	/** 상대에게 알릴지(기본 true). 커플 공간에서만 뜻이 있다. `PARTNER_CREATED` 알림 행을 만들지 정한다 (FR-NOTI-04, D-92) */
	val notifyPartner: Boolean,
)

/** `PATCH /events/{id}`로 바꿀 값 (검사를 모두 통과한 뒤). 요청에 없는 필드는 null이거나 false라 바꾸지 않는다 */
data class EventPatch(
	val title: String?,
	val noteProvided: Boolean,
	val note: String?,
	val isTogether: Boolean?,
	val time: PatchedTime?,
)

/** 시간 필드를 바꾸는 요청. `isAllDay`와 시작·종료 네 칸을 한꺼번에 다시 정한다. [allDayChanged]·[startChanged]는 일정 알림 다시 계산(D-91)이 쓴다 */
data class PatchedTime(
	val isAllDay: Boolean,
	val startAt: Instant?,
	val endAt: Instant?,
	val startDate: LocalDate?,
	val endDate: LocalDate?,
	val allDayChanged: Boolean,
	val startChanged: Boolean,
)

/**
 * 일정 쓰기 요청의 값 검사 (API.md 3.3 "쓰기 규칙"). 권한 검사(R-01·R-02·R-13·R-10)가 끝난 뒤에 부른다.
 * 본문은 "필드 없음"과 `null`을 구분하려고 `Map`으로 받은 JSON 값 그대로다. 모르는 필드는 무시한다(대상 사용자 필드도 효과가 없다, R-10).
 *
 * 틀린 필드는 모두 `details.fields`에 담는다. `VALIDATION_FAILED`가 하나라도 있으면 그것만 응답하고,
 * 날짜 범위(R-15, `DATE_OUT_OF_RANGE`)는 그 뒤에 본다. 한 응답에 두 코드를 섞지 않는다.
 * 시간 일정의 날짜는 요청자 시간대([zone]) 기준이다 (R-15).
 */
class EventWriteRules(private val zone: ZoneId) {

	fun parseCreate(body: Map<String, Any?>): EventDraft {
		val errors = mutableListOf<FieldError>()
		val title = parseTitle(body, errors)
		val note = parseNote(body, errors)
		val isAllDay = parseBoolean(body, IS_ALL_DAY, required = true, default = null, errors)
		val start = parseTime(body, START, isAllDay, required = true, errors)
		val end = parseTime(body, END, isAllDay, required = true, errors)
		val isTogether = parseBoolean(body, IS_TOGETHER, required = false, default = false, errors)
		val notifyPartner = parseBoolean(body, NOTIFY_PARTNER, required = false, default = true, errors)
		checkOrder(start, end, errors)
		if (errors.isNotEmpty()) throw validationFailed(errors)

		// 오류가 없으면 아래 값은 모두 정해져 있다
		val allDay = checkNotNull(isAllDay)
		val startValue = checkNotNull(start)
		val endValue = checkNotNull(end)
		checkRange(startValue, endValue)
		return EventDraft(
			title = checkNotNull(title),
			note = note,
			isAllDay = allDay,
			startAt = startValue.instant,
			endAt = endValue.instant,
			startDate = startValue.date,
			endDate = endValue.date,
			isTogether = checkNotNull(isTogether),
			notifyPartner = checkNotNull(notifyPartner),
		)
	}

	/**
	 * 부분 수정: 없는 필드는 바꾸지 않는다. `note: null`만 일정 메모를 지우고, 그 밖의 필드에 `null`을 보내면 `REQUIRED`.
	 * `isAllDay`를 바꾸면 `start`·`end`를 같은 요청에 함께 보내야 하고 빠진 필드는 `REQUIRED`.
	 * `isAllDay`가 없는 요청의 `start`·`end`는 저장된 종류([stored])에 맞아야 한다. `notifyPartner`는 받지 않는다(무시).
	 */
	fun parsePatch(body: Map<String, Any?>, stored: Event): EventPatch {
		val errors = mutableListOf<FieldError>()
		val title = if (body.containsKey(TITLE)) parseTitle(body, errors) else null
		val noteProvided = body.containsKey(NOTE)
		val note = if (noteProvided) parseNote(body, errors) else null

		val isAllDayProvided = body.containsKey(IS_ALL_DAY)
		val requestedAllDay = if (isAllDayProvided) parseBoolean(body, IS_ALL_DAY, required = true, default = null, errors) else null
		// 종류를 정할 수 없으면(isAllDay 값이 틀림) 시작·종료의 형식은 보지 않는다. isAllDay 오류가 이미 담겨 있다
		val effectiveAllDay: Boolean? = if (isAllDayProvided) requestedAllDay else stored.isAllDay
		val allDayChanged = requestedAllDay != null && requestedAllDay != stored.isAllDay

		val startRequested = body.containsKey(START)
		val endRequested = body.containsKey(END)
		val start = if (startRequested || allDayChanged) parseTime(body, START, effectiveAllDay, required = true, errors) else null
		val end = if (endRequested || allDayChanged) parseTime(body, END, effectiveAllDay, required = true, errors) else null

		val isTogether = if (body.containsKey(IS_TOGETHER)) parseBoolean(body, IS_TOGETHER, required = true, default = null, errors) else null

		// 종료가 시작보다 이른지는 합친 값으로 본다. 요청에 없는 쪽은 저장된 값이다 (종류가 같을 때만)
		val effectiveStart = start ?: if (!allDayChanged && effectiveAllDay != null) storedTime(stored, START) else null
		val effectiveEnd = end ?: if (!allDayChanged && effectiveAllDay != null) storedTime(stored, END) else null
		if (start != null || end != null) checkOrder(effectiveStart, effectiveEnd, errors)
		if (errors.isNotEmpty()) throw validationFailed(errors)

		val timeChange = startRequested || endRequested || allDayChanged
		var time: PatchedTime? = null
		if (timeChange) {
			val allDay = checkNotNull(effectiveAllDay)
			val newStart = checkNotNull(effectiveStart)
			val newEnd = checkNotNull(effectiveEnd)
			// 요청에 있는 날짜만 범위를 본다. 저장된 쪽은 이미 통과했고, 시간대가 다른 상대가 고칠 때 엉뚱하게 거부하지 않으려는 것이다
			checkRange(start, end)
			time = PatchedTime(
				isAllDay = allDay,
				startAt = newStart.instant,
				endAt = newEnd.instant,
				startDate = newStart.date,
				endDate = newEnd.date,
				allDayChanged = allDayChanged,
				startChanged = allDayChanged || (start != null && start.differsFrom(storedTime(stored, START))),
			)
		}
		return EventPatch(title, noteProvided, note, isTogether, time)
	}

	// ── 필드 검사 ───────────────────────────────────────

	/** 앞뒤 공백을 지운 값으로 저장한다. 지운 뒤 빈 값이면 `REQUIRED`, 코드 포인트 50개 초과면 `TOO_LONG` (D-89, D-82) */
	private fun parseTitle(body: Map<String, Any?>, errors: MutableList<FieldError>): String? {
		val raw = body[TITLE] ?: return fail(TITLE, FieldErrorReason.REQUIRED, errors)
		if (raw !is String) return fail(TITLE, FieldErrorReason.INVALID, errors)
		val trimmed = raw.trim()
		if (trimmed.isEmpty()) return fail(TITLE, FieldErrorReason.REQUIRED, errors)
		if (codePoints(trimmed) > TITLE_MAX) return fail(TITLE, FieldErrorReason.TOO_LONG, errors)
		return trimmed
	}

	/** 없거나 `null`이면 메모 없음. 코드 포인트 1000개 초과면 `TOO_LONG` */
	private fun parseNote(body: Map<String, Any?>, errors: MutableList<FieldError>): String? {
		val raw = body[NOTE] ?: return null
		if (raw !is String) return fail(NOTE, FieldErrorReason.INVALID, errors)
		if (codePoints(raw) > NOTE_MAX) return fail(NOTE, FieldErrorReason.TOO_LONG, errors)
		return raw
	}

	/**
	 * boolean 필드. 필드가 없으면 [required]면 `REQUIRED`, 아니면 [default]. `null`을 보내면 `REQUIRED`, boolean이 아닌 값이면 `INVALID`.
	 * 오류면 null을 돌려준다.
	 */
	private fun parseBoolean(
		body: Map<String, Any?>,
		field: String,
		required: Boolean,
		default: Boolean?,
		errors: MutableList<FieldError>,
	): Boolean? {
		if (!body.containsKey(field)) return if (required) fail(field, FieldErrorReason.REQUIRED, errors) else default
		return when (val raw = body[field]) {
			null -> fail(field, FieldErrorReason.REQUIRED, errors)
			is Boolean -> raw
			else -> fail(field, FieldErrorReason.INVALID, errors)
		}
	}

	/**
	 * `start`·`end` 하나. 종일이면 `YYYY-MM-DD`, 아니면 UTC 일시 (1장). 없거나 `null`이면 `REQUIRED`, 종류에 맞지 않으면 `INVALID`.
	 * [isAllDay]가 null이면(종류를 정할 수 없음) 값이 있는지만 본다.
	 */
	private fun parseTime(
		body: Map<String, Any?>,
		field: String,
		isAllDay: Boolean?,
		required: Boolean,
		errors: MutableList<FieldError>,
	): TimeValue? {
		val raw = body[field]
		if (raw == null) return if (required) fail(field, FieldErrorReason.REQUIRED, errors) else null
		if (raw !is String) return fail(field, FieldErrorReason.INVALID, errors)
		if (isAllDay == null) return null
		val parsed = if (isAllDay) parseDate(raw)?.let { TimeValue(it, null) } else parseDateTime(raw)?.let { TimeValue(null, it) }
		return parsed ?: fail(field, FieldErrorReason.INVALID, errors)
	}

	private fun parseDate(text: String): LocalDate? {
		if (!DATE_FORMAT.matches(text)) return null
		return try {
			LocalDate.parse(text)
		} catch (e: DateTimeParseException) {
			null
		}
	}

	/** API 1장 예시 형식 `YYYY-MM-DDTHH:MM:SSZ`만 받는다. `Z`가 아닌 오프셋과 소수 초는 `INVALID`다 (리드 결정) */
	private fun parseDateTime(text: String): Instant? {
		if (!DATE_TIME_FORMAT.matches(text)) return null
		return try {
			OffsetDateTime.parse(text).toInstant().truncatedTo(ChronoUnit.SECONDS)
		} catch (e: DateTimeException) {
			null
		}
	}

	private fun storedTime(stored: Event, field: String): TimeValue =
		if (stored.isAllDay) {
			TimeValue(if (field == START) stored.startDate else stored.endDate, null)
		} else {
			TimeValue(null, if (field == START) stored.startAt else stored.endAt)
		}

	/** 종료가 시작보다 이르면 (`end`, `INVALID`). 같으면 저장한다. 둘 중 하나라도 정해지지 않았으면(이미 오류) 건너뛴다 */
	private fun checkOrder(start: TimeValue?, end: TimeValue?, errors: MutableList<FieldError>) {
		if (start == null || end == null) return
		val before = when {
			start.date != null && end.date != null -> end.date.isBefore(start.date)
			start.instant != null && end.instant != null -> end.instant.isBefore(start.instant)
			else -> false
		}
		if (before) errors += FieldError(END, FieldErrorReason.INVALID)
	}

	/**
	 * 날짜 범위 (R-15): 시작·종료 날짜가 1950-01-01 ~ 2050-12-31 밖이면 400 `DATE_OUT_OF_RANGE`(`start`/`end` 각각, 둘 다 밖이면 둘 다 담는다).
	 * 시간 일정은 요청자 시간대의 날짜로 본다. 검사할 값이 null이면(PATCH에서 요청에 없는 쪽) 건너뛴다.
	 */
	private fun checkRange(start: TimeValue?, end: TimeValue?) {
		val errors = mutableListOf<FieldError>()
		if (start != null && !inRange(start)) errors += FieldError(START, FieldErrorReason.OUT_OF_RANGE)
		if (end != null && !inRange(end)) errors += FieldError(END, FieldErrorReason.OUT_OF_RANGE)
		if (errors.isNotEmpty()) {
			throw ApiException(
				ErrorCode.DATE_OUT_OF_RANGE,
				errors.joinToString { "${it.field}: ${it.reason}" },
				FieldErrorDetails(errors),
			)
		}
	}

	private fun inRange(value: TimeValue): Boolean {
		val date = value.date ?: checkNotNull(value.instant).atZone(zone).toLocalDate()
		return !date.isBefore(MIN_DATE) && !date.isAfter(MAX_DATE)
	}

	/** 오류를 담고 null을 돌려준다. 검사 함수가 `return fail(…)`로 값 대신 null을 돌려주려고 쓴다 */
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

	/** `start`/`end` 하나의 값. 종일이면 [date], 아니면 [instant] */
	private class TimeValue(val date: LocalDate?, val instant: Instant?) {
		fun differsFrom(other: TimeValue): Boolean = date != other.date || instant != other.instant
	}

	companion object {
		const val TITLE = "title"
		const val NOTE = "note"
		const val IS_ALL_DAY = "isAllDay"
		const val START = "start"
		const val END = "end"
		const val IS_TOGETHER = "isTogether"
		const val NOTIFY_PARTNER = "notifyPartner"

		/** NFR-LIMIT-01. 코드 포인트로 센다 (D-82) */
		const val TITLE_MAX = 50
		const val NOTE_MAX = 1000

		/** 날짜 범위 (R-15, D-74) */
		val MIN_DATE: LocalDate = LocalDate.of(1950, 1, 1)
		val MAX_DATE: LocalDate = LocalDate.of(2050, 12, 31)

		private val DATE_FORMAT = Regex("""\d{4}-\d{2}-\d{2}""")
		private val DATE_TIME_FORMAT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")
	}
}
