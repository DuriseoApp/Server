package com.duriseo.server.auth.application

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.config.DuriseoProperties
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Date
import java.util.UUID

data class IssuedAccessToken(val token: String, val expiresAt: Instant)

/** 서명·형식·클레임·만료가 맞는 access 토큰의 내용 */
data class AccessTokenClaims(val userId: UUID, val sessionId: UUID)

/**
 * access 토큰(JWT, HS256) 발급·검증 (API.md 1장, D-81).
 * 클레임은 `sub`(사용자 ID), `sid`(세션 ID), `iat`, `exp`(발급 + 1시간)이다. 계정 종류는 넣지 않는다.
 * 만료 판단은 주입된 시각으로 직접 한다 (테스트에서 Clock을 고정할 수 있게 라이브러리의 시계를 쓰지 않는다).
 */
@Component
class AccessTokenCodec(properties: DuriseoProperties) {

	private val secret: ByteArray = properties.jwt.secret.toByteArray(Charsets.UTF_8)

	init {
		// 비어 있거나 짧은 키로는 서버를 시작하지 않는다 (HS256은 256비트 이상이 필요)
		require(secret.size >= MIN_SECRET_BYTES) { "duriseo.jwt.secret (JWT_SECRET) must be at least $MIN_SECRET_BYTES bytes" }
	}

	private val signer = MACSigner(secret)
	private val verifier = MACVerifier(secret)

	fun issue(userId: UUID, sessionId: UUID, issuedAt: Instant): IssuedAccessToken {
		val expiresAt = issuedAt.plus(TokenLifetimes.ACCESS)
		val claims = JWTClaimsSet.Builder()
			.subject(userId.toString())
			.claim(SESSION_CLAIM, sessionId.toString())
			.issueTime(Date.from(issuedAt))
			.expirationTime(Date.from(expiresAt))
			.build()
		val jwt = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims)
		jwt.sign(signer)
		return IssuedAccessToken(jwt.serialize(), expiresAt)
	}

	/**
	 * API.md 1장의 검사 순서 중 1(형식·서명)과 2(만료)를 한다. 세션 검사(3)는 호출한 쪽이 한다.
	 * 형식·서명·클레임이 틀리면 401 SESSION_INVALID, 서명이 맞고 `exp`가 지났으면 401 TOKEN_EXPIRED.
	 */
	fun parse(token: String, now: Instant): AccessTokenClaims {
		val jwt = try {
			SignedJWT.parse(token)
		} catch (e: Exception) {
			throw invalid("not a JWT")
		}
		if (jwt.header.algorithm != JWSAlgorithm.HS256) throw invalid("unexpected algorithm")
		val signatureOk = try {
			jwt.verify(verifier)
		} catch (e: Exception) {
			false
		}
		if (!signatureOk) throw invalid("bad signature")

		val claims = try {
			jwt.getJWTClaimsSet()
		} catch (e: Exception) {
			throw invalid("unreadable claims")
		}
		val userId = claims.subject.toUuidOrNull() ?: throw invalid("bad sub")
		val rawSessionId: String? = try {
			claims.getStringClaim(SESSION_CLAIM)
		} catch (e: Exception) {
			null
		}
		val sessionId = rawSessionId.toUuidOrNull() ?: throw invalid("bad sid")
		val issuedAt: Date? = claims.issueTime
		if (issuedAt == null) throw invalid("missing iat")
		val expiresAt: Date = claims.expirationTime ?: throw invalid("missing exp")

		if (!now.isBefore(expiresAt.toInstant())) {
			throw ApiException(ErrorCode.TOKEN_EXPIRED, "access token expired")
		}
		return AccessTokenClaims(userId, sessionId)
	}

	private fun invalid(reason: String) = ApiException(ErrorCode.SESSION_INVALID, "invalid access token: $reason")

	private fun String?.toUuidOrNull(): UUID? = this?.let { runCatching { UUID.fromString(it) }.getOrNull() }

	companion object {
		const val SESSION_CLAIM = "sid"
		const val MIN_SECRET_BYTES = 32
	}
}
