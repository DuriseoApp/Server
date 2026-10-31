package com.duriseo.server.couple

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.IntegrationTestSupport
import com.duriseo.server.support.TestClockConfig
import com.jayway.jsonpath.JsonPath
import org.hamcrest.Matchers.matchesPattern
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals

/** 초대코드 만들기·읽기 (FR-COUPLE-01, R-09, API.md 3.2 `InviteCode`, 4.4 1단계) */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class InviteCodeTest : IntegrationTestSupport() {

	private fun activeCodeCount(userId: UUID) =
		count("select count(*) from invite_codes where creator_user_id = ? and used_at is null and revoked_at is null", userId)

	// ── FR-COUPLE-01 AC1 ────────────────────────────────

	@Test
	fun `FR-COUPLE-01 AC1 개인 공간 회원이 POST invite-codes를 부르면 6자리 코드와 10분 뒤 expiresAt, 60초 뒤 reissueAvailableAt을 돌려준다`() {
		val member = startMember()

		postInviteCode(member.accessToken)
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.code").value(matchesPattern("[ABCDEFGHJKMNPQRSTUVWXYZ2-9]{6}")))
			.andExpect(jsonPath("$.expiresAt").value("2026-10-26T00:10:00Z"))
			.andExpect(jsonPath("$.reissueAvailableAt").value("2026-10-26T00:01:00Z"))

		assertEquals(1, count("select count(*) from invite_codes where creator_user_id = ?", member.userId))
		assertEquals(1, activeCodeCount(member.userId))
	}

	@Test
	fun `FR-COUPLE-01 AC1 GET invite-codes current는 내 유효한 코드와 남은 시간·reissueAvailableAt을 돌려준다`() {
		val member = startMember()
		val code = JsonPath.read<String>(bodyOf(postInviteCode(member.accessToken)), "$.code")
		clock.advance(Duration.ofSeconds(20))

		getCurrentInviteCode(member.accessToken)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.code").value(code))
			// 만료와 재요청 가능 시각은 만든 때 기준이다 (시계가 지나도 그대로)
			.andExpect(jsonPath("$.expiresAt").value("2026-10-26T00:10:00Z"))
			.andExpect(jsonPath("$.reissueAvailableAt").value("2026-10-26T00:01:00Z"))
	}

	@Test
	fun `FR-COUPLE-01 AC1 코드를 만든 적이 없으면 GET invite-codes current는 404 NOT_FOUND`() {
		val member = startMember()

		getCurrentInviteCode(member.accessToken).expectError(404, "NOT_FOUND")
	}

	@Test
	fun `FR-COUPLE-01 AC1 만료된 코드만 있으면 GET invite-codes current는 404 NOT_FOUND`() {
		val member = startMember()
		postInviteCode(member.accessToken).andExpect(status().isCreated)

		// 만료 시각(생성 + 10분)이 되면 만료다 (expires_at <= now)
		clock.advance(Duration.ofMinutes(10))

		getCurrentInviteCode(member.accessToken).expectError(404, "NOT_FOUND")
	}

	@Test
	fun `FR-COUPLE-01 AC1 무효가 됐거나 사용된 코드만 있으면 GET invite-codes current는 404 NOT_FOUND`() {
		val revoked = startMember()
		insertInviteCodeRow(revoked.userId, "ABCDEF", revokedAt = clock.instant())
		val used = startMember()
		insertInviteCodeRow(used.userId, "GHJKMN", usedAt = clock.instant(), usedBy = revoked.userId)

		getCurrentInviteCode(revoked.accessToken).expectError(404, "NOT_FOUND")
		getCurrentInviteCode(used.accessToken).expectError(404, "NOT_FOUND")
	}

	@Test
	fun `FR-COUPLE-01 AC1 새 코드가 이미 있는 코드(사용·만료·무효 포함)와 겹치면 다른 코드로 다시 만든다`() {
		val other = startMember()
		insertInviteCodeRow(other.userId, "AAAAAA", usedAt = clock.instant(), usedBy = other.userId)
		insertInviteCodeRow(other.userId, "BBBBBB", createdAt = clock.instant().minusSeconds(3600), revokedAt = clock.instant().minusSeconds(60))
		insertInviteCodeRow(other.userId, "CCCCCC", createdAt = clock.instant().minusSeconds(7200))
		val member = startMember()
		// 세 번 겹친 뒤 겹치지 않는 코드가 나온다
		inviteCodeGenerator.enqueue("AAAAAA", "BBBBBB", "CCCCCC", "DDDDDD")

		postInviteCode(member.accessToken)
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.code").value("DDDDDD"))

		assertEquals(listOf("AAAAAA", "BBBBBB", "CCCCCC", "DDDDDD"), inviteCodeGenerator.generated.toList())
		assertEquals(1, activeCodeCount(member.userId))
	}

	// ── FR-COUPLE-01 AC3 ────────────────────────────────

	@Test
	fun `FR-COUPLE-01 AC3 재요청하면 이전 코드에 revoked_at이 기록되고 새 코드가 나온다`() {
		val member = startMember()
		inviteCodeGenerator.enqueue("AAAAAA", "BBBBBB")
		postInviteCode(member.accessToken).andExpect(jsonPath("$.code").value("AAAAAA"))

		clock.advance(Duration.ofSeconds(60))
		postInviteCode(member.accessToken)
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.code").value("BBBBBB"))
			.andExpect(jsonPath("$.expiresAt").value("2026-10-26T00:11:00Z"))
			.andExpect(jsonPath("$.reissueAvailableAt").value("2026-10-26T00:02:00Z"))

		assertEquals(
			clock.instant(),
			instantOf("select revoked_at from invite_codes where code = 'AAAAAA'"),
		)
		assertEquals(0, count("select count(*) from invite_codes where code = 'BBBBBB' and revoked_at is not null"))
		assertEquals(1, activeCodeCount(member.userId))
		getCurrentInviteCode(member.accessToken).andExpect(jsonPath("$.code").value("BBBBBB"))
	}

	@Test
	fun `FR-COUPLE-01 AC3 마지막 발급 후 60초 안에 재요청하면 429 INVITE_REISSUE_TOO_SOON이고 details retryAfterSeconds는 남은 초다`() {
		val member = startMember()
		postInviteCode(member.accessToken).andExpect(status().isCreated)

		clock.advance(Duration.ofSeconds(18))

		postInviteCode(member.accessToken)
			.expectError(429, "INVITE_REISSUE_TOO_SOON")
			.andExpect(jsonPath("$.details.retryAfterSeconds").value(42))
	}

	@Test
	fun `FR-COUPLE-01 AC3 59초째에는 429이고 retryAfterSeconds는 최소 1이며 60초째에는 재요청할 수 있다`() {
		val member = startMember()
		postInviteCode(member.accessToken).andExpect(status().isCreated)

		clock.advance(Duration.ofSeconds(59))
		postInviteCode(member.accessToken)
			.expectError(429, "INVITE_REISSUE_TOO_SOON")
			.andExpect(jsonPath("$.details.retryAfterSeconds").value(1))

		clock.advance(Duration.ofSeconds(1))
		postInviteCode(member.accessToken).andExpect(status().isCreated)
	}

	@Test
	fun `FR-COUPLE-01 AC3 재요청이 거부되면 코드가 바뀌지 않는다`() {
		val member = startMember()
		val code = JsonPath.read<String>(bodyOf(postInviteCode(member.accessToken)), "$.code")
		clock.advance(Duration.ofSeconds(30))
		val before = coupleSnapshot()

		postInviteCode(member.accessToken).expectError(429, "INVITE_REISSUE_TOO_SOON")

		assertEquals(before, coupleSnapshot())
		getCurrentInviteCode(member.accessToken).andExpect(jsonPath("$.code").value(code))
	}

	@Test
	fun `FR-COUPLE-01 AC3 만료됐지만 revoked_at이 없는 이전 코드도 재요청 때 revoked_at을 기록한다`() {
		val member = startMember()
		inviteCodeGenerator.enqueue("AAAAAA", "BBBBBB")
		postInviteCode(member.accessToken).andExpect(status().isCreated)

		// 10분이 지나 만료됐지만 revoked_at은 아직 null이다. 부분 UNIQUE를 지키려고 새 코드를 넣기 전에 기록해야 한다
		clock.advance(Duration.ofMinutes(11))
		assertEquals(1, activeCodeCount(member.userId))

		postInviteCode(member.accessToken).andExpect(status().isCreated)

		assertEquals(clock.instant(), instantOf("select revoked_at from invite_codes where code = 'AAAAAA'"))
		assertEquals(1, activeCodeCount(member.userId))
	}

	@Test
	fun `FR-COUPLE-01 AC3 같은 사용자의 초대코드 생성이 동시에 와도 유효한 코드는 하나이고 나머지는 429다`() {
		val member = startMember()
		val tasks = List(6) { { statusOf(postInviteCode(member.accessToken)) } }

		val statuses = inParallel(tasks)

		assertEquals(1, statuses.count { it == 201 }, "statuses=$statuses")
		assertEquals(5, statuses.count { it == 429 }, "statuses=$statuses")
		assertEquals(1, activeCodeCount(member.userId))
		assertEquals(1, count("select count(*) from invite_codes where creator_user_id = ?", member.userId))
	}

	// ── FR-COUPLE-01 AC5 ────────────────────────────────

	@Test
	fun `FR-COUPLE-01 AC5 코드가 만료된 뒤의 POST invite-codes는 429 없이 새 코드를 준다`() {
		val member = startMember()
		postInviteCode(member.accessToken).andExpect(status().isCreated)
		clock.advance(Duration.ofMinutes(10))
		getCurrentInviteCode(member.accessToken).expectError(404, "NOT_FOUND")

		postInviteCode(member.accessToken)
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.expiresAt").value("2026-10-26T00:20:00Z"))
	}

	// ── R-09 ────────────────────────────────────────────

	@Test
	fun `R-09 개인 공간 회원은 POST invite-codes, GET invite-codes current, POST couple join을 부를 수 있다`() {
		val creator = startMember()
		val joiner = startMember()
		val code = JsonPath.read<String>(bodyOf(postInviteCode(creator.accessToken)), "$.code")

		getCurrentInviteCode(creator.accessToken).andExpect(status().isOk)
		joinCouple(joiner.accessToken, code).andExpect(status().isOk)
	}

	@Test
	fun `R-09 게스트가 POST invite-codes를 부르면 403 GUEST_NOT_ALLOWED이고 코드가 생기지 않는다`() {
		val guest = startGuest()

		postInviteCode(guest.accessToken).expectError(403, "GUEST_NOT_ALLOWED")

		assertEquals(0, count("select count(*) from invite_codes"))
	}

	@Test
	fun `R-09 게스트가 GET invite-codes current를 부르면 403 GUEST_NOT_ALLOWED`() {
		val guest = startGuest()

		getCurrentInviteCode(guest.accessToken).expectError(403, "GUEST_NOT_ALLOWED")
	}

	@Test
	fun `R-09 커플 공간 회원이 POST invite-codes를 부르면 409 ALREADY_COUPLED`() {
		val a = startMember()
		val b = startMember()
		connect(a, b)
		val before = coupleSnapshot()

		postInviteCode(b.accessToken).expectError(409, "ALREADY_COUPLED")

		assertEquals(before, coupleSnapshot())
	}

	@Test
	fun `R-09 커플 공간 회원이 GET invite-codes current를 부르면 409 ALREADY_COUPLED`() {
		val a = startMember()
		val b = startMember()
		connect(a, b)

		getCurrentInviteCode(a.accessToken).expectError(409, "ALREADY_COUPLED")
	}

	@Test
	fun `R-09 연동한 게스트는 같은 access 토큰으로 바로 초대코드를 만들 수 있다`() {
		val guest = startGuest()
		postInviteCode(guest.accessToken).expectError(403, "GUEST_NOT_ALLOWED")

		// 게스트 연동(POST auth link)이 하는 일: 같은 사용자의 계정 종류가 MEMBER로 바뀐다. 토큰에는 계정 종류가 없다
		makeMember(guest.userId)

		postInviteCode(guest.accessToken).andExpect(status().isCreated)
	}

	@Test
	fun `R-09 연결 직후 같은 access 토큰으로 POST invite-codes를 부르면 409 ALREADY_COUPLED`() {
		val a = startMember()
		val b = startMember()
		connect(a, b)

		// 코드를 만든 A의 토큰은 그대로인데, 계정 상태는 요청마다 DB에서 읽으므로 바로 거부된다
		postInviteCode(a.accessToken).expectError(409, "ALREADY_COUPLED")
	}
}
