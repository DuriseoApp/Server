package com.duriseo.server.support

import com.duriseo.server.config.DuriseoProperties
import com.jayway.jsonpath.JsonPath
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.Date
import java.util.HexFormat
import java.util.UUID

/** `/auth/guest`나 `/auth/refresh`가 돌려준 토큰 */
data class Tokens(
	val accessToken: String,
	val refreshToken: String,
	val accessTokenExpiresAt: String,
	val refreshTokenExpiresAt: String,
) {
	val userId: UUID get() = UUID.fromString(claims().subject)
	val sessionId: UUID get() = UUID.fromString(claims().getStringClaim("sid"))

	private fun claims(): JWTClaimsSet = SignedJWT.parse(accessToken).getJWTClaimsSet()
}

/**
 * 통합 테스트(MockMvc + Testcontainers PostgreSQL)가 함께 쓰는 도구.
 * 구체적인 테스트 클래스에 `@SpringBootTest`와 `@Import(TestcontainersConfig::class, TestClockConfig::class)`를 붙인다.
 * (같은 설정이라 Spring 컨텍스트와 PostgreSQL 컨테이너를 테스트 클래스끼리 공유한다.)
 */
abstract class IntegrationTestSupport {

	@Autowired
	protected lateinit var jdbc: JdbcTemplate

	@Autowired
	protected lateinit var clock: MutableClock

	@Autowired
	protected lateinit var properties: DuriseoProperties

	@Autowired
	private lateinit var context: WebApplicationContext

	protected lateinit var mockMvc: MockMvc

	@BeforeEach
	fun resetState() {
		// users와 spaces를 비우면 CASCADE로 FK가 걸린 나머지 테이블도 모두 비워진다
		jdbc.execute("truncate table users, spaces cascade")
		clock.set(START)
		mockMvc = MockMvcBuilders.webAppContextSetup(context).build()
	}

	// ── 요청 ────────────────────────────────────────────

	protected fun startGuest(): Tokens {
		val result = mockMvc.perform(post("$API/auth/guest")).andExpect(status().isCreated).andReturn()
		return tokensOf(result.response.getContentAsString(Charsets.UTF_8))
	}

	protected fun tokensOf(body: String) = Tokens(
		accessToken = JsonPath.read<String>(body, "$.accessToken"),
		refreshToken = JsonPath.read<String>(body, "$.refreshToken"),
		accessTokenExpiresAt = JsonPath.read<String>(body, "$.accessTokenExpiresAt"),
		refreshTokenExpiresAt = JsonPath.read<String>(body, "$.refreshTokenExpiresAt"),
	)

	protected fun refresh(refreshToken: String): ResultActions =
		mockMvc.perform(
			post("$API/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""{"refreshToken": "$refreshToken"}"""),
		)

	protected fun getMe(accessToken: String): ResultActions =
		mockMvc.perform(get("$API/me").header("Authorization", "Bearer $accessToken"))

	protected fun patchMe(accessToken: String, json: String): ResultActions =
		mockMvc.perform(
			patch("$API/me").header("Authorization", "Bearer $accessToken").contentType(MediaType.APPLICATION_JSON).content(json),
		)

	protected fun putDevice(accessToken: String, json: String): ResultActions =
		mockMvc.perform(
			put("$API/me/devices").header("Authorization", "Bearer $accessToken").contentType(MediaType.APPLICATION_JSON).content(json),
		)

	protected fun logout(accessToken: String): ResultActions =
		mockMvc.perform(post("$API/auth/logout").header("Authorization", "Bearer $accessToken"))

	/** 오류 객체의 상태와 `code`를 확인한다 (API.md 1.1) */
	protected fun ResultActions.expectError(httpStatus: Int, code: String): ResultActions =
		andExpect(status().`is`(httpStatus)).andExpect(jsonPath("$.code").value(code))

	// ── 토큰 ────────────────────────────────────────────

	/** 서명 키와 클레임을 마음대로 정해 만든 access 토큰. 위조·클레임 누락 테스트용 */
	protected fun craftToken(
		secret: String = properties.jwt.secret,
		sub: String? = UUID.randomUUID().toString(),
		sid: String? = UUID.randomUUID().toString(),
		iat: Instant? = clock.instant(),
		exp: Instant? = clock.instant().plusSeconds(3600),
	): String {
		val builder = JWTClaimsSet.Builder()
		if (sub != null) builder.subject(sub)
		if (sid != null) builder.claim("sid", sid)
		if (iat != null) builder.issueTime(Date.from(iat))
		if (exp != null) builder.expirationTime(Date.from(exp))
		val claims = builder.build()
		val jwt = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims)
		jwt.sign(MACSigner(secret.toByteArray(Charsets.UTF_8)))
		return jwt.serialize()
	}

	protected fun sha256Hex(value: String): String =
		HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

	// ── DB 확인·픽스처 ──────────────────────────────────

	protected fun count(sql: String, vararg args: Any): Int = jdbc.queryForObject(sql, Long::class.java, *args)!!.toInt()

	protected fun instantOf(sql: String, vararg args: Any): Instant =
		jdbc.queryForObject(sql, Timestamp::class.java, *args)!!.toInstant()

	protected fun ts(instant: Instant): Timestamp = Timestamp.from(instant)

	/** 게스트 연동(2부)이 한 일을 흉내 낸다: 계정 종류를 MEMBER로 바꾸고 소셜 계정 행을 넣는다 */
	protected fun makeMember(userId: UUID, provider: String = "GOOGLE") {
		jdbc.update("update users set account_type = 'MEMBER' where id = ?", userId)
		jdbc.update(
			"insert into social_accounts (id, user_id, provider, provider_user_id, created_at) values (?, ?, ?, ?, ?)",
			UUID.randomUUID(), userId, provider, "fake-$provider-${UUID.randomUUID()}", ts(clock.instant()),
		)
	}

	protected fun insertUser(accountType: String, nickname: String = "테스트", deleted: Boolean = false): UUID {
		val id = UUID.randomUUID()
		jdbc.update(
			"insert into users (id, account_type, nickname, created_at, updated_at) values (?, ?, ?, ?, ?)",
			id, accountType, nickname, ts(clock.instant()), ts(clock.instant()),
		)
		if (deleted) jdbc.update("update users set deleted_at = ? where id = ?", ts(clock.instant()), id)
		return id
	}

	/** [type]이 COUPLE이고 [unlinked]면 UNLINKED 공간으로 만든다 (purge_at = 해제 + 30일) */
	protected fun insertSpace(type: String = "PERSONAL", unlinked: Boolean = false): UUID {
		val id = UUID.randomUUID()
		val now = ts(clock.instant())
		if (unlinked) {
			jdbc.update(
				"insert into spaces (id, type, status, unlinked_at, purge_at, created_at, updated_at) values (?, 'COUPLE', 'UNLINKED', ?, ?, ?, ?)",
				id, now, ts(clock.instant().plusSeconds(30L * 24 * 3600)), now, now,
			)
		} else {
			jdbc.update("insert into spaces (id, type, created_at, updated_at) values (?, ?, ?, ?)", id, type, now, now)
		}
		return id
	}

	/** [left]이면 이미 떠난 소속(`left_at` 있음)으로 넣는다 */
	protected fun insertMember(spaceId: UUID, userId: UUID, color: String = "pick-orange", left: Boolean = false): UUID {
		val id = UUID.randomUUID()
		jdbc.update(
			"insert into space_members (id, space_id, user_id, color, joined_at) values (?, ?, ?, ?, ?)",
			id, spaceId, userId, color, ts(clock.instant()),
		)
		if (left) jdbc.update("update space_members set left_at = ? where id = ?", ts(clock.instant()), id)
		return id
	}

	companion object {
		const val API = "/api/v1"
		val START: Instant = Instant.parse("2026-10-26T00:00:00Z")
	}
}
