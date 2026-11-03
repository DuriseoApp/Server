package com.duriseo.server.space

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.IntegrationTestSupport
import com.duriseo.server.support.TestClockConfig
import com.duriseo.server.support.Tokens
import com.jayway.jsonpath.JsonPath
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/**
 * `MeSpace.linkedAt`·`Space.linkedAt`(연결한 날, FR-SET-01 AC1, D-94, DB-17)과 `MeSpace.importCounts`(가져오기 대기 기록 개수,
 * FR-COUPLE-03 AC1·AC2, D-93). API.md 3.1·3.2, ERD 3.2·4.1·4.2.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class SpaceLinkedAtTest : IntegrationTestSupport() {

	private fun linkedAtOfSpace(spaceId: UUID): Instant = instantOf("select linked_at from spaces where id = ?", spaceId)

	private fun codeOf(tokens: Tokens): String =
		JsonPath.read(bodyOf(postInviteCode(tokens.accessToken).andExpect(status().isCreated)), "$.code")

	// ── linkedAt ────────────────────────────────────────

	@Test
	fun `FR-SET-01 AC1 개인 공간의 linkedAt은 null이다`() {
		val guest = startGuest()

		getMe(guest.accessToken)
			.andExpect(status().isOk)
			.andExpect(content().string(containsString("\"linkedAt\":null")))
		getSpace(guest.accessToken)
			.andExpect(status().isOk)
			.andExpect(content().string(containsString("\"linkedAt\":null")))
	}

	@Test
	fun `FR-SET-01 AC1 커플 공간의 linkedAt은 연결한 시각이고 두 사람의 GET me와 GET space가 같다`() {
		val a = startMember("에이")
		val b = startMember("비비")
		clock.advance(Duration.ofMinutes(10))

		val spaceId = connect(a, b)

		assertEquals(Instant.parse("2026-10-26T00:10:00Z"), linkedAtOfSpace(spaceId))
		for (tokens in listOf(a, b)) {
			getMe(tokens.accessToken).andExpect(jsonPath("$.space.linkedAt").value("2026-10-26T00:10:00Z"))
			getSpace(tokens.accessToken).andExpect(jsonPath("$.linkedAt").value("2026-10-26T00:10:00Z"))
		}
	}

	@Test
	fun `FR-SET-01 AC1 연결을 해제해도 해제된 공간의 linked_at은 그대로다`() {
		val a = startMember("에이")
		val b = startMember("비비")
		clock.advance(Duration.ofMinutes(10))
		val spaceId = connect(a, b)
		clock.advance(Duration.ofMinutes(5))

		unlinkCouple(a.accessToken).andExpect(status().isNoContent)

		// 해제·탈퇴 때는 linked_at을 그대로 둔다 (ERD 4.2)
		assertEquals(Instant.parse("2026-10-26T00:10:00Z"), linkedAtOfSpace(spaceId))
		// 해제 뒤 새 개인 공간의 linkedAt은 null이다
		getMe(a.accessToken)
			.andExpect(jsonPath("$.space.type").value("PERSONAL"))
			.andExpect(content().string(containsString("\"linkedAt\":null")))
	}

	@Test
	fun `FR-COUPLE-06 AC1 유예 기간 안에 복구하면 linkedAt이 다시 연결한 시각으로 바뀐다`() {
		val a = startMember("에이")
		val b = startMember("비비")
		// 해제된 이전 커플 공간: 처음 연결한 시각은 START이다
		val old = insertSpace("COUPLE", unlinked = true)
		insertMember(old, a.userId, "pick-teal", left = true)
		insertMember(old, b.userId, "pick-brown", left = true)
		assertEquals(Instant.parse("2026-10-26T00:00:00Z"), linkedAtOfSpace(old))
		// 해제 10일 뒤. access 토큰이 만료돼 새로 받는다
		clock.advance(Duration.ofDays(10))
		val a2 = refreshed(a)
		val b2 = refreshed(b)

		joinCouple(b2.accessToken, codeOf(a2))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.restored").value(true))
			.andExpect(jsonPath("$.spaceId").value(old.toString()))

		// 다시 연결한 시각 (ERD 4.2, DB-17)
		assertEquals(Instant.parse("2026-11-05T00:00:00Z"), linkedAtOfSpace(old))
		getMe(a2.accessToken).andExpect(jsonPath("$.space.linkedAt").value("2026-11-05T00:00:00Z"))
		getSpace(b2.accessToken).andExpect(jsonPath("$.linkedAt").value("2026-11-05T00:00:00Z"))
	}

	// ── importCounts ────────────────────────────────────

	/** 연결 전에 B가 혼자 쓴 기록이 있는 커플. 연결 뒤 B의 개인 공간이 가져오기 대기로 남는다 */
	private class PendingCouple(val a: Tokens, val b: Tokens, val coupleSpaceId: UUID, val personalOfB: UUID)

	private fun pendingCouple(events: Int, anniversaries: Int, memos: Int): PendingCouple {
		val a = startMember("에이")
		val b = startMember("비비")
		val personalOfB = activeSpaceIdOfUser(b.userId)
		repeat(events) { insertEvent(personalOfB, b.userId, "비비의 일정 $it") }
		repeat(anniversaries) { insertAnniversary(personalOfB, b.userId, "비비의 기념일 $it") }
		repeat(memos) { insertMemo(personalOfB, b.userId, "비비의 메모 $it") }
		return PendingCouple(a, b, connect(a, b), personalOfB)
	}

	@Test
	fun `FR-COUPLE-03 AC1 importPending이 true면 importCounts는 대기 공간의 일정·기념일·메모 개수다`() {
		val couple = pendingCouple(events = 2, anniversaries = 1, memos = 3)
		// 커플 공간에 있는 기록은 세지 않는다
		insertEvent(couple.coupleSpaceId, couple.a.userId, "에이의 일정")
		insertMemo(couple.coupleSpaceId, couple.a.userId, "에이의 메모")

		getMe(couple.b.accessToken)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.space.importPending").value(true))
			.andExpect(jsonPath("$.space.importCounts.events").value(2))
			.andExpect(jsonPath("$.space.importCounts.anniversaries").value(1))
			.andExpect(jsonPath("$.space.importCounts.memos").value(3))
	}

	@Test
	fun `FR-COUPLE-03 AC1 importCounts는 기록이 없는 종류도 0으로 보낸다`() {
		val couple = pendingCouple(events = 0, anniversaries = 0, memos = 1)

		getMe(couple.b.accessToken)
			.andExpect(jsonPath("$.space.importPending").value(true))
			.andExpect(jsonPath("$.space.importCounts.events").value(0))
			.andExpect(jsonPath("$.space.importCounts.anniversaries").value(0))
			.andExpect(jsonPath("$.space.importCounts.memos").value(1))
	}

	@Test
	fun `FR-COUPLE-03 AC1 importPending이 false면 importCounts는 null이다`() {
		val couple = pendingCouple(events = 1, anniversaries = 0, memos = 0)

		// 기록이 없어 연결 때 개인 공간이 지워진 A는 대기 공간이 없다
		getMe(couple.a.accessToken)
			.andExpect(jsonPath("$.space.importPending").value(false))
			.andExpect(content().string(containsString("\"importCounts\":null")))
		// 개인 공간 사용자도 마찬가지다
		val guest = startGuest()
		getMe(guest.accessToken)
			.andExpect(jsonPath("$.space.importPending").value(false))
			.andExpect(content().string(containsString("\"importCounts\":null")))
	}

	@Test
	fun `FR-COUPLE-03 AC1 importCounts는 한 사람의 대기 공간만 센다`() {
		// 두 사람 모두 혼자 쓴 기록이 있다
		val a = startMember("에이")
		val b = startMember("비비")
		val personalOfA = activeSpaceIdOfUser(a.userId)
		val personalOfB = activeSpaceIdOfUser(b.userId)
		insertEvent(personalOfA, a.userId)
		insertEvent(personalOfA, a.userId)
		insertMemo(personalOfB, b.userId)
		connect(a, b)

		getMe(a.accessToken)
			.andExpect(jsonPath("$.space.importCounts.events").value(2))
			.andExpect(jsonPath("$.space.importCounts.memos").value(0))
		getMe(b.accessToken)
			.andExpect(jsonPath("$.space.importCounts.events").value(0))
			.andExpect(jsonPath("$.space.importCounts.memos").value(1))
	}

	@Test
	fun `FR-COUPLE-03 AC2 가져오기를 정하면 importPending이 false가 되고 importCounts는 null이 된다`() {
		val couple = pendingCouple(events = 1, anniversaries = 1, memos = 1)

		importRecords(couple.b.accessToken, true).andExpect(status().isNoContent)

		getMe(couple.b.accessToken)
			.andExpect(jsonPath("$.space.importPending").value(false))
			.andExpect(content().string(containsString("\"importCounts\":null")))
	}
}
