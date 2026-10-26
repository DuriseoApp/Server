package com.duriseo.server.auth.application.social

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.config.DuriseoProperties
import com.duriseo.server.user.domain.SocialProvider
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKMatcher
import com.nimbusds.jose.jwk.JWKSelector
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.util.DefaultResourceRetriever
import com.nimbusds.jwt.SignedJWT
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Clock

/** Google 서명 공개키를 찾는 곳. 테스트는 가짜 공개키를 주입한다 */
fun interface GoogleKeySource {

	/** 토큰 헤더(`kid`, `alg`)에 맞는 공개키 후보. 공개키 목록을 받지 못하면 예외를 던진다 */
	fun keysFor(header: JWSHeader): List<JWK>
}

/** Nimbus의 캐시 있는 JWKS 가져오기를 [GoogleKeySource]로 감싼다 */
class JwkGoogleKeySource(private val jwkSource: JWKSource<SecurityContext>) : GoogleKeySource {

	override fun keysFor(header: JWSHeader): List<JWK> =
		jwkSource.get(JWKSelector(JWKMatcher.forJWSHeader(header)), null)
}

@Configuration(proxyBeanMethods = false)
class GoogleKeySourceConfig {

	/** Google JWKS를 캐시해서 읽는다. 짧은 연결·읽기 제한 시간을 둔다 (외부 호출) */
	@Bean
	fun googleKeySource(): GoogleKeySource {
		val retriever = DefaultResourceRetriever(CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS, SIZE_LIMIT_BYTES)
		val source = JWKSourceBuilder.create<SecurityContext>(URI.create(GOOGLE_JWKS_URL).toURL(), retriever).build()
		return JwkGoogleKeySource(source)
	}

	private companion object {
		const val GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs"
		const val CONNECT_TIMEOUT_MILLIS = 2000
		const val READ_TIMEOUT_MILLIS = 3000
		const val SIZE_LIMIT_BYTES = 100 * 1024
	}
}

/**
 * Google ID 토큰 검증 (API.md 4.2 2단계, NFR-SEC-01): 서명(Google 공개키), `iss`(두 값 모두 허용), `aud`(둘이서 웹 클라이언트 ID), `exp`.
 * 통과하면 `sub`를 사용자 ID로, `name` 클레임을 프로필 이름으로 쓴다. `email` 등 다른 클레임은 읽지 않는다 (NFR-PRIV-01).
 * 토큰이 틀리면 401 `SOCIAL_TOKEN_INVALID`, 공개키를 받지 못하면 500 `INTERNAL_ERROR`.
 * 만료 판단은 주입된 시각으로 직접 한다 (테스트에서 Clock을 고정할 수 있게 한다).
 */
@Component
class GoogleIdTokenVerifier(
	private val keys: GoogleKeySource,
	private val properties: DuriseoProperties,
	private val clock: Clock,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	fun verify(token: String): SocialIdentity {
		val jwt = try {
			SignedJWT.parse(token)
		} catch (e: Exception) {
			throw invalid("not a JWT")
		}
		if (jwt.header.algorithm != JWSAlgorithm.RS256) throw invalid("unexpected algorithm")

		val candidates = try {
			keys.keysFor(jwt.header)
		} catch (e: Exception) {
			// 토큰이 아니라 우리 쪽에서 공개키를 못 받은 것이다. 토큰은 로그에 남기지 않는다
			log.warn("Google public keys could not be loaded ({})", e.javaClass.simpleName)
			throw ApiException(ErrorCode.INTERNAL_ERROR, "google public keys unavailable")
		}
		val signatureOk = candidates.filterIsInstance<RSAKey>().any { key ->
			runCatching { jwt.verify(RSASSAVerifier(key)) }.getOrDefault(false)
		}
		if (!signatureOk) throw invalid("bad signature")

		val claims = try {
			jwt.getJWTClaimsSet()
		} catch (e: Exception) {
			throw invalid("unreadable claims")
		}
		if (claims.issuer !in ISSUERS) throw invalid("bad iss")
		val clientId = properties.google.webClientId
		if (clientId.isBlank() || !claims.audience.contains(clientId)) throw invalid("bad aud")
		val expiresAt = claims.expirationTime ?: throw invalid("missing exp")
		if (!clock.instant().isBefore(expiresAt.toInstant())) throw invalid("expired")

		val subject = claims.subject
		if (subject.isNullOrEmpty() || subject.length > MAX_PROVIDER_USER_ID_LENGTH) throw invalid("bad sub")
		val name = try {
			claims.getStringClaim(NAME_CLAIM)
		} catch (e: Exception) {
			null
		}
		return GoogleIdentity(subject, name)
	}

	private fun invalid(reason: String) = ApiException(ErrorCode.SOCIAL_TOKEN_INVALID, "invalid google id token: $reason")

	private companion object {
		val ISSUERS = setOf("accounts.google.com", "https://accounts.google.com")
		const val NAME_CLAIM = "name"

		/** social_accounts.provider_user_id는 varchar(100) */
		const val MAX_PROVIDER_USER_ID_LENGTH = 100
	}
}

private class GoogleIdentity(override val providerUserId: String, private val name: String?) : SocialIdentity {

	override val provider: SocialProvider = SocialProvider.GOOGLE

	override fun profileName(): String? = name

	override fun toString(): String = "GoogleIdentity"
}
