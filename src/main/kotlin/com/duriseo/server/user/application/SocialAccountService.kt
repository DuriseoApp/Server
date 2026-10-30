package com.duriseo.server.user.application

import com.duriseo.server.common.time.nowInSeconds
import com.duriseo.server.user.domain.SocialAccount
import com.duriseo.server.user.domain.SocialAccountRepository
import com.duriseo.server.user.domain.SocialProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** 회원의 소셜 로그인 수단 (ERD 3.1 social_accounts). 제공자의 사용자 ID만 저장한다 (NFR-PRIV-01) */
@Service
class SocialAccountService(
	private val socialAccounts: SocialAccountRepository,
	private val clock: Clock,
) {

	/** 그 소셜 계정을 쓰는 사용자 ID. 없으면 null */
	@Transactional(readOnly = true)
	fun findUserId(provider: SocialProvider, providerUserId: String): UUID? =
		socialAccounts.findUserIdByProvider(provider, providerUserId)

	/**
	 * 소셜 계정 행을 넣는다. 바로 반영(flush)해서 `uq_social_accounts_provider_user` 위반이 이 호출에서
	 * `DataIntegrityViolationException`으로 나오게 한다. 호출한 쪽이 제약 이름으로 판단한다 (연동 거부, 동시 가입).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun register(userId: UUID, provider: SocialProvider, providerUserId: String) {
		socialAccounts.saveAndFlush(SocialAccount(userId, provider, providerUserId, clock.nowInSeconds()))
	}

	/** 탈퇴 때 Kakao 앱 연결을 끊을 사용자 ID. 행을 지우기 전에 읽어 둔다 (API.md 4.6). Kakao 회원이 아니면 null */
	@Transactional(readOnly = true)
	fun kakaoProviderUserIdOf(userId: UUID): String? =
		socialAccounts.findAllByUserId(userId).firstOrNull { it.provider == SocialProvider.KAKAO }?.providerUserId

	/** 사용자가 쓰는 로그인 수단 */
	@Transactional(readOnly = true)
	fun providersOf(userId: UUID): Set<SocialProvider> = socialAccounts.findAllByUserId(userId).map { it.provider }.toSet()

	/**
	 * 사용자의 [provider] 행만 지운다. 세션·기기·데이터는 그대로다 (Kakao 연결 해제 알림, API.md 4.8 5단계, ERD DB-15).
	 * 바로 반영(flush)한다. 지웠으면 true.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun remove(userId: UUID, provider: SocialProvider): Boolean {
		val account = socialAccounts.findAllByUserId(userId).firstOrNull { it.provider == provider } ?: return false
		socialAccounts.delete(account)
		socialAccounts.flush()
		return true
	}
}
