package com.duriseo.server.notification.application

import com.duriseo.server.notification.domain.Notification
import com.duriseo.server.notification.domain.NotificationRepository
import com.duriseo.server.notification.domain.NotificationTargetType
import com.duriseo.server.notification.domain.NotificationType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 알림 행을 만든다 (API.md 7장 "받는 사람마다 `notifications` 행을 먼저 만든다", ERD DB-07).
 * 푸시와 동기화 신호 `NOTIFICATION`은 단위 6이 커밋 뒤에 보낸다. 부른 쪽의 트랜잭션 안에서 만든다.
 * 연결·해제 알림(단위 2)과 상대 일정 추가 알림(단위 3)을 만든다. 목록·읽음·설정 API와 일정·기념일 알림 작업은 단위 6이다.
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

	/**
	 * 연결 해제 알림 (API.md 4.5 5단계, 4.6, PRD 8장). 상대에게만 만든다. `space_id` null(해제한 공간의 알림 삭제에 지워지지 않는다),
	 * `target_type` `SETTINGS`, `target_id` null (D-85). [partnerNickname]은 받는 사람 기준의 상대, 곧 해제한(탈퇴한) 사람의
	 * 닉네임이다(탈퇴면 익명화 전 값). [byWithdrawal]이면 탈퇴 전용 문구를 쓴다(복구 안내 없음, D-53). 만든 알림 ID를 돌려준다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun createCoupleUnlinked(recipientUserId: UUID, partnerNickname: String, byWithdrawal: Boolean, now: Instant): UUID {
		val notification = notifications.save(
			Notification(
				userId = recipientUserId,
				type = NotificationType.COUPLE_UNLINKED,
				title = if (byWithdrawal) {
					NotificationTexts.coupleUnlinkedByWithdrawalTitle(partnerNickname)
				} else {
					NotificationTexts.coupleUnlinkedTitle(partnerNickname)
				},
				body = if (byWithdrawal) NotificationTexts.COUPLE_UNLINKED_BY_WITHDRAWAL_BODY else NotificationTexts.COUPLE_UNLINKED_BODY,
				targetType = NotificationTargetType.SETTINGS,
				now = now,
			),
		)
		return notification.requireId()
	}

	/**
	 * 상대의 일정 추가 알림 (API.md 3.3 "상대 일정 추가 알림", 7장 "상대 활동 알림", D-92, PRD 8장). 받는 사람의 `partner_created` 설정과
	 * 관계없이 행은 만든다(설정은 푸시만 막는다, 7장 머리말). `target_type` `EVENT`, `target_id` 일정 ID, `space_id` 커플 공간(D-45).
	 * [startDate]는 일정의 시작 날짜다: 종일은 `start_date`, 시간 일정은 받는 사람의 시간대로 바꾼 날짜. 만든 알림 ID를 돌려준다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun createPartnerCreated(
		recipientUserId: UUID,
		authorNickname: String,
		startDate: LocalDate,
		eventTitle: String,
		spaceId: UUID,
		eventId: UUID,
		now: Instant,
	): UUID {
		val notification = Notification(
			userId = recipientUserId,
			type = NotificationType.PARTNER_CREATED,
			title = NotificationTexts.partnerCreatedTitle(authorNickname),
			body = NotificationTexts.partnerCreatedBody(startDate, eventTitle),
			targetType = NotificationTargetType.EVENT,
			now = now,
		)
		notification.spaceId = spaceId
		notification.targetId = eventId
		return notifications.save(notification).requireId()
	}

	/** 해제한 공간의 알림을 두 사람 모두 지운다 (API.md 4.5 3단계, PRD D-45). 영속성 컨텍스트를 비운다 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun deleteOfSpace(spaceId: UUID) {
		notifications.deleteAllBySpaceId(spaceId)
	}

	/** 탈퇴하는 사용자가 받은 알림을 모두 지운다 (ERD 4.3 4단계). 영속성 컨텍스트를 비운다 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun deleteAllOf(userId: UUID) {
		notifications.deleteAllByUserId(userId)
	}
}
