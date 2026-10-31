package com.duriseo.server.user.application

import com.duriseo.server.auth.application.SessionService
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.time.nowInSeconds
import com.duriseo.server.couple.application.CoupleUnlinkService
import com.duriseo.server.space.application.SpaceService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

/**
 * 탈퇴 (FR-AUTH-06, API.md 4.6, ERD 4.3). 한 트랜잭션에서 연결 해제 → 속했던 커플 공간 `purge_at` +90일 → 개인 공간 삭제 → 사용자 익명화를 한다.
 * 게스트 → 기존 계정 로그인(switch)의 게스트 삭제와 게스트 정리 작업(단위 6, DB-10)도 [eraseInTransaction]을 그대로 부른다.
 * 외부 호출(Kakao 연결 끊기)은 여기서 하지 않고 [UserWithdrawn]을 발행해 커밋 뒤 리스너가 한다.
 */
@Service
class WithdrawalService(
	private val userService: UserService,
	private val socialAccountService: SocialAccountService,
	private val spaceService: SpaceService,
	private val coupleUnlinkService: CoupleUnlinkService,
	private val sessionService: SessionService,
	private val deviceService: DeviceService,
	private val dataEraser: WithdrawalDataEraser,
	private val events: ApplicationEventPublisher,
	private val clock: Clock,
	transactionManager: PlatformTransactionManager,
) {

	private val tx = TransactionTemplate(transactionManager)

	/**
	 * `DELETE /me`: 로그인한 사용자 누구나(게스트 포함) 탈퇴할 수 있다.
	 * 잠그는 동안 커플 상대가 바뀌면 트랜잭션을 롤백하고 처음부터 다시 한다 ([PartnerLockRetry]).
	 */
	fun withdraw(userId: UUID) {
		PartnerLockRetry.run { tx.executeWithoutResult { eraseInTransaction(userId) } }
	}

	/**
	 * 이미 열린 트랜잭션 안에서 탈퇴 처리를 한다. 사용자 행과 커플 상대의 행을 UUID 순서로 잠근 뒤 시작한다 (연결 해제와 같은 순서).
	 * [unlinkFromKakao]가 false면 커밋 뒤 Kakao 연결 끊기를 하지 않는다: Kakao가 이미 연결을 끊은 뒤 부른 웹훅 탈퇴다 (API.md 4.8 6단계).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun eraseInTransaction(userId: UUID, unlinkFromKakao: Boolean = true) {
		val user = userService.lockForUpdateWithPartner(userId)
		if (user.deletedAt != null) throw ApiException(ErrorCode.SESSION_INVALID, "user already withdrawn")
		val nicknameBeforeWithdrawal = user.nickname
		val profileImageKey = user.profileImageKey
		// 소셜 계정 행을 지우기 전에 읽어 둔다 (Kakao 연결 끊기에 쓴다)
		val kakaoProviderUserId = if (unlinkFromKakao) socialAccountService.kakaoProviderUserIdOf(userId) else null

		// 1. 커플 공간이면 연결 해제하고 상대에게 탈퇴 전용 COUPLE_UNLINKED 알림을 만든다
		val partnerUserId = coupleUnlinkService.unlinkForWithdrawal(userId, nicknameBeforeWithdrawal)
		// 2. 속했던 UNLINKED 커플 공간을 탈퇴 시각 + 90일까지 보관
		spaceService.extendPurgeOfUnlinkedSpacesForWithdrawal(userId)
		// 3. 개인 공간과 가져오기 대기 중인 개인 공간 삭제 (콘텐츠·공간 알림은 CASCADE)
		spaceService.deletePersonalSpacesOf(userId)
		// 4. 익명화: 세션은 WITHDRAWN으로 무효화한 뒤 지우고, 나머지 행을 지운다
		sessionService.revokeAndDeleteAllForWithdrawal(userId)
		deviceService.deleteAllOf(userId)
		dataEraser.eraseUserRows(userId)
		// 해제와 알림·초대코드 삭제가 영속성 컨텍스트를 비워 앞에서 읽은 엔티티는 더 이상 추적되지 않는다. 같은 잠금을 다시 잡아 새로 읽는다
		userService.lockForUpdate(userId).anonymize(clock.nowInSeconds())

		events.publishEvent(UserWithdrawn(userId, nicknameBeforeWithdrawal, profileImageKey, partnerUserId, kakaoProviderUserId))
	}
}
