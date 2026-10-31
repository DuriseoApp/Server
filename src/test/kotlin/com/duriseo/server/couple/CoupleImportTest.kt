package com.duriseo.server.couple

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.IntegrationTestSupport
import com.duriseo.server.support.TestClockConfig
import com.duriseo.server.support.Tokens
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertEquals

/**
 * 기록 가져오기 결정 `POST /couple/import` (FR-COUPLE-03, API.md 4.4 9~10단계, R-18).
 * 연결 때 혼자 쓴 기록이 있는 사람의 개인 공간은 소속에 `left_at`이 생긴 채 남는다(가져오기 대기, D-78).
 * 가져오기·가져오지 않기를 고르면 그 공간이 지워진다. 해제와의 경쟁은 `CoupleUnlinkTest`도 본다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class CoupleImportTest : IntegrationTestSupport() {

	/** 연결 전에 B가 혼자 쓴 기록(일정·기념일·메모 하나씩)이 있는 커플. 연결 뒤 B의 개인 공간이 가져오기 대기로 남는다 */
	private class PendingCouple(
		val a: Tokens,
		val b: Tokens,
		val coupleSpaceId: UUID,
		val personalOfB: UUID,
		val eventId: UUID,
		val anniversaryId: UUID,
		val memoId: UUID,
	)

	private fun pendingCouple(): PendingCouple {
		val a = startMember("에이")
		val b = startMember("비비")
		val personalOfB = activeSpaceIdOfUser(b.userId)
		val eventId = insertEvent(personalOfB, b.userId, "비비의 일정")
		val anniversaryId = insertAnniversary(personalOfB, b.userId, "비비의 기념일")
		val memoId = insertMemo(personalOfB, b.userId, "비비의 메모")
		return PendingCouple(a, b, connect(a, b), personalOfB, eventId, anniversaryId, memoId)
	}

	private fun spaceOfRecord(table: String, id: UUID): UUID? =
		jdbc.queryForList("select space_id from $table where id = ?", UUID::class.java, id).firstOrNull()

	// ── FR-COUPLE-03 AC1·AC2 ─────────────────────────────

	@Test
	fun `FR-COUPLE-03 AC1 import true면 개인 공간의 일정·기념일·메모가 author_id를 유지한 채 커플 공간으로 옮겨지고 개인 공간이 지워진다`() {
		val couple = pendingCouple()
		val coupleEventId = insertEvent(couple.coupleSpaceId, couple.a.userId, "에이의 일정")

		importRecords(couple.b.accessToken, true).andExpect(status().isNoContent).andExpect(content().string(""))

		assertEquals(couple.coupleSpaceId, spaceOfRecord("events", couple.eventId))
		assertEquals(couple.coupleSpaceId, spaceOfRecord("anniversaries", couple.anniversaryId))
		assertEquals(couple.coupleSpaceId, spaceOfRecord("memos", couple.memoId))
		// 작성자는 그대로 B다
		assertEquals(1, count("select count(*) from events where id = ? and author_id = ?", couple.eventId, couple.b.userId))
		assertEquals(1, count("select count(*) from anniversaries where id = ? and author_id = ?", couple.anniversaryId, couple.b.userId))
		assertEquals(1, count("select count(*) from memos where id = ? and author_id = ?", couple.memoId, couple.b.userId))
		// 커플 공간에 이미 있던 기록은 그대로
		assertEquals(couple.coupleSpaceId, spaceOfRecord("events", coupleEventId))
		// 개인 공간과 그 소속은 지워진다
		assertEquals(0, count("select count(*) from spaces where id = ?", couple.personalOfB))
		assertEquals(0, count("select count(*) from space_members where space_id = ?", couple.personalOfB))
		getMe(couple.b.accessToken)
			.andExpect(jsonPath("$.space.type").value("COUPLE"))
			.andExpect(jsonPath("$.space.id").value(couple.coupleSpaceId.toString()))
			.andExpect(jsonPath("$.space.importPending").value(false))
	}

	@Test
	fun `FR-COUPLE-03 AC2 import false면 개인 공간과 그 기록·알림이 지워진다`() {
		val couple = pendingCouple()
		insertNotification(couple.b.userId, couple.personalOfB)
		val coupleMemoId = insertMemo(couple.coupleSpaceId, couple.a.userId, "에이의 메모")

		importRecords(couple.b.accessToken, false).andExpect(status().isNoContent).andExpect(content().string(""))

		assertEquals(0, count("select count(*) from spaces where id = ?", couple.personalOfB))
		assertEquals(0, count("select count(*) from events where id = ?", couple.eventId))
		assertEquals(0, count("select count(*) from anniversaries where id = ?", couple.anniversaryId))
		assertEquals(0, count("select count(*) from memos where id = ?", couple.memoId))
		assertEquals(0, count("select count(*) from notifications where space_id = ?", couple.personalOfB))
		// 커플 공간의 기록과 연결 알림은 그대로다
		assertEquals(couple.coupleSpaceId, spaceOfRecord("memos", coupleMemoId))
		assertEquals(2, count("select count(*) from notifications where type = 'COUPLE_LINKED' and space_id is null"))
		getMe(couple.b.accessToken).andExpect(jsonPath("$.space.importPending").value(false))
	}

	@Test
	fun `FR-COUPLE-03 AC3 한 사람의 가져오기 결정은 상대의 대기 중인 개인 공간을 바꾸지 않는다`() {
		val a = startMember("에이")
		val b = startMember("비비")
		val personalOfA = activeSpaceIdOfUser(a.userId)
		val personalOfB = activeSpaceIdOfUser(b.userId)
		val memoOfA = insertMemo(personalOfA, a.userId, "에이의 메모")
		val memoOfB = insertMemo(personalOfB, b.userId, "비비의 메모")
		val coupleSpaceId = connect(a, b)

		importRecords(b.accessToken, true).andExpect(status().isNoContent)

		// A의 대기 공간과 기록, importPending은 그대로다
		assertEquals(personalOfA, spaceOfRecord("memos", memoOfA))
		assertEquals(1, count("select count(*) from space_members where space_id = ? and user_id = ? and left_at is not null", personalOfA, a.userId))
		getMe(a.accessToken).andExpect(jsonPath("$.space.importPending").value(true))
		getMe(b.accessToken).andExpect(jsonPath("$.space.importPending").value(false))
		assertEquals(coupleSpaceId, spaceOfRecord("memos", memoOfB))

		// A는 자기 몫만 정한다
		importRecords(a.accessToken, false).andExpect(status().isNoContent)
		assertEquals(0, count("select count(*) from memos where id = ?", memoOfA))
		assertEquals(coupleSpaceId, spaceOfRecord("memos", memoOfB))
		getMe(a.accessToken).andExpect(jsonPath("$.space.importPending").value(false))
	}

	// ── FR-COUPLE-03 AC4, R-18 ───────────────────────────

	@Test
	fun `FR-COUPLE-03 AC4 대기 중인 개인 공간이 없으면 POST couple import는 409 NO_IMPORT_PENDING이고 아무것도 바꾸지 않는다`() {
		// 개인 공간 사용자: 게스트도 회원도 같은 오류다 (R-18은 이 조건만 본다)
		val guest = startGuest()
		val soloMember = startMember()
		insertMemo(activeSpaceIdOfUser(soloMember.userId), soloMember.userId)
		// 기록이 없어 연결 때 개인 공간이 지워진 커플
		val a = startMember()
		val b = startMember()
		connect(a, b)
		val before = coupleSnapshot()

		importRecords(guest.accessToken, true).expectError(409, "NO_IMPORT_PENDING")
		importRecords(soloMember.accessToken, false).expectError(409, "NO_IMPORT_PENDING")
		importRecords(a.accessToken, true).expectError(409, "NO_IMPORT_PENDING")
		importRecords(b.accessToken, false).expectError(409, "NO_IMPORT_PENDING")

		assertEquals(before, coupleSnapshot())
		assertEquals(1, count("select count(*) from memos"))
	}

	@Test
	fun `FR-COUPLE-03 AC4 이미 정한 사람의 두 번째 POST couple import는 409 NO_IMPORT_PENDING이다`() {
		val couple = pendingCouple()
		importRecords(couple.b.accessToken, true).andExpect(status().isNoContent)
		val before = coupleSnapshot()

		importRecords(couple.b.accessToken, true).expectError(409, "NO_IMPORT_PENDING")
		importRecords(couple.b.accessToken, false).expectError(409, "NO_IMPORT_PENDING")

		assertEquals(before, coupleSnapshot())
		assertEquals(couple.coupleSpaceId, spaceOfRecord("memos", couple.memoId))
	}

	@Test
	fun `FR-COUPLE-03 AC4 import 값이 없으면 400 VALIDATION_FAILED (import REQUIRED)`() {
		val couple = pendingCouple()
		val before = coupleSnapshot()

		for (body in listOf("{}", """{"import": null}""", """{"other": true}""", null)) {
			importCoupleRaw(couple.b.accessToken, body)
				.expectError(400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.details.fields[0].field").value("import"))
				.andExpect(jsonPath("$.details.fields[0].reason").value("REQUIRED"))
		}

		// 불리언이 아닌 값은 INVALID다
		importCoupleRaw(couple.b.accessToken, """{"import": "true"}""")
			.expectError(400, "VALIDATION_FAILED")
			.andExpect(jsonPath("$.details.fields[0].field").value("import"))
			.andExpect(jsonPath("$.details.fields[0].reason").value("INVALID"))
		// 거부된 요청은 아무것도 바꾸지 않고 대기 공간이 남는다
		assertEquals(before, coupleSnapshot())
		getMe(couple.b.accessToken).andExpect(jsonPath("$.space.importPending").value(true))
	}

	@Test
	fun `R-18 POST couple import는 import 값 검사보다 먼저 대기 공간을 확인한다`() {
		val a = startMember()
		val b = startMember()
		connect(a, b)

		// 대기 공간이 없으면 본문이 틀려도 409다
		importCoupleRaw(a.accessToken, "{}").expectError(409, "NO_IMPORT_PENDING")
		importCoupleRaw(a.accessToken, null).expectError(409, "NO_IMPORT_PENDING")
	}

	@Test
	fun `R-18 가져오기 대기 중인 본인은 POST couple import를 부를 수 있다`() {
		val couple = pendingCouple()

		importRecords(couple.b.accessToken, true).andExpect(status().isNoContent)
		// 대기 공간이 없던 A는 부를 수 없다
		importRecords(couple.a.accessToken, true).expectError(409, "NO_IMPORT_PENDING")
	}

	@Test
	fun `POST couple import는 access 토큰이 없으면 401 SESSION_INVALID다`() {
		importCoupleRaw("not-a-token", """{"import": true}""").expectError(401, "SESSION_INVALID")
	}

	// ── FR-COUPLE-05 AC4 (가져오기와 해제가 겹침) ──────────

	@Test
	fun `FR-COUPLE-05 AC4 가져오기와 해제가 동시에 와도 기록이 한쪽에만 남는다`() {
		repeat(3) {
			val couple = pendingCouple()

			val results = inParallel(
				listOf(
					{ statusOf(importRecords(couple.b.accessToken, true)) },
					{ statusOf(unlinkCouple(couple.a.accessToken)) },
				),
			)
			val (importStatus, unlinkStatus) = results

			assertEquals(204, unlinkStatus, "해제는 항상 성공해요")
			val memoSpace = spaceOfRecord("memos", couple.memoId)
			if (importStatus == 204) {
				// 가져오기가 먼저: 기록은 (지금은 UNLINKED인) 커플 공간으로 갔고 개인 공간은 지워졌다
				assertEquals(couple.coupleSpaceId, memoSpace)
				assertEquals(0, count("select count(*) from spaces where id = ?", couple.personalOfB))
			} else {
				// 해제가 먼저: 대기 공간이 활성 개인 공간으로 되살아나 기록이 거기 남는다
				assertEquals(409, importStatus)
				assertEquals(couple.personalOfB, memoSpace)
				assertEquals(couple.personalOfB, activeSpaceIdOfUser(couple.b.userId))
			}
			assertEquals(1, count("select count(*) from memos where id = ?", couple.memoId))
			assertEquals(0, count("select count(*) from space_members where user_id = ? and left_at is not null and space_id = ?", couple.b.userId, couple.personalOfB))
		}
	}
}
