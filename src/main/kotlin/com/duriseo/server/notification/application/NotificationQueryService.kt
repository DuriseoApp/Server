package com.duriseo.server.notification.application

import com.duriseo.server.common.access.SpaceAccessPolicy
import com.duriseo.server.common.time.nowInSeconds
import com.duriseo.server.notification.domain.Notification
import com.duriseo.server.notification.domain.NotificationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** `GET /notifications` 한 페이지 */
data class NotificationPage(val items: List<Notification>, val nextCursor: String?)

/**
 * 알림 목록·안 읽음 개수·읽음 처리 (API.md 3.5, FR-NOTI-05, FR-CAL-01 AC4, R-12).
 * 보이는 알림은 `created_at > now − 30일`인 것뿐이다. 03:00 KST 정리 작업은 물리 삭제일 뿐이라 정리 전에도 이 경계를 지킨다 (D-141, DB-19).
 */
@Service
class NotificationQueryService(
	private val notifications: NotificationRepository,
	private val policy: SpaceAccessPolicy,
	private val clock: Clock,
) {

	/** `GET /notifications?cursor=&size=`: (`created_at`, `id`) 내림차순, 커서보다 작은 것. 같은 `created_at`이 페이지 경계에 걸려도 빠지지 않는다 */
	@Transactional(readOnly = true)
	fun list(requesterId: UUID, rawCursor: String?, rawSize: String?): NotificationPage {
		val query = NotificationCursor.parse(rawCursor, rawSize)
		val cutoff = visibleAfter()
		val limit = query.size + 1
		val cursor = query.cursor
		val found = if (cursor == null) {
			notifications.findVisibleFirstPage(requesterId, cutoff, limit)
		} else {
			notifications.findVisibleAfter(requesterId, cutoff, cursor.createdAt, cursor.id, limit)
		}
		val page = found.take(query.size)
		val next = if (found.size > page.size) {
			page.last().let { NotificationCursor.encode(NotificationCursorKey(it.createdAt, it.requireId())) }
		} else {
			null
		}
		return NotificationPage(page, next)
	}

	/** `GET /notifications/unread-count`: 30일 안의 안 읽은 알림 수 */
	@Transactional(readOnly = true)
	fun unreadCount(requesterId: UUID): Long = notifications.countVisibleUnread(requesterId, visibleAfter())

	/**
	 * `PUT /notifications/{id}/read`: 읽음 처리. 이미 읽음이어도 성공이고 `read_at`은 그대로다 (D-142).
	 * 없는 알림, 남의 알림, 30일이 지나 보이지 않는 알림은 모두 404 `NOT_FOUND`다 (R-12, 확인 필요 1).
	 */
	@Transactional
	fun markRead(requesterId: UUID, notificationId: UUID) {
		policy.requireNotificationOwner(requesterId, notifications.findVisibleOwnerId(notificationId, visibleAfter()))
		notifications.markRead(notificationId, clock.nowInSeconds())
	}

	private fun visibleAfter(): Instant = clock.instant().minus(VISIBLE_DAYS)

	companion object {
		/** 알림 보관·표시 기간 (D-141) */
		val VISIBLE_DAYS: Duration = Duration.ofDays(30)
	}
}
