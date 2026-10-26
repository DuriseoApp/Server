package com.duriseo.server.auth.application.social

import com.duriseo.server.user.domain.SocialProvider

/**
 * 제공자가 확인해 준 소셜 계정 (API.md 4.2 2단계). 식별자와 프로필 이름만 다룬다 (NFR-PRIV-01).
 * 이메일 등 다른 값은 받거나 저장하거나 로그에 남기지 않는다.
 */
interface SocialIdentity {

	val provider: SocialProvider

	/** 제공자의 고유 사용자 ID. `social_accounts.provider_user_id`가 된다 */
	val providerUserId: String

	/**
	 * 소셜 프로필 이름. 받지 못했으면 null.
	 * 이름이 필요한 경우(신규 가입, "게스트" 닉네임 교체)에만 부른다. Kakao는 이때 외부 호출을 하므로
	 * 트랜잭션 밖에서 부르고, 제공자 장애면 500 `INTERNAL_ERROR`를 던진다 (API.md 4.2).
	 */
	fun profileName(): String?
}

/**
 * 소셜 토큰 검증 (API.md 4.2 2단계, NFR-SEC-01). 테스트는 이 인터페이스를 가짜로 바꾼다.
 * 토큰이 틀리면 401 `SOCIAL_TOKEN_INVALID`, 제공자 장애(Google 공개키를 못 받음, Kakao 5xx·시간 초과)는 500 `INTERNAL_ERROR`다.
 * DB 트랜잭션 밖에서 부른다 (외부 호출).
 */
interface SocialTokenVerifier {

	fun verify(provider: SocialProvider, token: String): SocialIdentity
}
