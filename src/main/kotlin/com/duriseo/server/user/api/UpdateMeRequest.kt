package com.duriseo.server.user.api

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.FieldError
import com.duriseo.server.common.api.FieldErrorDetails
import com.duriseo.server.common.api.FieldErrorReason
import com.duriseo.server.user.domain.Nicknames
import java.time.ZoneId

/**
 * `PATCH /me` 본문 `{nickname?, timeZone?}` (API.md 3.1). 없는 필드는 바꾸지 않고, `null`을 보내면 400 `VALIDATION_FAILED`(`REQUIRED`)다.
 * "필드가 없음"과 "null"을 구분해야 해서 본문을 Map으로 받아 여기서 검사한다.
 * - 닉네임: 앞뒤 공백을 지운 값을 저장한다. 지운 뒤 비면 `REQUIRED`, 코드 포인트 10개 초과면 `TOO_LONG` (D-82)
 * - 시간대: Java `ZoneId`가 아는 IANA 지역 이름만. 아니면 `INVALID` (NFR-TIME-01)
 */
data class UpdateMeRequest(val nickname: String?, val timeZone: String?) {

	companion object {
		private const val NICKNAME = "nickname"
		private const val TIME_ZONE = "timeZone"

		fun parse(body: Map<String, Any?>): UpdateMeRequest {
			val errors = mutableListOf<FieldError>()
			val nickname = nickname(body, errors)
			val timeZone = timeZone(body, errors)
			if (errors.isNotEmpty()) {
				throw ApiException(
					ErrorCode.VALIDATION_FAILED,
					errors.joinToString { "${it.field}: ${it.reason}" },
					FieldErrorDetails(errors),
				)
			}
			return UpdateMeRequest(nickname, timeZone)
		}

		private fun nickname(body: Map<String, Any?>, errors: MutableList<FieldError>): String? {
			if (!body.containsKey(NICKNAME)) return null
			val raw = body[NICKNAME]
			if (raw == null) {
				errors += FieldError(NICKNAME, FieldErrorReason.REQUIRED)
				return null
			}
			if (raw !is String) {
				errors += FieldError(NICKNAME, FieldErrorReason.INVALID)
				return null
			}
			val trimmed = raw.trim()
			when {
				trimmed.isEmpty() -> errors += FieldError(NICKNAME, FieldErrorReason.REQUIRED)
				Nicknames.codePointLength(trimmed) > Nicknames.MAX_CODE_POINTS -> errors += FieldError(NICKNAME, FieldErrorReason.TOO_LONG)
				else -> return trimmed
			}
			return null
		}

		private fun timeZone(body: Map<String, Any?>, errors: MutableList<FieldError>): String? {
			if (!body.containsKey(TIME_ZONE)) return null
			val raw = body[TIME_ZONE]
			if (raw == null) {
				errors += FieldError(TIME_ZONE, FieldErrorReason.REQUIRED)
				return null
			}
			if (raw !is String || raw !in ZoneId.getAvailableZoneIds()) {
				errors += FieldError(TIME_ZONE, FieldErrorReason.INVALID)
				return null
			}
			return raw
		}
	}
}
