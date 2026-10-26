package com.duriseo.server.auth.application.social

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.config.DuriseoProperties
import com.duriseo.server.user.domain.SocialProvider
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Kakao 액세스 토큰 검증 (API.md 4.2 2단계, NFR-SEC-01).
 * `access_token_info`의 `app_id`가 설정된 둘이서 앱 ID와 같을 때만 `id`를 사용자 ID로 쓴다.
 * 4xx면 401 `SOCIAL_TOKEN_INVALID`, 5xx·시간 초과면 500 `INTERNAL_ERROR`.
 * 프로필 닉네임은 [SocialIdentity.profileName]이 불릴 때만 `v2/user/me`로 읽는다 (기존 회원 로그인은 호출하지 않는다).
 */
@Component
class KakaoTokenVerifier(
	private val client: KakaoApiClient,
	private val properties: DuriseoProperties,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	fun verify(token: String): SocialIdentity {
		val info = try {
			client.accessTokenInfo(token)
		} catch (e: KakaoRejectedException) {
			throw ApiException(ErrorCode.SOCIAL_TOKEN_INVALID, "kakao rejected the access token")
		} catch (e: KakaoUnavailableException) {
			log.warn("Kakao access_token_info is unavailable ({})", e.message)
			throw ApiException(ErrorCode.INTERNAL_ERROR, "kakao unavailable")
		}
		val appId = info.appId
		val id = info.id
		if (appId == null || id == null || properties.kakao.appId.isBlank() || appId.toString() != properties.kakao.appId) {
			throw ApiException(ErrorCode.SOCIAL_TOKEN_INVALID, "kakao token was issued for another app")
		}
		return KakaoIdentity(id.toString(), token, client)
	}

	private class KakaoIdentity(
		override val providerUserId: String,
		private val accessToken: String,
		private val client: KakaoApiClient,
	) : SocialIdentity {

		private val log = LoggerFactory.getLogger(javaClass)

		override val provider: SocialProvider = SocialProvider.KAKAO

		/** 4xx면 이름 없음(null), 5xx·시간 초과면 500 `INTERNAL_ERROR` */
		override fun profileName(): String? = try {
			client.nickname(accessToken)
		} catch (e: KakaoRejectedException) {
			null
		} catch (e: KakaoUnavailableException) {
			log.warn("Kakao user/me is unavailable ({})", e.message)
			throw ApiException(ErrorCode.INTERNAL_ERROR, "kakao unavailable")
		}

		// 토큰 원문이 로그에 남지 않게 한다
		override fun toString(): String = "KakaoIdentity"
	}
}
