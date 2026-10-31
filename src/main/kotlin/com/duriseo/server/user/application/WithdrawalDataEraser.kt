package com.duriseo.server.user.application

import com.duriseo.server.couple.application.InviteCodeService
import com.duriseo.server.notification.application.NotificationService
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 탈퇴 4단계 (ERD 4.3)에서 사용자의 행을 지우는 곳. 알림은 `NotificationService`, 유효한 초대코드는 `InviteCodeService`가 지운다.
 * 아직 엔티티가 없는 테이블(즐겨찾기 단위 5, 일정 알림 단위 3)은 그 단위가 도메인 서비스를 만들 때까지 여기서 직접 지운다.
 * 부른 쪽의 트랜잭션 안에서만 쓴다. 알림·초대코드 삭제는 영속성 컨텍스트를 비우므로 부른 쪽은 이 뒤에 이전에 읽은 엔티티를 쓰지 않는다.
 * 세션(`auth_sessions`)과 기기(`devices`)는 각 서비스가 지운다 (`SessionService`, `DeviceService`).
 */
@Component
class WithdrawalDataEraser(
	private val em: EntityManager,
	private val notificationService: NotificationService,
	private val inviteCodeService: InviteCodeService,
) {

	@Transactional(propagation = Propagation.MANDATORY)
	fun eraseUserRows(userId: UUID) {
		DELETE_STATEMENTS.forEach { sql ->
			em.createNativeQuery(sql).setParameter("userId", userId).executeUpdate()
		}
		notificationService.deleteAllOf(userId)
		inviteCodeService.deleteValidOf(userId)
	}

	private companion object {
		val DELETE_STATEMENTS = listOf(
			"delete from social_accounts where user_id = :userId",
			"delete from notification_settings where user_id = :userId",
			"delete from memo_favorites where user_id = :userId",
			"delete from event_reminders where user_id = :userId",
		)
	}
}
