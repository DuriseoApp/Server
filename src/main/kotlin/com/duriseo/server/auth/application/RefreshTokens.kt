package com.duriseo.server.auth.application

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

/**
 * refresh 토큰: 보안 난수 32바이트를 base64url(패딩 없음)로 인코딩한 43자 문자열이다 (API.md 1장, D-81).
 * DB에는 원문 대신 SHA-256 16진수 64자만 저장한다.
 */
object RefreshTokens {

	private val random = SecureRandom()

	fun generate(): String {
		val bytes = ByteArray(32)
		random.nextBytes(bytes)
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
	}

	fun hash(token: String): String {
		val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(StandardCharsets.UTF_8))
		return HexFormat.of().formatHex(digest)
	}
}
