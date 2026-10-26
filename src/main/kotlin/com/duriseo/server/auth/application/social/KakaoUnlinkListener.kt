package com.duriseo.server.auth.application.social

import com.duriseo.server.user.application.UserWithdrawn
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Kakao 회원이 탈퇴하면 탈퇴 트랜잭션이 커밋된 뒤에 Kakao 앱 연결을 끊는다 (FR-AUTH-06.AC3, API.md 4.6, ERD 4.3).
 * 호출이 실패하거나 시간이 초과돼도 이미 끝난 탈퇴를 되돌리지 않고 실패했다는 사실만 로그에 남긴다.
 * 어드민 키·토큰·Kakao 사용자 ID는 로그에 쓰지 않는다. 트랜잭션이 롤백되면 이 리스너는 불리지 않는다.
 */
@Component
class KakaoUnlinkListener(private val unlinker: KakaoUnlinker) {

	private val log = LoggerFactory.getLogger(javaClass)

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun onWithdrawn(event: UserWithdrawn) {
		val kakaoUserId = event.kakaoProviderUserId ?: return
		try {
			unlinker.unlink(kakaoUserId)
		} catch (e: Exception) {
			val reason = if (e is KakaoRejectedException) "${e.javaClass.simpleName} status=${e.status}" else e.javaClass.simpleName
			log.warn("Kakao unlink failed after withdrawal of user {} ({})", event.userId, reason)
		}
	}
}
