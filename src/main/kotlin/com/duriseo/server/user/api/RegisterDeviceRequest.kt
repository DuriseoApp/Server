package com.duriseo.server.user.api

import com.duriseo.server.user.domain.DevicePlatform
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * `PUT /me/devices` 본문 (API.md 3.1). `fcmToken`이 비었으면 `REQUIRED`, 255자 초과면 `TOO_LONG`,
 * `platform`이 `ANDROID`·`IOS`가 아니면 `INVALID`. `platform`을 안 보내면 `ANDROID`.
 * 기기 정보(FR-APP-03.AC2, D-182)는 모두 선택이다: `osVersion` 20자 초과·`appVersion` 50자 초과는 `TOO_LONG`, `appBuild`가 0~2147483647 정수가 아니면 `INVALID`.
 */
data class RegisterDeviceRequest(
	@field:NotBlank @field:Size(max = 255) val fcmToken: String?,
	@field:Pattern(regexp = "ANDROID|IOS") val platform: String? = null,
	@field:Size(max = 20) val osVersion: String? = null,
	@field:Size(max = 50) val appVersion: String? = null,
	@field:ValidAppBuild val appBuild: Any? = null,
) {
	fun platformOrDefault(): DevicePlatform = platform?.let { DevicePlatform.valueOf(it) } ?: DevicePlatform.ANDROID

	/** 검사를 통과한 `appBuild`. 없으면 null */
	fun appBuildOrNull(): Int? = AppBuildValidator.toBuild(appBuild)

	// FCM 토큰이 로그에 남지 않게 한다
	override fun toString(): String = "RegisterDeviceRequest(platform=$platform)"
}
