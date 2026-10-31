package com.duriseo.server.notification.domain

/** notifications.target_type (ERD 3.1, API.md 3.5): 알림을 눌렀을 때 이동할 대상 (FR-NOTI-03) */
enum class NotificationTargetType {
	EVENT,
	MEMO,
	ANNIVERSARY,
	SETTINGS,
}
