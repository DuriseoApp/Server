package com.duriseo.server.auth

import com.duriseo.server.auth.application.AccessTokenCodec
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.config.DuriseoProperties
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** DB 없이 access 토큰 발급·검증만 본다 (NFR-SEC-01) */
class AccessTokenCodecTest {

	private val now = Instant.parse("2026-10-26T00:00:00Z")

	private fun codec(secret: String) = AccessTokenCodec(DuriseoProperties(jwt = DuriseoProperties.Jwt(secret)))

	@Test
	fun `NFR-SEC-01 JWT 비밀 키가 비어 있거나 32바이트 미만이면 시작하지 않는다`() {
		assertFailsWith<IllegalArgumentException> { codec("") }
		assertFailsWith<IllegalArgumentException> { codec("a".repeat(31)) }
		codec("a".repeat(32)) // 정확히 32바이트는 허용
	}

	@Test
	fun `NFR-SEC-01 발급한 access 토큰은 같은 키로 검증하면 sub와 sid가 그대로 나온다`() {
		val codec = codec("fake-secret-for-unit-test-0123456789abcdef")
		val userId = UUID.randomUUID()
		val sessionId = UUID.randomUUID()

		val issued = codec.issue(userId, sessionId, now)
		val claims = codec.parse(issued.token, now.plusSeconds(10))

		assertEquals(userId, claims.userId)
		assertEquals(sessionId, claims.sessionId)
		assertEquals(now.plus(Duration.ofHours(1)), issued.expiresAt)
	}

	@Test
	fun `NFR-SEC-01 exp 시각부터는 TOKEN_EXPIRED다`() {
		val codec = codec("fake-secret-for-unit-test-0123456789abcdef")
		val issued = codec.issue(UUID.randomUUID(), UUID.randomUUID(), now)

		codec.parse(issued.token, issued.expiresAt.minusSeconds(1))
		val error = assertFailsWith<ApiException> { codec.parse(issued.token, issued.expiresAt) }
		assertEquals(ErrorCode.TOKEN_EXPIRED, error.code)
	}

	@Test
	fun `NFR-SEC-01 다른 키로 서명한 토큰은 SESSION_INVALID다`() {
		val issued = codec("fake-secret-for-unit-test-0123456789abcdef").issue(UUID.randomUUID(), UUID.randomUUID(), now)

		val error = assertFailsWith<ApiException> {
			codec("another-fake-secret-for-unit-test-0123456789").parse(issued.token, now)
		}
		assertEquals(ErrorCode.SESSION_INVALID, error.code)
	}
}
