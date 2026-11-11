package com.duriseo.server.notification.api

import com.duriseo.server.notification.application.NotificationPage
import com.duriseo.server.notification.domain.Notification
import com.duriseo.server.notification.domain.NotificationTargetType
import com.duriseo.server.notification.domain.NotificationType
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** API.md 3.5 항목 `{id, type, title, body, targetType, targetId, read, createdAt}`. 값이 없는 필드도 `null`로 보낸다 (1.1) */
data class NotificationResponse(
	val id: UUID,
	val type: NotificationType,
	val title: String,
	val body: String?,
	val targetType: NotificationTargetType?,
	val targetId: UUID?,
	val read: Boolean,
	val createdAt: Instant,
) {
	companion object {
		fun from(n: Notification) = NotificationResponse(
			n.requireId(), n.type, n.title, n.body, n.targetType, n.targetId, n.readAt != null, n.createdAt.truncatedTo(ChronoUnit.SECONDS),
		)
	}
}

/** `GET /notifications`: `{items, nextCursor}` */
data class NotificationPageResponse(val items: List<NotificationResponse>, val nextCursor: String?) {
	companion object {
		fun from(page: NotificationPage) = NotificationPageResponse(page.items.map { NotificationResponse.from(it) }, page.nextCursor)
	}
}

/** `GET /notifications/unread-count`: `{count}` */
data class UnreadCountResponse(val count: Long)
