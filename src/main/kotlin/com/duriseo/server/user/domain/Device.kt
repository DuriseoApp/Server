package com.duriseo.server.user.domain

import com.duriseo.server.common.persistence.UuidEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** FCM 푸시 토큰 (ERD 3.1 devices). 등록은 `DeviceService`의 upsert가 한다 */
@Entity
@Table(name = "devices")
class Device(userId: UUID, fcmToken: String, platform: DevicePlatform, now: Instant) : UuidEntity() {

	@Column(name = "user_id", nullable = false)
	var userId: UUID = userId

	@Column(name = "fcm_token", nullable = false, length = 255)
	var fcmToken: String = fcmToken

	@Enumerated(EnumType.STRING)
	@Column(name = "platform", nullable = false, length = 10)
	var platform: DevicePlatform = platform

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = now
}
