package com.duriseo.server.auth.application

import com.duriseo.server.user.application.PartnerLockRetry
import com.duriseo.server.user.application.SocialAccountService
import com.duriseo.server.user.application.UserService
import com.duriseo.server.user.application.WithdrawalService
import com.duriseo.server.user.domain.SocialProvider
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Kakao 연결 해제 알림 처리 (FR-AUTH-06.AC4, API.md 4.8, ERD DB-15, PRD D-87). 인증은 컨트롤러 앞단(`KakaoWebhookAuthenticator`)이 한다.
 *
 * 1. Kakao 회원번호로 `social_accounts`의 (`KAKAO`, `provider_user_id`) 행을 찾는다. 없으면 아무것도 하지 않는다
 *    (모르는 사용자, 이미 탈퇴함, 같은 알림을 두 번 받음, 서버가 `DELETE /me`에서 연결 끊기를 불러 온 알림).
 * 2. 한 트랜잭션에서 그 사용자 행(과 커플 상대 행)을 잠근다(그 사이 상대가 바뀌면 롤백하고 다시, `PartnerLockRetry`). 잠근 뒤 탈퇴했거나 그 Kakao 행이 사라졌으면 아무것도 하지 않는다.
 * 3. `KAKAO` 밖의 로그인 수단(곧 `GOOGLE`)이 있으면 `KAKAO` 행만 지운다. 세션·기기·데이터는 그대로다.
 * 4. 없으면 `DELETE /me`와 같은 탈퇴를 한다. Kakao가 이미 연결을 끊었으므로 커밋 뒤 Kakao 연결 끊기는 부르지 않는다.
 *
 * `referrer_type`으로 동작을 나누지 않는다. 처리 중 오류는 던지므로(트랜잭션 롤백) 부른 쪽이 ERROR 로그를 남기고 200으로 답한다.
 * 트랜잭션 범위를 정해야 해서 `@Transactional` 대신 [TransactionTemplate]을 쓴다 (사용자 찾기는 트랜잭션 밖).
 */
@Service
class KakaoUnlinkWebhookService(
	private val socialAccountService: SocialAccountService,
	private val userService: UserService,
	private val withdrawalService: WithdrawalService,
	transactionManager: PlatformTransactionManager,
) {

	private val log = LoggerFactory.getLogger(javaClass)
	private val tx = TransactionTemplate(transactionManager)

	fun handle(kakaoUserId: String) {
		val userId = socialAccountService.findUserId(SocialProvider.KAKAO, kakaoUserId) ?: return
		// 잠그는 동안 커플 상대가 바뀌면 트랜잭션을 롤백하고 처음부터 다시 한다
		PartnerLockRetry.run { tx.executeWithoutResult { process(userId, kakaoUserId) } }
	}

	/** 한 트랜잭션 안의 처리 (4.8 4~6단계) */
	private fun process(userId: UUID, kakaoUserId: String) {
		val user = userService.lockForUpdateWithPartner(userId)
		if (user.deletedAt != null) return
		// 잠근 뒤 한 번 더: 그 사이 같은 알림이 먼저 처리돼 Kakao 행이 사라졌을 수 있다
		if (socialAccountService.findUserId(SocialProvider.KAKAO, kakaoUserId) != userId) return

		if (socialAccountService.providersOf(userId).any { it != SocialProvider.KAKAO }) {
			socialAccountService.remove(userId, SocialProvider.KAKAO)
			log.info("Kakao unlink notice removed the KAKAO login of user {}", userId)
		} else {
			withdrawalService.eraseInTransaction(userId, unlinkFromKakao = false)
			log.info("Kakao unlink notice withdrew user {}", userId)
		}
	}
}
