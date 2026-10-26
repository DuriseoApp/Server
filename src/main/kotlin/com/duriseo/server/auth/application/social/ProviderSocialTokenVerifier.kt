package com.duriseo.server.auth.application.social

import com.duriseo.server.user.domain.SocialProvider
import org.springframework.stereotype.Component

/** 제공자에 맞는 검증기로 보낸다 */
@Component
class ProviderSocialTokenVerifier(
	private val google: GoogleIdTokenVerifier,
	private val kakao: KakaoTokenVerifier,
) : SocialTokenVerifier {

	override fun verify(provider: SocialProvider, token: String): SocialIdentity = when (provider) {
		SocialProvider.GOOGLE -> google.verify(token)
		SocialProvider.KAKAO -> kakao.verify(token)
	}
}
