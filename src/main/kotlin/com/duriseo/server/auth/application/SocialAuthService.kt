package com.duriseo.server.auth.application

import com.duriseo.server.auth.application.social.SocialIdentity
import com.duriseo.server.auth.application.social.SocialTokenVerifier
import com.duriseo.server.common.access.SpaceAccessPolicy
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.web.AuthenticatedUser
import com.duriseo.server.user.application.SocialAccountService
import com.duriseo.server.user.application.UserService
import com.duriseo.server.user.application.WithdrawalService
import com.duriseo.server.user.domain.AccountType
import com.duriseo.server.user.domain.Nicknames
import com.duriseo.server.user.domain.SocialProvider
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 소셜 로그인·연동·게스트 → 기존 계정 로그인 (API.md 4.2, 4.3, 4.7).
 *
 * 소셜 토큰 검증과 Kakao 닉네임 조회는 외부 호출이라 DB 트랜잭션 밖에서 하고, 검증이 끝난 뒤에 짧은 트랜잭션을 연다 (ARCHITECTURE 4장).
 * 그래서 이 클래스의 메서드는 `@Transactional`이 아니고 [TransactionTemplate]으로 트랜잭션 범위를 정한다.
 * 검증이 실패하면 트랜잭션을 열기 전이므로 아무 행도 바뀌지 않는다 (NFR-SEC-01).
 */
@Service
class SocialAuthService(
	private val verifier: SocialTokenVerifier,
	private val policy: SpaceAccessPolicy,
	private val userService: UserService,
	private val socialAccountService: SocialAccountService,
	private val loginService: LoginService,
	private val withdrawalService: WithdrawalService,
	transactionManager: PlatformTransactionManager,
) {

	private val tx = TransactionTemplate(transactionManager)

	/**
	 * `POST /auth/social` (API.md 4.2): 소셜 계정이 이미 회원이면 그 사용자로, 아니면 새 회원과 개인 공간을 만들어 로그인한다.
	 * 같은 트랜잭션에서 그 사용자의 다른 유효 세션을 `REPLACED`로 끊고 `devices`를 지운다 (`LoginService`).
	 */
	fun login(provider: SocialProvider, token: String): AuthTokens {
		val identity = verifier.verify(provider, token)
		loginExistingMember(identity)?.let { return it }

		// 신규 가입: 닉네임이 필요하므로 이름을 먼저 받는다 (Kakao는 v2/user/me, 트랜잭션 밖)
		val name = identity.profileName()
		return try {
			requireNotNull(
				tx.execute {
					val user = userService.createMember(name)
					socialAccountService.register(user.requireId(), identity.provider, identity.providerUserId)
					loginService.login(user, isNewUser = true)
				},
			)
		} catch (e: DataIntegrityViolationException) {
			// 같은 소셜 계정의 첫 로그인이 동시에 둘 왔다. 먼저 끝난 쪽이 만든 회원으로 로그인한다 (500을 내지 않는다)
			if (!e.violatesSocialAccountUnique()) throw e
			loginExistingMember(identity) ?: throw e
		}
	}

	/**
	 * `POST /auth/link` (API.md 4.3): 게스트 → 회원. 같은 사용자가 MEMBER가 되고 세션·`sid`·`devices`는 그대로이며 새 토큰은 없다.
	 * R-14를 소셜 토큰 검증보다 먼저 검사한다. 그 소셜 계정을 이미 다른 회원이 쓰면(UNIQUE 위반) 409 SOCIAL_ALREADY_USED이고 아무것도 바뀌지 않는다.
	 */
	fun link(requester: AuthenticatedUser, provider: SocialProvider, token: String) {
		policy.requireGuest(requester.userId)
		val identity = verifier.verify(provider, token)

		// 닉네임이 정확히 "게스트"일 때만 소셜 이름이 필요하다 (Kakao는 이때만 v2/user/me를 부른다)
		val guest = userService.findActive(requester.userId) ?: throw ApiException(ErrorCode.SESSION_INVALID, "user not found")
		val name = if (guest.nickname == Nicknames.GUEST_DEFAULT) identity.profileName() else null

		try {
			tx.executeWithoutResult {
				policy.requireGuest(requester.userId)
				socialAccountService.register(requester.userId, identity.provider, identity.providerUserId)
				userService.promoteGuestToMember(requester.userId, name)
			}
		} catch (e: DataIntegrityViolationException) {
			if (e.violatesSocialAccountUnique()) {
				throw ApiException(ErrorCode.SOCIAL_ALREADY_USED, "social account is already used by another member")
			}
			throw e
		}
	}

	/**
	 * `POST /auth/switch` (API.md 4.7, ERD DB-12): 게스트 계정과 기록을 탈퇴와 같이 지우고 그 소셜 계정의 회원으로 로그인한다.
	 * 한 트랜잭션이다: 소셜 계정을 다시 조회하고 대상 회원 행을 잠근 뒤(그 사이 탈퇴했을 수 있다) 게스트 탈퇴 → 이전 세션·기기 정리 → 새 세션.
	 * 대상이 없거나 탈퇴했으면 롤백하고 404 SOCIAL_NOT_REGISTERED, 중간에 실패해도 게스트는 그대로 남는다.
	 * 이 경로는 닉네임이 필요 없으므로 Kakao `v2/user/me`를 부르지 않는다.
	 */
	fun switchToExistingAccount(requester: AuthenticatedUser, provider: SocialProvider, token: String): AuthTokens {
		policy.requireGuest(requester.userId)
		val identity = verifier.verify(provider, token)
		return requireNotNull(
			tx.execute {
				val targetId = socialAccountService.findUserId(identity.provider, identity.providerUserId) ?: throw notRegistered()
				val target = userService.lockForUpdate(targetId)
				if (target.deletedAt != null || target.accountType != AccountType.MEMBER) throw notRegistered()

				// 잠근 뒤 한 번 더 확인한다: 그 사이 게스트가 연동했거나 탈퇴했으면 지우면 안 된다
				userService.lockForUpdate(requester.userId)
				policy.requireGuest(requester.userId)

				withdrawalService.eraseInTransaction(requester.userId)
				loginService.login(target, isNewUser = false)
			},
		)
	}

	/** 소셜 계정이 이미 회원이면 그 사용자로 로그인한다. 아니거나 그 사이 탈퇴했으면 null */
	private fun loginExistingMember(identity: SocialIdentity): AuthTokens? =
		requireNotNull(
			tx.execute {
				val userId = socialAccountService.findUserId(identity.provider, identity.providerUserId)
				val user = userId?.let { userService.lockForUpdate(it) }
				if (user == null || user.deletedAt != null) {
					ExistingLogin(null)
				} else {
					ExistingLogin(loginService.login(user, isNewUser = false))
				}
			},
		).tokens

	/** 트랜잭션 콜백이 null 대신 돌려주는 결과 */
	private class ExistingLogin(val tokens: AuthTokens?)

	private fun notRegistered() = ApiException(ErrorCode.SOCIAL_NOT_REGISTERED, "no member uses this social account")

	/** `uq_social_accounts_provider_user` 위반인가 (제약 이름은 마이그레이션이 정한 것이라 바꾸지 않는다) */
	private fun Throwable.violatesSocialAccountUnique(): Boolean =
		generateSequence(this) { it.cause?.takeIf { cause -> cause !== it } }
			.any { it.message?.contains(SOCIAL_ACCOUNT_UNIQUE_CONSTRAINT) == true }

	private companion object {
		const val SOCIAL_ACCOUNT_UNIQUE_CONSTRAINT = "uq_social_accounts_provider_user"
	}
}
