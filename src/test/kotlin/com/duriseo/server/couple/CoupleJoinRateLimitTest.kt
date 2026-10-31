package com.duriseo.server.couple

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.IntegrationTestSupport
import com.duriseo.server.support.Tokens
import com.duriseo.server.support.TestClockConfig
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/** 초대코드 오입력 제한 (FR-COUPLE-02 AC4, NFR-SEC-03, D-83, API.md 4.4 2단계, ERD 3.2 invite_code_attempts) */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class CoupleJoinRateLimitTest : IntegrationTestSupport() {

	private fun codeOf(tokens: Tokens): String =
		JsonPath.read(bodyOf(postInviteCode(tokens.accessToken).andExpect(status().isCreated)), "$.code")

	private fun failures(userId: UUID) = count("select count(*) from invite_code_attempts where user_id = ? and succeeded = false", userId)

	/** [userId]의 실패 시도 [count]개를 [first]부터 1분 간격으로 심는다 */
	private fun insertFailures(userId: UUID, count: Int, first: Instant) {
		repeat(count) { index -> insertAttempt(userId, false, first.plus(Duration.ofMinutes(index.toLong()))) }
	}

	@Test
	fun `FR-COUPLE-02 AC4 1시간 안에 10번 틀리면 11번째 입력은 429 INVITE_RATE_LIMITED이고 details retryAfterSeconds를 준다`() {
		val b = startMember()

		repeat(10) { joinCouple(b.accessToken, "ZZZZZZ").expectError(404, "INVITE_NOT_FOUND") }

		// 10번 모두 같은 시각에 틀렸으니 10번째로 최근 실패도 지금이다: 1시간 뒤에 풀린다
		joinCouple(b.accessToken, "ZZZZZZ")
			.expectError(429, "INVITE_RATE_LIMITED")
			.andExpect(jsonPath("$.details.retryAfterSeconds").value(3600))
		assertEquals(10, failures(b.userId))
	}

	@Test
	fun `FR-COUPLE-02 AC4 retryAfterSeconds는 10번째로 최근 실패 시각 + 1시간까지 남은 초다`() {
		val b = startMember()
		// 실패 12개: 59분 전부터 1분 간격. 가장 최근부터 세어 10번째는 57분 전 → 3분 뒤에 풀린다
		insertFailures(b.userId, 12, clock.instant().minus(Duration.ofMinutes(59)))

		joinCouple(b.accessToken, "ZZZZZZ")
			.expectError(429, "INVITE_RATE_LIMITED")
			.andExpect(jsonPath("$.details.retryAfterSeconds").value(180))
	}

	@Test
	fun `FR-COUPLE-02 AC4 막힌 동안에는 올바른 코드도 429이고 연결되지 않는다`() {
		val a = startMember()
		val b = startMember()
		val code = codeOf(a)
		insertFailures(b.userId, 10, clock.instant().minus(Duration.ofMinutes(30)))
		val before = coupleSnapshot()

		joinCouple(b.accessToken, code).expectError(429, "INVITE_RATE_LIMITED")

		assertEquals(before, coupleSnapshot())
		assertEquals(0, count("select count(*) from spaces where type = 'COUPLE'"))
		// 429 자체는 시도로 남지 않는다
		assertEquals(10, failures(b.userId))
		assertEquals(0, count("select count(*) from invite_code_attempts where succeeded = true"))
	}

	@Test
	fun `FR-COUPLE-02 AC4 막힘이 풀린 뒤에는 다시 입력할 수 있다`() {
		val a = startMember()
		val b = startMember()
		val code = codeOf(a)
		// 가장 오래된 실패가 59분 전: 1분 뒤에 풀린다
		insertFailures(b.userId, 10, clock.instant().minus(Duration.ofMinutes(59)))
		joinCouple(b.accessToken, code).expectError(429, "INVITE_RATE_LIMITED")

		// 정확히 풀리는 시각에는 가장 오래된 실패가 창 밖이다 (attempted_at > now - 1시간)
		clock.advance(Duration.ofMinutes(1))

		joinCouple(b.accessToken, code).andExpect(status().isOk)
	}

	@Test
	fun `FR-COUPLE-02 AC4 풀리기 1초 전에는 아직 429이고 retryAfterSeconds는 1이다`() {
		val b = startMember()
		insertFailures(b.userId, 10, clock.instant().minus(Duration.ofMinutes(59)))
		clock.advance(Duration.ofSeconds(59))

		joinCouple(b.accessToken, "ZZZZZZ")
			.expectError(429, "INVITE_RATE_LIMITED")
			.andExpect(jsonPath("$.details.retryAfterSeconds").value(1))
	}

	@Test
	fun `FR-COUPLE-02 AC4 실패 시도는 연결 요청이 오류로 끝나도 invite_code_attempts에 남는다`() {
		val a = startMember()
		val c = startMember()
		val usedBy = startMember()
		val b = startMember()
		insertInviteCodeRow(a.userId, "AAAAAA", createdAt = clock.instant().minusSeconds(660))
		insertInviteCodeRow(c.userId, "BBBBBB", usedAt = clock.instant(), usedBy = usedBy.userId)
		val own = codeOf(b)
		val coupled = startMember()
		val partner = startMember()
		connect(coupled, partner)

		// 연결에 쓸 수 없는 코드는 모두 실패 한 행이다 (5~9번과 10번)
		joinCouple(b.accessToken, "ZZZZZ").expectError(404, "INVITE_NOT_FOUND") // 형식
		joinCouple(b.accessToken, "ZZZZZZ").expectError(404, "INVITE_NOT_FOUND") // 없음
		joinCouple(b.accessToken, own).expectError(409, "INVITE_SELF")
		joinCouple(b.accessToken, "BBBBBB").expectError(409, "INVITE_USED")
		joinCouple(b.accessToken, "AAAAAA").expectError(409, "INVITE_EXPIRED")
		// 만든 사람이 연결 중인 코드 (UNAVAILABLE): coupled가 만든 코드는 연결 때 무효가 됐지만 만든 사람이 커플이라 9번이다
		insertInviteCodeRow(coupled.userId, "CCCCCC", createdAt = clock.instant().minusSeconds(30), revokedAt = clock.instant())
		joinCouple(b.accessToken, "CCCCCC").expectError(409, "INVITE_UNAVAILABLE")

		assertEquals(6, failures(b.userId))
		assertEquals(0, count("select count(*) from invite_code_attempts where user_id = ? and succeeded = true", b.userId))
	}

	@Test
	fun `FR-COUPLE-02 AC4 성공한 시도는 succeeded true로 남고 실패 횟수에 들어가지 않는다`() {
		val b = startMember()
		// 실패 9번과 성공 1번이 최근 1시간에 있다. 성공은 세지 않으므로 다음 오입력은 429가 아니다
		insertFailures(b.userId, 9, clock.instant().minus(Duration.ofMinutes(30)))
		insertAttempt(b.userId, true, clock.instant().minus(Duration.ofMinutes(10)))

		joinCouple(b.accessToken, "ZZZZZZ").expectError(404, "INVITE_NOT_FOUND")
		// 이제 실패가 10번이다
		joinCouple(b.accessToken, "ZZZZZZ").expectError(429, "INVITE_RATE_LIMITED")
	}

	@Test
	fun `FR-COUPLE-02 AC4 연결에 성공하면 succeeded true 한 행이 남는다`() {
		val a = startMember()
		val b = startMember()

		connect(a, b)

		assertEquals(1, count("select count(*) from invite_code_attempts"))
		assertEquals(1, count("select count(*) from invite_code_attempts where user_id = ? and succeeded = true", b.userId))
		assertEquals(clock.instant(), instantOf("select attempted_at from invite_code_attempts where user_id = ?", b.userId))
	}

	@Test
	fun `FR-COUPLE-02 AC4 다른 사용자의 실패는 세지 않는다`() {
		val other = startMember()
		val b = startMember()
		insertFailures(other.userId, 10, clock.instant().minus(Duration.ofMinutes(30)))

		joinCouple(other.accessToken, "ZZZZZZ").expectError(429, "INVITE_RATE_LIMITED")
		joinCouple(b.accessToken, "ZZZZZZ").expectError(404, "INVITE_NOT_FOUND")
	}

	@Test
	fun `FR-COUPLE-02 AC4 정확히 1시간 전의 실패는 창 밖이라 세지 않는다`() {
		val b = startMember()
		// attempted_at > now - 1시간 이므로 정확히 1시간 전의 실패 10개는 세지 않는다
		repeat(10) { insertAttempt(b.userId, false, clock.instant().minus(Duration.ofHours(1))) }

		joinCouple(b.accessToken, "ZZZZZZ").expectError(404, "INVITE_NOT_FOUND")
	}

	@Test
	fun `FR-COUPLE-02 AC4 R-09 거부와 형식 오류와 429는 시도로 남기지 않는다`() {
		val guest = startGuest()
		val coupledA = startMember()
		val coupledB = startMember()
		connect(coupledA, coupledB)
		val b = startMember()
		insertFailures(b.userId, 10, clock.instant().minus(Duration.ofMinutes(30)))
		val attemptsBefore = count("select count(*) from invite_code_attempts")

		joinCouple(guest.accessToken, "ZZZZZZ").expectError(403, "GUEST_NOT_ALLOWED")
		joinCouple(coupledA.accessToken, "ZZZZZZ").expectError(409, "ALREADY_COUPLED")
		joinCoupleRaw(b.accessToken, "{}").expectError(400, "VALIDATION_FAILED")
		joinCouple(b.accessToken, "ZZZZZZ").expectError(429, "INVITE_RATE_LIMITED")

		assertEquals(attemptsBefore, count("select count(*) from invite_code_attempts"))
	}

	@Test
	fun `FR-COUPLE-02 AC4 같은 사용자의 오입력이 동시에 와도 10번째 실패 뒤 요청은 막힌다`() {
		val b = startMember()
		insertFailures(b.userId, 9, clock.instant().minus(Duration.ofMinutes(30)))

		val statuses = inParallel(List(4) { { statusOf(joinCouple(b.accessToken, "ZZZZZZ")) } })

		// 9번 틀린 상태에서 동시에 4번 입력하면 사용자 행 잠금으로 순서가 정해진다: 하나만 404로 10번째 실패가 되고 나머지는 429
		assertEquals(1, statuses.count { it == 404 }, "statuses=$statuses")
		assertEquals(3, statuses.count { it == 429 }, "statuses=$statuses")
		assertEquals(10, failures(b.userId))
	}
}
