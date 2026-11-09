package com.duriseo.server.memo

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.TestClockConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/**
 * `GET /memos/dated` (FR-CAL-01 AC2·AC6, API.md 3.4, 3.3 "조회 규칙") 와 `memoDate` 날짜 범위 R-15 (쓰기는 400 `DATE_OUT_OF_RANGE`, 조회는 잘라내기).
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class MemoDateRangeTest : MemoTestSupport() {

	private val t0: Instant = Instant.parse("2026-10-20T00:00:00Z")

	// ── FR-CAL-01 AC2 ────────────────────────────────────

	@Test
	fun `FR-CAL-01 AC2 GET memos dated는 범위 안 날짜 메모를 memo_date 오름차순으로 준다`() {
		val couple = newCouple()
		val nov5 = insertMemoRow(couple.spaceId, couple.a.userId, "5일", memoDate = "2026-11-05", createdAt = t0)
		val nov1 = insertMemoRow(couple.spaceId, couple.a.userId, "1일", memoDate = "2026-11-01", createdAt = t0)
		val nov30 = insertMemoRow(couple.spaceId, couple.b.userId, "30일", memoDate = "2026-11-30", createdAt = t0)
		// 같은 날은 created_at 내림차순 → id 내림차순
		val sameOld = insertMemoRow(couple.spaceId, couple.a.userId, "같은 날 먼저", memoDate = "2026-11-10", createdAt = t0)
		val sameNew = insertMemoRow(couple.spaceId, couple.a.userId, "같은 날 나중", memoDate = "2026-11-10", createdAt = t0.plusSeconds(5))
		val tied = (1..3).map { insertMemoRow(couple.spaceId, couple.a.userId, "같은 날 같은 시각", memoDate = "2026-11-11", createdAt = t0) }
		// 범위 밖, 날짜 없음
		insertMemoRow(couple.spaceId, couple.a.userId, "10월 말", memoDate = "2026-10-31", createdAt = t0)
		insertMemoRow(couple.spaceId, couple.a.userId, "12월", memoDate = "2026-12-01", createdAt = t0)
		insertMemoRow(couple.spaceId, couple.a.userId, "날짜 없음", createdAt = t0)

		val result = getDatedMemos(couple.b.accessToken, "2026-11-01", "2026-11-30").andExpect(status().isOk)

		val pgOrder = Comparator<UUID> { a, b ->
			val high = java.lang.Long.compareUnsigned(a.mostSignificantBits, b.mostSignificantBits)
			if (high != 0) high else java.lang.Long.compareUnsigned(a.leastSignificantBits, b.leastSignificantBits)
		}
		assertEquals(
			listOf(nov1, nov5, sameNew, sameOld) + tied.sortedWith(pgOrder).reversed() + listOf(nov30),
			idsOf(result),
		)
		// from·to를 모두 포함한다. 페이지가 없는 목록이라 nextCursor가 없다
		getDatedMemos(couple.b.accessToken, "2026-11-01", "2026-11-30").andExpect(jsonPath("$.nextCursor").doesNotExist())
		assertEquals(listOf(nov5), idsOf(getDatedMemos(couple.a.accessToken, "2026-11-05", "2026-11-05")))
	}

	@Test
	fun `FR-CAL-01 AC2 GET memos dated는 고정 여부와 관계없이 주고 요청자 기준 isFavorite을 담는다`() {
		val couple = newCouple()
		val pinned = insertMemoRow(couple.spaceId, couple.a.userId, "고정", memoDate = "2026-11-03", pinnedAt = t0, pinnedBy = couple.a.userId)
		val normal = insertMemoRow(couple.spaceId, couple.a.userId, "일반", memoDate = "2026-11-04")
		insertMemoFavorite(normal, couple.a.userId)

		val forA = getDatedMemos(couple.a.accessToken, "2026-11-01", "2026-11-30").andExpect(status().isOk)
			.andExpect(jsonPath("$.items[0].isPinned").value(true))
			.andExpect(jsonPath("$.items[0].isFavorite").value(false))
			.andExpect(jsonPath("$.items[1].isPinned").value(false))
			.andExpect(jsonPath("$.items[1].isFavorite").value(true))
		assertEquals(listOf(pinned, normal), idsOf(forA))
		getDatedMemos(couple.b.accessToken, "2026-11-01", "2026-11-30").andExpect(jsonPath("$.items[1].isFavorite").value(false))
	}

	@Test
	fun `FR-CAL-01 AC2 GET memos dated의 from to가 없거나 틀리거나 3개월을 넘으면 400 VALIDATION_FAILED`() {
		val me = startMember()

		// 없음: REQUIRED
		assertEquals(listOf("from" to "REQUIRED", "to" to "REQUIRED"), fieldsOf(getDatedMemos(me.accessToken, null, null).andExpect(status().isBadRequest)))
		assertEquals(listOf("to" to "REQUIRED"), fieldsOf(getDatedMemos(me.accessToken, "2026-11-01", null).andExpect(status().isBadRequest)))
		// 형식: INVALID
		assertEquals(
			listOf("from" to "INVALID", "to" to "INVALID"),
			fieldsOf(getDatedMemos(me.accessToken, "2026/11/01", "내일").andExpect(status().isBadRequest)),
		)
		assertEquals(listOf("from" to "INVALID"), fieldsOf(getDatedMemos(me.accessToken, "2026-02-30", "2026-11-30").andExpect(status().isBadRequest)))
		// from > to, 3개월 초과: (to, INVALID)
		assertEquals(listOf("to" to "INVALID"), fieldsOf(getDatedMemos(me.accessToken, "2026-11-30", "2026-11-01").andExpect(status().isBadRequest)))
		assertEquals(listOf("to" to "INVALID"), fieldsOf(getDatedMemos(me.accessToken, "2026-11-01", "2027-02-02").andExpect(status().isBadRequest)))
		getDatedMemos(me.accessToken, "2026-11-01", "2026-11-30").andExpect(jsonPath("$.items").isEmpty)
		// from + 3개월(달력 월)까지는 받는다
		getDatedMemos(me.accessToken, "2026-11-01", "2027-02-01").andExpect(status().isOk)
		getDatedMemos(me.accessToken, "2026-11-30", "2027-02-28").andExpect(status().isOk)
		getDatedMemos(me.accessToken, "2026-11-30", "2027-03-01").andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
	}

	// ── R-15 ────────────────────────────────────────────

	@Test
	fun `R-15 memoDate가 범위 밖이면 POST PATCH 모두 400 DATE_OUT_OF_RANGE`() {
		val me = startMember()
		val id = createMemo(me.accessToken, """{"title":"메모","memoDate":"2026-11-03"}""")
		val before = memoSnapshot()

		listOf("1949-12-31", "2051-01-01", "0001-01-01", "9999-12-31").forEach { date ->
			val created = postMemoRaw(me.accessToken, """{"title":"제목","memoDate":"$date"}""").andExpect(status().isBadRequest)
				.andExpect(jsonPath("$.code").value("DATE_OUT_OF_RANGE"))
			assertEquals(listOf("memoDate" to "OUT_OF_RANGE"), fieldsOf(created), date)
			patchMemo(me.accessToken, id, """{"memoDate":"$date"}""").andExpect(status().isBadRequest)
				.andExpect(jsonPath("$.code").value("DATE_OUT_OF_RANGE"))
		}
		assertEquals(before, memoSnapshot())
	}

	@Test
	fun `R-15 범위의 양 끝 날짜 1950-01-01과 2050-12-31은 허용한다`() {
		val me = startMember()

		val first = createMemo(me.accessToken, """{"title":"처음","memoDate":"1950-01-01"}""")
		val last = createMemo(me.accessToken, """{"title":"끝","memoDate":"2050-12-31"}""")

		assertEquals("1950-01-01", memoRow(first)["memo_date"])
		assertEquals("2050-12-31", memoRow(last)["memo_date"])
		patchMemo(me.accessToken, first, """{"memoDate":"2050-12-31"}""").andExpect(status().isOk)
			.andExpect(jsonPath("$.memoDate").value("2050-12-31"))
		// memoDate가 null이면 범위 검사 대상이 아니다
		patchMemo(me.accessToken, first, """{"memoDate":null}""").andExpect(status().isOk)
			.andExpect(jsonPath("$.memoDate").value(null as String?))
	}

	@Test
	fun `R-15 값 오류와 범위 밖이 같이 있으면 VALIDATION_FAILED만 준다`() {
		val me = startMember()
		val id = createMemo(me.accessToken, """{"title":"메모"}""")
		val before = memoSnapshot()

		// POST: 제목이 길고 날짜가 범위 밖 → VALIDATION_FAILED(title)만
		val post = postMemoRaw(me.accessToken, """{"title":"${"가".repeat(51)}","memoDate":"1900-01-01"}""").andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
		assertEquals(listOf("title" to "TOO_LONG"), fieldsOf(post))
		// POST: 둘 다 비고 날짜가 범위 밖
		val empty = postMemoRaw(me.accessToken, """{"memoDate":"2100-01-01"}""").andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
		assertEquals(listOf("title" to "REQUIRED", "body" to "REQUIRED"), fieldsOf(empty))
		// PATCH: 결과가 둘 다 비고 날짜가 범위 밖
		patchMemo(me.accessToken, id, """{"title":null,"memoDate":"2100-01-01"}""").andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
		// PATCH: 날짜 형식 오류는 범위 검사가 아니라 INVALID
		patchMemo(me.accessToken, id, """{"memoDate":"2100-1-1"}""").andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
		assertEquals(before, memoSnapshot())
	}

	@Test
	fun `R-15 dated 조회의 from to가 범위 밖이면 잘라서 조회한다`() {
		val me = startMember()
		val space = activeSpaceIdOfUser(me.userId)
		val first = insertMemoRow(space, me.userId, "1950", memoDate = "1950-01-01")
		val last = insertMemoRow(space, me.userId, "2050", memoDate = "2050-12-31")

		// 1950년 1월 달력은 1949-12 날짜를 그린다: 오류 없이 1950-01-01부터 조회한다
		assertEquals(listOf(first), idsOf(getDatedMemos(me.accessToken, "1949-12-25", "1950-02-05").andExpect(status().isOk)))
		assertEquals(listOf(last), idsOf(getDatedMemos(me.accessToken, "2050-11-25", "2051-01-10").andExpect(status().isOk)))
		assertEquals(listOf(first), idsOf(getDatedMemos(me.accessToken, "1949-11-20", "1950-01-01").andExpect(status().isOk)))
		assertEquals(listOf(last), idsOf(getDatedMemos(me.accessToken, "2050-12-31", "2051-02-20").andExpect(status().isOk)))
	}

	@Test
	fun `R-15 dated 조회는 검사를 먼저 하고 잘라낸 구간이 비면 빈 목록이다`() {
		val me = startMember()
		insertMemoRow(activeSpaceIdOfUser(me.userId), me.userId, "1950", memoDate = "1950-01-01")

		// 범위 전체가 밖이면 빈 목록 (오류 아님)
		getDatedMemos(me.accessToken, "1949-11-01", "1949-12-31").andExpect(status().isOk).andExpect(jsonPath("$.items").isEmpty)
		getDatedMemos(me.accessToken, "2051-01-01", "2051-02-01").andExpect(status().isOk).andExpect(jsonPath("$.items").isEmpty)
		// from > to와 3개월 초과는 요청받은 값 그대로 먼저 본다
		getDatedMemos(me.accessToken, "2051-02-01", "2051-01-01").andExpect(status().isBadRequest)
		getDatedMemos(me.accessToken, "1940-01-01", "1950-01-01").andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
	}
}
