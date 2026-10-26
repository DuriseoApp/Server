package com.duriseo.server.user.application

import com.duriseo.server.auth.application.SessionService
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.time.nowInSeconds
import com.duriseo.server.space.application.SpaceService
import com.duriseo.server.space.domain.SpaceType
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
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
	private val sessionService: SessionService,
	private val deviceService: DeviceService,
	private val dataEraser: WithdrawalDataEraser,
	private val events: ApplicationEventPublisher,
	private val clock: Clock,
) {

	/** `DELETE /me`: 로그인한 사용자 누구나(게스트 포함) 탈퇴할 수 있다 */
	@Transactional
	fun withdraw(userId: UUID) {
		eraseInTransaction(userId)
	}

	/** 이미 열린 트랜잭션 안에서 탈퇴 처리를 한다. 사용자 행을 잠근 뒤 시작한다 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun eraseInTransaction(userId: UUID) {
		val user = userService.lockForUpdate(userId)
		if (user.deletedAt != null) throw ApiException(ErrorCode.SESSION_INVALID, "user already withdrawn")
		val nicknameBeforeWithdrawal = user.nickname
		val profileImageKey = user.profileImageKey
		// 소셜 계정 행을 지우기 전에 읽어 둔다 (Kakao 연결 끊기에 쓴다)
		val kakaoProviderUserId = socialAccountService.kakaoProviderUserIdOf(userId)

		// 1. 커플 공간이면 연결 해제 (단위 2가 채운다)
		val partnerUserId = unlinkCoupleBeforeWithdrawal(userId)
		// 2. 속했던 UNLINKED 커플 공간을 탈퇴 시각 + 90일까지 보관
		spaceService.extendPurgeOfUnlinkedSpacesForWithdrawal(userId)
		// 3. 개인 공간과 가져오기 대기 중인 개인 공간 삭제 (콘텐츠·공간 알림은 CASCADE)
		spaceService.deletePersonalSpacesOf(userId)
		// 4. 익명화: 세션은 WITHDRAWN으로 무효화한 뒤 지우고, 나머지 행을 지운다
		sessionService.revokeAndDeleteAllForWithdrawal(userId)
		deviceService.deleteAllOf(userId)
		dataEraser.eraseUserRows(userId)
		user.anonymize(clock.nowInSeconds())

		events.publishEvent(UserWithdrawn(userId, nicknameBeforeWithdrawal, profileImageKey, partnerUserId, kakaoProviderUserId))
	}

	/**
	 * 탈퇴 1단계 (ERD 4.3): 커플 공간에 있으면 4.5로 연결 해제하고, 상대에게 탈퇴 전용 `COUPLE_UNLINKED` 알림을 만든다.
	 * 연결 해제는 단위 2, 알림 푸시·SYNC는 단위 6이 채운다. 돌려주는 값은 상대의 사용자 ID다.
	 * 단위 2가 오기 전에는 커플 공간이 생길 수 없으므로 만나면 데이터 오류로 보고 실패시킨다 (탈퇴 전체가 롤백된다).
	 */
	private fun unlinkCoupleBeforeWithdrawal(userId: UUID): UUID? {
		check(spaceService.activeSpaceType(userId) != SpaceType.COUPLE) {
			"withdrawal of a user in a couple space needs the unlink step (unit 2)"
		}
		return null
	}
}
