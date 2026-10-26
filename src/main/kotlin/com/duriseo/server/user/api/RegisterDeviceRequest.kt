package com.duriseo.server.user.api

import com.duriseo.server.user.domain.DevicePlatform
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * `PUT /me/devices` 본문 (API.md 3.1). `fcmToken`이 비었으면 `REQUIRED`, 255자 초과면 `TOO_LONG`,
 * `platform`이 `ANDROID`·`IOS`가 아니면 `INVALID`. `platform`을 안 보내면 `ANDROID`.
 */
data class RegisterDeviceRequest(
	@field:NotBlank @field:Size(max = 255) val fcmToken: String?,
	@field:Pattern(regexp = "ANDROID|IOS") val platform: String? = null,
) {
	fun platformOrDefault(): DevicePlatform = platform?.let { DevicePlatform.valueOf(it) } ?: DevicePlatform.ANDROID

	// FCM 토큰이 로그에 남지 않게 한다
	override fun toString(): String = "RegisterDeviceRequest(platform=$platform)"
}
