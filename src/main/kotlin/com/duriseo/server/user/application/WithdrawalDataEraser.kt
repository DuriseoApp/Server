package com.duriseo.server.user.application

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 탈퇴 4단계 (ERD 4.3)에서 사용자의 행을 지우는 곳. 아직 엔티티가 없는 테이블(알림, 즐겨찾기, 일정 알림, 초대코드)도 지워야 하므로
 * 테이블을 직접 지운다. 부른 쪽의 트랜잭션 안에서만 쓴다.
 * 세션(`auth_sessions`)과 기기(`devices`)는 각 서비스가 지운다 (`SessionService`, `DeviceService`).
 */
@Component
class WithdrawalDataEraser(private val em: EntityManager) {

	@Transactional(propagation = Propagation.MANDATORY)
	fun eraseUserRows(userId: UUID) {
		DELETE_STATEMENTS.forEach { sql ->
			em.createNativeQuery(sql).setParameter("userId", userId).executeUpdate()
		}
	}

	private companion object {
		val DELETE_STATEMENTS = listOf(
			"delete from social_accounts where user_id = :userId",
			"delete from notification_settings where user_id = :userId",
			"delete from notifications where user_id = :userId",
			"delete from memo_favorites where user_id = :userId",
			"delete from event_reminders where user_id = :userId",
			// 유효한 초대코드 = 사용되지 않았고 무효화되지 않은 코드
			"delete from invite_codes where creator_user_id = :userId and used_at is null and revoked_at is null",
		)
	}
}
