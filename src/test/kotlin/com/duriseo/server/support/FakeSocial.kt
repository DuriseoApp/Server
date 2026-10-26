package com.duriseo.server.support

import com.duriseo.server.auth.application.social.KakaoUnlinker
import com.duriseo.server.auth.application.social.SocialIdentity
import com.duriseo.server.auth.application.social.SocialTokenVerifier
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.user.domain.SocialProvider
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 소셜 제공자를 흉내 내는 가짜 검증기. 실제 Google·Kakao 검증은 `GoogleIdTokenVerifierTest`, `KakaoTokenVerifierTest`가 한다.
 * [register]로 등록한 토큰만 통과하고, 그 밖의 토큰은 401 SOCIAL_TOKEN_INVALID다.
 */
class FakeSocialTokenVerifier : SocialTokenVerifier {

	private class Account(val providerUserId: String, val name: String?)

	private val accounts = ConcurrentHashMap<Pair<SocialProvider, String>, Account>()

	/** true면 제공자 장애로 본다 (Google 공개키를 못 받음, Kakao 5xx·시간 초과): 500 INTERNAL_ERROR */
	@Volatile
	var providerDown: Boolean = false

	/** 값이 있으면 가짜 등록 대신 이 검증기로 검증한다. 실제 Google 검증기를 경로에 끼워 볼 때 쓴다 (NFR-PRIV-01) */
	@Volatile
	var delegate: SocialTokenVerifier? = null

	/** 값이 있으면 `profileName`이 여기서 다른 쪽을 기다린다. 첫 로그인 두 건이 겹치는 상황을 만든다 */
	@Volatile
	var nameLookupBarrier: CyclicBarrier? = null

	/** `verify`를 부른 횟수. R-14가 소셜 토큰 검증보다 먼저인지 확인한다 */
	val verifyCalls = AtomicInteger()

	/** `profileName`을 부른 횟수. Kakao `v2/user/me`는 이름이 필요할 때만 부르는지 확인한다 */
	val nameLookups = AtomicInteger()

	fun register(provider: SocialProvider, token: String, providerUserId: String, name: String? = null) {
		accounts[provider to token] = Account(providerUserId, name)
	}

	fun reset() {
		accounts.clear()
		providerDown = false
		delegate = null
		nameLookupBarrier = null
		verifyCalls.set(0)
		nameLookups.set(0)
	}

	override fun verify(provider: SocialProvider, token: String): SocialIdentity {
		verifyCalls.incrementAndGet()
		delegate?.let { return it.verify(provider, token) }
		if (providerDown) throw ApiException(ErrorCode.INTERNAL_ERROR, "fake provider is down")
		val account = accounts[provider to token] ?: throw ApiException(ErrorCode.SOCIAL_TOKEN_INVALID, "fake token is not registered")
		val requestedProvider = provider
		return object : SocialIdentity {
			override val provider: SocialProvider = requestedProvider
			override val providerUserId: String = account.providerUserId
			override fun profileName(): String? {
				nameLookups.incrementAndGet()
				nameLookupBarrier?.await(10, TimeUnit.SECONDS)
				if (providerDown) throw ApiException(ErrorCode.INTERNAL_ERROR, "fake provider is down")
				return account.name
			}
		}
	}
}

/** Kakao 연결 끊기를 흉내 낸다. 부른 `provider_user_id`를 기록하고, [failure]가 있으면 던진다 */
class FakeKakaoUnlinker : KakaoUnlinker {

	val calls = CopyOnWriteArrayList<String>()

	@Volatile
	var failure: RuntimeException? = null

	/** 부를 때 실행한다. 커밋 뒤에 불렸는지(다른 연결에서 커밋된 값이 보이는지) 확인할 때 쓴다 */
	@Volatile
	var onCall: ((String) -> Unit)? = null

	fun reset() {
		calls.clear()
		failure = null
		onCall = null
	}

	override fun unlink(providerUserId: String) {
		calls.add(providerUserId)
		onCall?.invoke(providerUserId)
		failure?.let { throw it }
	}
}

/** 통합 테스트에서 실제 소셜 검증과 Kakao 호출 대신 가짜를 쓴다. `TestClockConfig`가 함께 가져와 컨텍스트가 하나로 공유된다 */
@TestConfiguration(proxyBeanMethods = false)
class TestSocialConfig {

	@Bean
	@Primary
	fun fakeSocialTokenVerifier() = FakeSocialTokenVerifier()

	@Bean
	@Primary
	fun fakeKakaoUnlinker() = FakeKakaoUnlinker()
}
