package com.duriseo.server.push.application

import com.duriseo.server.notification.domain.NotificationTargetType
import com.duriseo.server.notification.domain.NotificationType
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** 푸시에 실을 알림 한 건의 값 (`notifications` 행 그대로, D-46) */
data class NotificationSnapshot(
	val id: UUID,
	val userId: UUID,
	val type: NotificationType,
	val title: String,
	val body: String?,
	val targetType: NotificationTargetType?,
	val targetId: UUID?,
)

/**
 * 알림 하나를 보내기 위해 읽은 값. [tokens]는 받는 사람 본인의 기기 토큰, [pushAllowed]는 보이는 푸시를 보내도 되는지
 * (유효 세션 R-16 + 그 종류의 설정, 연결·해제는 설정을 보지 않는다, API.md 7장)다.
 */
data class NotificationDeliveryPlan(val notification: NotificationSnapshot, val tokens: List<String>, val pushAllowed: Boolean)

/**
 * 푸시·동기화 신호를 보내기 위해 DB를 읽고(받는 사람 기기, 유효 세션, 종류별 설정, 공간 구성원) 무효 토큰 행을 지운다.
 *
 * 부르는 쪽이 `@TransactionalEventListener(AFTER_COMMIT)`라 이미 끝난 트랜잭션에 걸려 있다. 그 트랜잭션에 기대지 않도록 모든 메서드가
 * 새 트랜잭션(`REQUIRES_NEW`)이다. 읽기는 읽기 전용이다. `push`는 다른 도메인의 테이블(devices, auth_sessions, notification_settings,
 * space_members, notifications)을 읽기만 하므로 `SpaceAccessPolicy`처럼 도메인 리포지토리 대신 네이티브 쿼리로 읽는다.
 */
@Component
class PushStore(private val em: EntityManager) {

	/** 알림 행과 받는 사람의 기기·푸시 허용 여부를 한 번에 읽는다. 알림이 없으면 null */
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	fun planFor(notificationId: UUID, now: Instant): NotificationDeliveryPlan? {
		val row = em.createNativeQuery(
			"select n.id, n.user_id, n.type, n.title, n.body, n.target_type, n.target_id from notifications n where n.id = :id",
		)
			.setParameter("id", notificationId)
			.resultList
			.firstOrNull() as Array<*>? ?: return null
		val notification = NotificationSnapshot(
			id = row[0] as UUID,
			userId = row[1] as UUID,
			type = NotificationType.valueOf(row[2] as String),
			title = row[3] as String,
			body = row[4] as String?,
			targetType = (row[5] as String?)?.let { NotificationTargetType.valueOf(it) },
			targetId = row[6] as UUID?,
		)
		val tokens = tokensOf(notification.userId)
		val allowed = tokens.isNotEmpty() && hasValidSession(notification.userId, now) && settingAllows(notification.userId, notification.type)
		return NotificationDeliveryPlan(notification, tokens, allowed)
	}

	/** [userIds] 각자의 기기 토큰을 모두 모은다 (동기화 신호는 세션을 보지 않는다, R-16) */
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	fun tokensOfUsers(userIds: Collection<UUID>): List<String> = userIds.distinct().flatMap { tokensOf(it) }

	/** [spaceId]의 활성 구성원 중 [exceptUserId]가 아닌 사람 (동기화 신호의 받는 사람, API.md 6장) */
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	fun otherActiveMembers(spaceId: UUID, exceptUserId: UUID): List<UUID> =
		em.createNativeQuery(
			"select sm.user_id from space_members sm where sm.space_id = :spaceId and sm.left_at is null and sm.user_id <> :exceptUserId order by sm.user_id",
		)
			.setParameter("spaceId", spaceId)
			.setParameter("exceptUserId", exceptUserId)
			.resultList
			.map { it as UUID }

	/** 무효 토큰 응답을 받은 기기 행을 지우고 지운 기기 ID를 돌려준다(이미 없으면 null). 로그에는 토큰 대신 이 ID를 쓴다 (API.md 7장 "푸시 실패") */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	fun deleteDeviceByToken(fcmToken: String): UUID? =
		em.createNativeQuery("delete from devices where fcm_token = :token returning id")
			.setParameter("token", fcmToken)
			.resultList
			.firstOrNull() as UUID?

	private fun tokensOf(userId: UUID): List<String> =
		em.createNativeQuery("select d.fcm_token from devices d where d.user_id = :userId order by d.updated_at, d.id")
			.setParameter("userId", userId)
			.resultList
			.map { it as String }

	/** R-16: `revoked_at IS NULL AND expires_at > now`인 세션이 있을 때만 보이는 푸시를 보낸다 (ERD DB-14) */
	private fun hasValidSession(userId: UUID, now: Instant): Boolean =
		(em.createNativeQuery("select count(*) from auth_sessions s where s.user_id = :userId and s.revoked_at is null and s.expires_at > :now")
			.setParameter("userId", userId)
			.setParameter("now", now)
			.singleResult as Number).toLong() > 0

	/** 그 종류의 푸시 설정이 켜져 있는가. 연결·해제 알림은 설정이 없어 늘 true다 (API.md 7장). 행이 없으면 기본값(켜짐) */
	private fun settingAllows(userId: UUID, type: NotificationType): Boolean {
		val column = when (type) {
			NotificationType.EVENT_REMINDER -> "event_reminder"
			NotificationType.PARTNER_CREATED -> "partner_created"
			NotificationType.MEMO_PINNED -> "memo_pinned"
			NotificationType.ANNIVERSARY -> "anniversary"
			NotificationType.COUPLE_LINKED, NotificationType.COUPLE_UNLINKED -> return true
		}
		val value = em.createNativeQuery("select s.$column from notification_settings s where s.user_id = :userId")
			.setParameter("userId", userId)
			.resultList
			.firstOrNull() as Boolean?
		return value ?: true
	}
}
