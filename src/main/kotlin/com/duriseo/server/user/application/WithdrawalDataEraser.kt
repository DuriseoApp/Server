package com.duriseo.server.user.application

import com.duriseo.server.couple.application.InviteCodeService
import com.duriseo.server.event.application.EventContentService
import com.duriseo.server.memo.application.MemoContentService
import com.duriseo.server.notification.application.NotificationService
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 탈퇴 4단계 (ERD 4.3)에서 사용자의 행을 지우는 곳. 알림은 `NotificationService`, 유효한 초대코드는 `InviteCodeService`,
 * 일정 알림은 `EventContentService`, 즐겨찾기는 `MemoContentService`가 지운다.
 * 부른 쪽의 트랜잭션 안에서만 쓴다. 알림·초대코드 삭제는 영속성 컨텍스트를 비우므로 부른 쪽은 이 뒤에 이전에 읽은 엔티티를 쓰지 않는다.
 * 세션(`auth_sessions`)과 기기(`devices`)는 각 서비스가 지운다 (`SessionService`, `DeviceService`).
 */
@Component
class WithdrawalDataEraser(
	private val em: EntityManager,
	private val notificationService: NotificationService,
	private val inviteCodeService: InviteCodeService,
	private val eventContent: EventContentService,
	private val memoContent: MemoContentService,
) {

	@Transactional(propagation = Propagation.MANDATORY)
	fun eraseUserRows(userId: UUID) {
		DELETE_STATEMENTS.forEach { sql ->
			em.createNativeQuery(sql).setParameter("userId", userId).executeUpdate()
		}
		eventContent.deleteRemindersOfUser(userId)
		memoContent.deleteFavoritesOf(userId)
		notificationService.deleteAllOf(userId)
		inviteCodeService.deleteValidOf(userId)
	}

	private companion object {
		val DELETE_STATEMENTS = listOf(
			"delete from social_accounts where user_id = :userId",
			"delete from notification_settings where user_id = :userId",
		)
	}
}
