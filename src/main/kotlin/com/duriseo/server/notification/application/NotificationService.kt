package com.duriseo.server.notification.application

import com.duriseo.server.notification.domain.Notification
import com.duriseo.server.notification.domain.NotificationRepository
import com.duriseo.server.notification.domain.NotificationTargetType
import com.duriseo.server.notification.domain.NotificationType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 알림 행을 만든다 (API.md 7장 "받는 사람마다 `notifications` 행을 먼저 만든다", ERD DB-07).
 * 푸시와 동기화 신호 `NOTIFICATION`은 단위 6이 커밋 뒤에 보낸다. 부른 쪽의 트랜잭션 안에서 만든다.
 * 연결·해제 알림만 있고 목록·읽음·설정 API는 단위 6이다.
 */
@Service
class NotificationService(private val notifications: NotificationRepository) {

	/**
	 * 커플 연결(복구 포함) 알림 (API.md 4.4 6단계, PRD 8장). `space_id` null, `target_type` `SETTINGS`, `target_id` null (D-85).
	 * [partnerNickname]은 받는 사람 기준의 상대 닉네임이다(연결 시점 값). 만든 알림 ID를 돌려준다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun createCoupleLinked(recipientUserId: UUID, partnerNickname: String, now: Instant): UUID {
		val notification = notifications.save(
			Notification(
				userId = recipientUserId,
				type = NotificationType.COUPLE_LINKED,
				title = NotificationTexts.coupleLinkedTitle(partnerNickname),
				body = NotificationTexts.COUPLE_LINKED_BODY,
				targetType = NotificationTargetType.SETTINGS,
				now = now,
			),
		)
		return notification.requireId()
	}
}
