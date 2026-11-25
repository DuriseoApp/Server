package com.duriseo.server.appops.application

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.FieldError
import com.duriseo.server.common.api.FieldErrorDetails
import com.duriseo.server.common.api.FieldErrorReason
import java.math.BigInteger
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * 관리자 API 본문(`Map`으로 읽은 JSON)을 검사하며 읽는다 (API.md 3.7 "관리자 API 본문 검사").
 * 틀린 필드를 하나씩 모아 두었다가 [throwIfAny]가 한 번에 400 `VALIDATION_FAILED`로 던진다. 같은 필드는 처음 틀린 이유만 남긴다.
 * 필드 이름은 요청 그대로이고 중첩은 `build.op`, 배열 원소는 `build.values[0]`처럼 쓴다. 모르는 필드는 읽지 않으므로 무시된다.
 */
internal class BodyReader(private val body: Map<String, Any?>) {

	private val errors = LinkedHashMap<String, FieldErrorReason>()

	fun fail(field: String, reason: FieldErrorReason) {
		errors.putIfAbsent(field, reason)
	}

	fun hasError(field: String): Boolean = field in errors

	fun throwIfAny() {
		if (errors.isEmpty()) return
		val fields = errors.map { FieldError(it.key, it.value) }
		throw ApiException(
			ErrorCode.VALIDATION_FAILED,
			fields.joinToString { "${it.field}: ${it.reason}" },
			FieldErrorDetails(fields),
		)
	}

	/** 본문 최상위의 [name] 값. 없으면 null (없는 것과 `null`은 같게 본다) */
	fun raw(name: String): Any? = body[name]

	/**
	 * 문자열 필드. 없거나 `null`이면 null. 문자열이 아니거나 빈 문자열이면 `INVALID`, [maxLength]자(코드 포인트) 초과면 `TOO_LONG`.
	 * 틀리면 null을 돌려준다(그 필드는 이미 오류로 기록됨).
	 */
	fun string(name: String, maxLength: Int, source: Map<String, Any?> = body, path: String = name): String? {
		val value = source[name] ?: return null
		if (value !is String || value.isEmpty()) {
			fail(path, FieldErrorReason.INVALID)
			return null
		}
		if (value.codePointCount(0, value.length) > maxLength) {
			fail(path, FieldErrorReason.TOO_LONG)
			return null
		}
		return value
	}

	/** `https://` 주소 필드(`linkUrl`) */
	fun httpsUrl(name: String, maxLength: Int): String? {
		val value = string(name, maxLength) ?: return null
		if (!isHttpsUrl(value)) {
			fail(name, FieldErrorReason.INVALID)
			return null
		}
		return value
	}

	/** 일시 필드: `YYYY-MM-DDTHH:MM:SSZ`만 받는다 (1장) */
	fun instant(name: String): Instant? {
		val value = body[name] ?: return null
		if (value !is String || !INSTANT_FORMAT.matches(value)) {
			fail(name, FieldErrorReason.INVALID)
			return null
		}
		return try {
			Instant.parse(value)
		} catch (e: DateTimeParseException) {
			fail(name, FieldErrorReason.INVALID)
			null
		}
	}

	/** 0~2147483647 정수 한 칸. 없거나 `null`이면 null, 정수가 아니거나 범위 밖이면 `INVALID` */
	fun int(value: Any?, path: String): Int? {
		if (value == null) return null
		val long = when (value) {
			is Int -> value.toLong()
			is Long -> value
			is BigInteger -> if (value.bitLength() < 63) value.toLong() else Long.MIN_VALUE
			else -> Long.MIN_VALUE
		}
		if (long < 0 || long > Int.MAX_VALUE) {
			fail(path, FieldErrorReason.INVALID)
			return null
		}
		return long.toInt()
	}

	/** 중첩 객체 필드. 없거나 `null`이면 null, 객체가 아니면 `INVALID` */
	@Suppress("UNCHECKED_CAST")
	fun obj(name: String): Map<String, Any?>? {
		val value = body[name] ?: return null
		if (value !is Map<*, *>) {
			fail(name, FieldErrorReason.INVALID)
			return null
		}
		return value as Map<String, Any?>
	}

	companion object {
		private val INSTANT_FORMAT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")

		fun isHttpsUrl(value: String): Boolean = value.startsWith("https://") && value.length > "https://".length
	}
}
