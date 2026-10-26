package com.duriseo.server.user.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** 알림 종류별 켜고 끄기 (ERD 3.1). 사용자를 만들 때 함께 만든다. 기본 키는 user_id */
@Entity
@Table(name = "notification_settings")
class NotificationSettings(userId: UUID, now: Instant) {

	@Id
	@Column(name = "user_id", nullable = false, updatable = false)
	var userId: UUID = userId

	@Column(name = "event_reminder", nullable = false)
	var eventReminder: Boolean = true

	@Column(name = "partner_created", nullable = false)
	var partnerCreated: Boolean = true

	@Column(name = "memo_pinned", nullable = false)
	var memoPinned: Boolean = true

	@Column(name = "anniversary", nullable = false)
	var anniversary: Boolean = true

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = now
}
