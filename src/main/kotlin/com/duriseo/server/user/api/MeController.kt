package com.duriseo.server.user.api

import com.duriseo.server.common.web.AuthenticatedUser
import com.duriseo.server.common.web.CurrentUser
import com.duriseo.server.user.application.DeviceService
import com.duriseo.server.user.application.MeService
import com.duriseo.server.user.application.NotificationSettingsService
import com.duriseo.server.user.application.UserService
import com.duriseo.server.user.application.WithdrawalService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** 내 정보 API (API.md 3.1). 프로필 사진 경로는 [ProfileImageController] */
@RestController
class MeController(
	private val meService: MeService,
	private val userService: UserService,
	private val deviceService: DeviceService,
	private val withdrawalService: WithdrawalService,
	private val notificationSettingsService: NotificationSettingsService,
) {

	/** 내 정보 + 현재 공간 요약 (FR-SET-01). 게스트도 부를 수 있다 */
	@GetMapping("/me")
	fun get(@CurrentUser requester: AuthenticatedUser): MeResponse =
		MeResponse.from(meService.get(requester.userId))

	/** 닉네임·시간대 변경 (FR-AUTH-07, NFR-TIME-01). 없는 필드는 바꾸지 않는다 */
	@PatchMapping("/me")
	fun update(
		@CurrentUser requester: AuthenticatedUser,
		@RequestBody body: Map<String, Any?>,
	): MeResponse {
		val request = UpdateMeRequest.parse(body)
		userService.updateProfile(requester.userId, request.nickname, request.timeZone)
		return MeResponse.from(meService.get(requester.userId))
	}

	/** FCM 토큰 등록·갱신. 204 */
	@PutMapping("/me/devices")
	fun registerDevice(
		@CurrentUser requester: AuthenticatedUser,
		@Valid @RequestBody request: RegisterDeviceRequest,
	): ResponseEntity<Void> {
		deviceService.register(requester.userId, request.fcmToken!!, request.platformOrDefault())
		return ResponseEntity.noContent().build()
	}

	/** 알림 설정 조회 (FR-NOTI-02, D-62). 게스트도 부를 수 있다 */
	@GetMapping("/me/notification-settings")
	fun getNotificationSettings(@CurrentUser requester: AuthenticatedUser): NotificationSettingsResponse =
		NotificationSettingsResponse.from(notificationSettingsService.get(requester.userId))

	/** 알림 설정 부분 수정 (FR-NOTI-02, D-145). 없는 필드는 바꾸지 않고 응답은 전체 설정이다 */
	@PatchMapping("/me/notification-settings")
	fun updateNotificationSettings(
		@CurrentUser requester: AuthenticatedUser,
		@RequestBody(required = false) body: Map<String, Any?>?,
	): NotificationSettingsResponse {
		val change = NotificationSettingsRequest.parse(body ?: emptyMap())
		return NotificationSettingsResponse.from(notificationSettingsService.update(requester.userId, change))
	}

	/** 탈퇴 (FR-AUTH-06, API.md 4.6). 게스트도 부를 수 있다. 204 */
	@DeleteMapping("/me")
	fun delete(@CurrentUser requester: AuthenticatedUser): ResponseEntity<Void> {
		withdrawalService.withdraw(requester.userId)
		return ResponseEntity.noContent().build()
	}
}
