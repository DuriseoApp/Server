package com.duriseo.server.notification.domain

import com.duriseo.server.common.persistence.UuidEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 받은 알림 한 건 (ERD 3.1 notifications). 받는 사람마다 한 행이고 푸시와 별개로 먼저 만든다 (DB-07, API.md 7장).
 * `space_id`가 null이면 공간이 지워져도 남는 알림이다(연결·해제 알림). 제목·본문은 만들 때 서버가 채운 값 그대로다.
 * 목록·읽음 API는 단위 6이다.
 */
@Entity
@Table(name = "notifications")
class Notification(
	userId: UUID,
	type: NotificationType,
	title: String,
	body: String?,
	targetType: NotificationTargetType?,
	now: Instant,
) : UuidEntity() {

	@Column(name = "user_id", nullable = false, updatable = false)
	var userId: UUID = userId

	@Column(name = "space_id")
	var spaceId: UUID? = null

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, length = 30)
	var type: NotificationType = type

	@Column(name = "title", nullable = false, length = 100)
	var title: String = title

	@Column(name = "body", length = 200)
	var body: String? = body

	@Enumerated(EnumType.STRING)
	@Column(name = "target_type", length = 20)
	var targetType: NotificationTargetType? = targetType

	@Column(name = "target_id")
	var targetId: UUID? = null

	@Column(name = "read_at")
	var readAt: Instant? = null

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant = now
}
