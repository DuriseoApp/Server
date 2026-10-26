package com.duriseo.server.auth

import com.duriseo.server.auth.application.social.GoogleIdTokenVerifier
import com.duriseo.server.auth.application.social.GoogleKeySource
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.config.DuriseoProperties
import com.duriseo.server.support.LogCapture
import com.duriseo.server.user.domain.SocialProvider
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Google ID 토큰 검증 (API.md 4.2 2단계, NFR-SEC-01). 가짜 RSA 키로 서명하고 공개키 목록을 주입해 확인한다 (네트워크 없음).
 * 서명(Google 공개키), `iss`(두 값 모두 허용), `aud`(둘이서 웹 클라이언트 ID), `exp`를 하나씩 어긋나게 만들어 본다.
 */
class GoogleIdTokenVerifierTest {

	private val clientId = "fake-web-client-id.apps.example.invalid"
	private val now: Instant = Instant.parse("2026-10-26T00:00:00Z")
	private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
	private val googleKey: RSAKey = RSAKeyGenerator(2048).keyID("fake-key-1").generate()
	private val strangerKey: RSAKey = RSAKeyGenerator(2048).keyID("fake-key-1").generate()
	private val properties = DuriseoProperties(google = DuriseoProperties.Google(webClientId = clientId))

	private fun verifier(keys: GoogleKeySource = GoogleKeySource { listOf(googleKey.toPublicJWK()) }) =
		GoogleIdTokenVerifier(keys, properties, clock)

	/** 기본값은 모두 정상이다. 테스트가 하나씩 어긋나게 만든다 */
	private fun idToken(
		signer: RSAKey = googleKey,
		kid: String? = "fake-key-1",
		iss: String? = "https://accounts.google.com",
		aud: String? = clientId,
		exp: Instant? = now.plusSeconds(3600),
		sub: String? = "fake-google-sub-1",
		name: String? = "홍길동",
		email: String? = "someone@example.invalid",
	): String {
		val claims = JWTClaimsSet.Builder()
		if (iss != null) claims.issuer(iss)
		if (aud != null) claims.audience(aud)
		if (exp != null) claims.expirationTime(Date.from(exp))
		if (sub != null) claims.subject(sub)
		if (name != null) claims.claim("name", name)
		if (email != null) claims.claim("email", email)
		claims.issueTime(Date.from(now))
		val header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build()
		val jwt = SignedJWT(header, claims.build())
		jwt.sign(RSASSASigner(signer))
		return jwt.serialize()
	}

	private fun assertInvalid(token: String, verifier: GoogleIdTokenVerifier = verifier()) {
		val error = assertFailsWith<ApiException> { verifier.verify(token) }
		assertEquals(ErrorCode.SOCIAL_TOKEN_INVALID, error.code)
		assertEquals(401, error.code.status.value())
	}

	@Test
	fun `NFR-SEC-01 서명 iss aud exp가 모두 맞는 Google ID 토큰은 sub와 name을 돌려준다`() {
		val identity = verifier().verify(idToken())

		assertEquals(SocialProvider.GOOGLE, identity.provider)
		assertEquals("fake-google-sub-1", identity.providerUserId)
		assertEquals("홍길동", identity.profileName())
	}

	@Test
	fun `NFR-SEC-01 Google ID 토큰의 aud가 둘이서 클라이언트 ID가 아니면 401 SOCIAL_TOKEN_INVALID`() {
		assertInvalid(idToken(aud = "another-client-id.apps.example.invalid"))
		assertInvalid(idToken(aud = null))
	}

	@Test
	fun `NFR-SEC-01 Google ID 토큰의 iss가 틀리면 401 SOCIAL_TOKEN_INVALID`() {
		assertInvalid(idToken(iss = "https://evil.example.invalid"))
		assertInvalid(idToken(iss = "accounts.google.com.evil.example.invalid"))
		assertInvalid(idToken(iss = null))
	}

	@Test
	fun `NFR-SEC-01 Google ID 토큰의 iss는 허용하는 두 값을 모두 받는다`() {
		assertEquals("fake-google-sub-1", verifier().verify(idToken(iss = "accounts.google.com")).providerUserId)
		assertEquals("fake-google-sub-1", verifier().verify(idToken(iss = "https://accounts.google.com")).providerUserId)
	}

	@Test
	fun `NFR-SEC-01 Google ID 토큰이 만료됐으면 401 SOCIAL_TOKEN_INVALID`() {
		assertInvalid(idToken(exp = now.minusSeconds(1)))
		// exp 시각 정각부터 만료다
		assertInvalid(idToken(exp = now))
		assertInvalid(idToken(exp = null))
	}

	@Test
	fun `NFR-SEC-01 Google ID 토큰의 서명이 틀리면 401 SOCIAL_TOKEN_INVALID`() {
		// 같은 kid지만 Google이 서명한 키가 아니다
		assertInvalid(idToken(signer = strangerKey))
		// 공개키 목록에 없는 kid
		assertInvalid(idToken(kid = "unknown-key"), verifier(GoogleKeySource { emptyList() }))
	}

	@Test
	fun `NFR-SEC-01 Google ID 토큰이 아니거나 RS256이 아니면 401 SOCIAL_TOKEN_INVALID`() {
		assertInvalid("not-a-jwt")
		// HS256으로 서명한 토큰은 받지 않는다 (알고리즘 바꿔치기)
		val claims = JWTClaimsSet.Builder().issuer("accounts.google.com").audience(clientId)
			.expirationTime(Date.from(now.plusSeconds(3600))).subject("fake-google-sub-1").build()
		val hs = SignedJWT(JWSHeader.Builder(JWSAlgorithm.HS256).keyID("fake-key-1").build(), claims)
		hs.sign(MACSigner("fake-hmac-secret-for-test-0123456789abcdef".toByteArray(Charsets.UTF_8)))
		assertInvalid(hs.serialize())
	}

	@Test
	fun `NFR-SEC-01 Google 공개키를 받지 못하면 401이 아니라 500 INTERNAL_ERROR다`() {
		val down = verifier(GoogleKeySource { throw java.io.IOException("connection timed out") })

		val error = assertFailsWith<ApiException> { down.verify(idToken()) }

		assertEquals(ErrorCode.INTERNAL_ERROR, error.code)
		assertEquals(500, error.code.status.value())
	}

	@Test
	fun `FR-AUTH-07 AC1 Google ID 토큰에 name이 없으면 이름 없음이다`() {
		val identity = verifier().verify(idToken(name = null))

		assertNull(identity.profileName())
	}

	@Test
	fun `NFR-PRIV-01 Google 검증은 email 클레임을 읽거나 로그에 남기지 않는다`() {
		val email = "private-person@example.invalid"
		val token = idToken(email = email)

		LogCapture().use { logs ->
			val identity = verifier().verify(token)
			identity.profileName()
			// 실패하는 경우의 로그도 본다
			runCatching { verifier(GoogleKeySource { throw java.io.IOException("down") }).verify(token) }

			assertFalse(logs.text().contains(email), "이메일이 로그에 남았어요")
			assertFalse(logs.text().contains(token), "토큰이 로그에 남았어요")
			assertFalse(identity.toString().contains(email))
		}
	}
}
