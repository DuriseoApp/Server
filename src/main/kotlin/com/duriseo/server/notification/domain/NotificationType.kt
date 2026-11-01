package com.duriseo.server.notification.domain

/** notifications.type (ERD 3.1, API.md 3.5). 단위 2는 연결·해제 알림, 단위 3은 `PARTNER_CREATED`를 만든다 */
enum class NotificationType {
	EVENT_REMINDER,
	PARTNER_CREATED,
	MEMO_PINNED,
	ANNIVERSARY,
	COUPLE_LINKED,
	COUPLE_UNLINKED,
}
