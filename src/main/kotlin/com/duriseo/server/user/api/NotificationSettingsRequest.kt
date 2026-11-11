package com.duriseo.server.user.api

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.FieldError
import com.duriseo.server.common.api.FieldErrorDetails
import com.duriseo.server.common.api.FieldErrorReason
import com.duriseo.server.user.application.NotificationSettingsChange
import com.duriseo.server.user.application.NotificationSettingsView

/**
 * `PATCH /me/notification-settings` 본문 `{eventReminder?, partnerCreated?, memoPinned?, anniversary?}` (API.md 3.1, D-145).
 * 없는 필드는 바꾸지 않고, `null`은 400 `VALIDATION_FAILED`(그 필드, `REQUIRED`), boolean이 아니면 (그 필드, `INVALID`)다 (`PATCH /me`와 같은 방식).
 * 틀린 필드는 모두 `details.fields`에 담는다. 모르는 필드는 무시한다.
 */
object NotificationSettingsRequest {

	private val FIELDS = listOf("eventReminder", "partnerCreated", "memoPinned", "anniversary")

	fun parse(body: Map<String, Any?>): NotificationSettingsChange {
		val errors = mutableListOf<FieldError>()
		val values = FIELDS.associateWith { field ->
			if (!body.containsKey(field)) {
				null
			} else {
				when (val raw = body[field]) {
					null -> {
						errors += FieldError(field, FieldErrorReason.REQUIRED)
						null
					}
					is Boolean -> raw
					else -> {
						errors += FieldError(field, FieldErrorReason.INVALID)
						null
					}
				}
			}
		}
		if (errors.isNotEmpty()) {
			throw ApiException(
				ErrorCode.VALIDATION_FAILED,
				errors.joinToString { "${it.field}: ${it.reason}" },
				FieldErrorDetails(errors),
			)
		}
		return NotificationSettingsChange(values["eventReminder"], values["partnerCreated"], values["memoPinned"], values["anniversary"])
	}
}

/** `NotificationSettings` 응답 (`GET`·`PATCH /me/notification-settings`) */
data class NotificationSettingsResponse(
	val eventReminder: Boolean,
	val partnerCreated: Boolean,
	val memoPinned: Boolean,
	val anniversary: Boolean,
) {
	companion object {
		fun from(view: NotificationSettingsView) =
			NotificationSettingsResponse(view.eventReminder, view.partnerCreated, view.memoPinned, view.anniversary)
	}
}
