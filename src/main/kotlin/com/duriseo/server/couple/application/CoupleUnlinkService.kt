package com.duriseo.server.couple.application

import com.duriseo.server.common.access.SpaceAccessPolicy
import com.duriseo.server.common.time.nowInSeconds
import com.duriseo.server.notification.application.NotificationService
import com.duriseo.server.space.application.SpaceService
import com.duriseo.server.space.domain.SpaceType
import com.duriseo.server.user.application.PartnerLockRetry
import com.duriseo.server.user.application.UserService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

/**
 * 연결 해제 (`DELETE /couple`, FR-COUPLE-05, API.md 4.5, ERD 4.2)와 탈퇴로 인한 해제 (API.md 4.6, ERD 4.3 1단계).
 * 권한은 R-18(요청자의 활성 공간이 `COUPLE`)이며 `SpaceAccessPolicy`가 검사한다.
 *
 * **잠금 순서는 users(UUID 오름차순) → spaces다.** 요청자와 상대의 사용자 행을 먼저 잠그고 공간 행을 잠근 뒤 다시 확인한다.
 * 해제가 공간 행을 먼저 잡으면 상대의 알림·개인 공간 소속을 넣을 때 상대 사용자 행(`FOR KEY SHARE`)을 기다리는데, 그 행을 잡은
 * 상대의 탈퇴가 공간 행을 기다려 교착한다. 둘이 동시에 해제하면 두 번째 요청은 사용자 행 잠금 뒤 개인 공간을 보고 409 `NOT_COUPLE`이다.
 * 가져오기(`POST /couple/import`)는 사용자 행을 잠그지 않고 같은 공간 행만 잠그므로 둘은 공간 행에서 한 줄로 선다.
 */
@Service
class CoupleUnlinkService(
	private val policy: SpaceAccessPolicy,
	private val userService: UserService,
	private val spaceService: SpaceService,
	private val content: CoupleContentGateway,
	private val notifications: NotificationService,
	private val events: ApplicationEventPublisher,
	private val clock: Clock,
	transactionManager: PlatformTransactionManager,
) {

	private val tx = TransactionTemplate(transactionManager)

	/**
	 * `DELETE /couple`: 개인 공간이면 409 `NOT_COUPLE`. 해제한 사람 자신에게는 알림을 만들지 않는다 (API.md 4.5 5단계).
	 * 잠그는 동안 상대가 바뀌면 트랜잭션을 롤백하고 처음부터 다시 한다 ([PartnerLockRetry]).
	 */
	fun unlink(requesterId: UUID) {
		PartnerLockRetry.run { tx.executeWithoutResult { unlinkInTransaction(requesterId) } }
	}

	private fun unlinkInTransaction(requesterId: UUID) {
		policy.requireCoupleSpace(requesterId)
		val requester = userService.lockForUpdateWithPartner(requesterId)
		// 잠근 뒤 다시 확인한다: 상대가 먼저 해제했으면 이제 개인 공간이라 409 NOT_COUPLE
		val spaceId = policy.requireCoupleSpace(requesterId)
		unlinkLocked(spaceId, requesterId, requester.nickname, byWithdrawal = false)
	}

	/**
	 * 탈퇴 1단계: 커플 공간에 있으면 해제하고 상대 ID를 돌려준다. 아니면 null. 탈퇴하는 사용자와 상대의 사용자 행을 잠근 뒤에 부른다
	 * (`UserService.lockForUpdateWithPartner`). [nicknameBeforeWithdrawal]은 상대에게 갈 탈퇴 전용 알림의 이름이다(익명화 전 값).
	 * 탈퇴하는 사람에게는 새 개인 공간을 만들지 않는다: 바로 다음 단계가 개인 공간을 지우므로 결과는 ERD 4.3과 같다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun unlinkForWithdrawal(userId: UUID, nicknameBeforeWithdrawal: String): UUID? {
		if (spaceService.activeSpaceType(userId) != SpaceType.COUPLE) return null
		val spaceId = policy.requireCoupleSpace(userId)
		return unlinkLocked(spaceId, userId, nicknameBeforeWithdrawal, byWithdrawal = true)
	}

	/** 두 사람의 사용자 행을 잠근 뒤에 부른다. 해제한 사람 아닌 쪽(상대)의 ID를 돌려준다 */
	private fun unlinkLocked(spaceId: UUID, actorId: UUID, actorNickname: String, byWithdrawal: Boolean): UUID {
		val now = clock.nowInSeconds()
		checkNotNull(spaceService.lockSpace(spaceId)) { "couple space to unlink is missing" }

		// 2. 커플 공간을 UNLINKED로 (unlinked_at, purge_at +30일), 두 소속에 left_at. 이 뒤로 영속성 컨텍스트가 비워진다
		val memberIds = spaceService.unlinkCoupleSpace(spaceId, now)
		val partnerId = memberIds.single { it != actorId }
		// 3. 그 공간의 일정 알림과 공간에 묶인 알림을 두 사람 모두 지운다 (D-45)
		content.deleteEventReminders(spaceId)
		notifications.deleteOfSpace(spaceId)
		// 4. 두 사람 각자의 개인 공간: 가져오기 대기 공간이 있으면 되살리고, 없으면 새 빈 개인 공간 (D-78)
		memberIds.filter { !(byWithdrawal && it == actorId) }.forEach { spaceService.restoreOrCreatePersonalSpace(it) }
		// 5. 상대에게만 COUPLE_UNLINKED 알림 행 (space_id null이라 3단계 삭제에 지워지지 않는다)
		val notificationId = notifications.createCoupleUnlinked(partnerId, actorNickname, byWithdrawal, now)
		events.publishEvent(CoupleUnlinked(spaceId, actorId, partnerId, byWithdrawal, notificationId))
		return partnerId
	}
}
