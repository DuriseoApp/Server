package com.duriseo.server.anniversary

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.TestClockConfig
import com.duriseo.server.support.Tokens
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 기념일 조회 경로 `GET /anniversaries?from=&to=`(캘린더용, R-15), `/upcoming`, `/past`(커서·`size`)를 HTTP로 확인한다.
 * 시각은 `MutableClock`이 `2026-10-26T00:00:00Z`로 고정한다: 서울(기본) 사용자의 오늘은 2026-10-26이다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class AnniversaryListTest : AnniversaryTestSupport() {

	private class Page(val items: List<Map<String, Any?>>, val nextCursor: String?, val totalCount: Int?)

	private fun readPage(body: String): Page = Page(
		JsonPath.read(body, "$.items"),
		JsonPath.read<String?>(body, "$.nextCursor"),
		runCatching { JsonPath.read<Int>(body, "$.totalCount") }.getOrNull(),
	)

	/** 첫 페이지부터 `nextCursor`가 null일 때까지 [size]씩 읽는다. 각 페이지를 돌려준다 */
	private fun walk(token: Tokens, path: String, size: Int): List<Page> {
		val pages = mutableListOf<Page>()
		var cursor: String? = null
		do {
			val page = readPage(bodyOf(getAnniversaryList(token.accessToken, path, cursor, size.toString()).andExpect(status().isOk)))
			pages += page
			cursor = page.nextCursor
		} while (cursor != null && pages.size < 1000)
		return pages
	}

	private fun idsOf(page: Page): List<Any?> = page.items.map { it["anniversaryId"] }

	// ── 캘린더용 GET /anniversaries ──────────────────────

	@Test
	fun `FR-CAL-01 캘린더용 GET은 범위 안의 자동과 직접 기념일을 날짜순으로 daysLeft와 함께 돌려준다`() {
		val couple = newCouple()
		// 300일 = 2026-10-27
		setStartDate(couple.spaceId, "2026-01-01")
		val first = insertAnniversary(couple.spaceId, couple.b.userId, "비비 기념일", date = "2026-10-27", repeatsYearly = false)
		val second = insertAnniversary(couple.spaceId, couple.a.userId, "에이 기념일", date = "2026-11-05", repeatsYearly = false)
		insertAnniversary(couple.spaceId, couple.a.userId, "범위 밖", date = "2026-12-05", repeatsYearly = false)

		val result = getAnniversaries(couple.a.accessToken, "2026-10-26", "2026-11-30").andExpect(status().isOk)

		assertEquals(listOf("AUTO", "CUSTOM", "CUSTOM"), itemsOf(result, "kind"))
		assertEquals(listOf("300일", "비비 기념일", "에이 기념일"), itemsOf(result, "name"))
		assertEquals(listOf("2026-10-27", "2026-10-27", "2026-11-05"), itemsOf(result, "date"))
		assertEquals(listOf(1, 1, 10), itemsOf(result, "daysLeft"))
		assertEquals(listOf(null, first.toString(), second.toString()), itemsOf(result, "anniversaryId"))
		assertEquals(listOf(null, couple.b.userId.toString(), couple.a.userId.toString()), itemsOf(result, "authorId"))
		assertEquals(listOf(false, false, false), itemsOf(result, "repeatsYearly"))
		// 자동 항목도 값이 없는 필드를 빼지 않고 null로 보낸다
		assertTrue(bodyOf(result).contains(""""anniversaryId":null"""))
		assertTrue(bodyOf(result).contains(""""authorId":null"""))
	}

	@Test
	fun `FR-CAL-01 캘린더용 GET의 daysLeft는 지난 날짜면 음수이고 오늘이면 0이다`() {
		val me = startMember()
		val space = activeSpaceIdOfUser(me.userId)
		insertAnniversary(space, me.userId, "열흘 전", date = "2026-10-16", repeatsYearly = false)
		insertAnniversary(space, me.userId, "오늘", date = "2026-10-26", repeatsYearly = false)

		val result = getAnniversaries(me.accessToken, "2026-10-01", "2026-10-31").andExpect(status().isOk)

		assertEquals(listOf(-10, 0), itemsOf(result, "daysLeft"))
	}

	@Test
	fun `FR-ANNIV-04 AC1 캘린더용 GET은 매년 반복 기념일을 범위 안의 해에만 한 항목으로 돌려준다`() {
		val me = startMember()
		insertAnniversary(activeSpaceIdOfUser(me.userId), me.userId, "생일", date = "2020-03-01", repeatsYearly = true)

		assertEquals(listOf("2027-03-01"), itemsOf(getAnniversaries(me.accessToken, "2027-02-01", "2027-03-31"), "date"))
		// 등록한 해보다 앞의 해에는 없다
		assertEquals(emptyList(), itemsOf(getAnniversaries(me.accessToken, "2019-02-01", "2019-03-31"), "date"))
	}

	@Test
	fun `R-01 캘린더용 GET은 다른 공간의 기념일을 돌려주지 않는다`() {
		val couple = newCouple()
		val other = newCouple()
		insertAnniversary(other.spaceId, other.a.userId, "남의 것", date = "2026-11-01", repeatsYearly = false)
		insertAnniversary(couple.spaceId, couple.b.userId, "우리 것", date = "2026-11-02", repeatsYearly = false)

		val result = getAnniversaries(couple.a.accessToken, "2026-11-01", "2026-11-30")

		assertEquals(listOf("우리 것"), itemsOf(result, "name"))
	}

	@Test
	fun `FR-ANNIV-04 AC2 AC4 개인 공간과 처음 만난 날이 없는 커플 공간의 캘린더용 GET에는 AUTO가 없다`() {
		val me = startMember()
		insertAnniversary(activeSpaceIdOfUser(me.userId), me.userId, "내 것", date = "2026-11-01", repeatsYearly = false)
		val couple = newCouple()
		insertAnniversary(couple.spaceId, couple.a.userId, "우리 것", date = "2026-11-01", repeatsYearly = false)

		assertEquals(listOf("CUSTOM"), itemsOf(getAnniversaries(me.accessToken, "2026-10-01", "2027-01-01"), "kind"))
		assertEquals(listOf("CUSTOM"), itemsOf(getAnniversaries(couple.a.accessToken, "2026-10-01", "2027-01-01"), "kind"))
	}

	@Test
	fun `FR-CAL-01 캘린더용 GET은 from과 to가 없으면 400 REQUIRED이고 형식이 틀리면 INVALID다`() {
		val me = startMember()

		val none = getAnniversaries(me.accessToken, null, null).expectError(400, "VALIDATION_FAILED")
		expectField(none, 0, "from", "REQUIRED")
		expectField(none, 1, "to", "REQUIRED")
		val noTo = getAnniversaries(me.accessToken, "2026-11-01", null).expectError(400, "VALIDATION_FAILED")
		expectField(noTo, 0, "to", "REQUIRED")
		val bad = getAnniversaries(me.accessToken, "2026-13-01", "11월").expectError(400, "VALIDATION_FAILED")
		expectField(bad, 0, "from", "INVALID")
		expectField(bad, 1, "to", "INVALID")
	}

	@Test
	fun `FR-CAL-01 캘린더용 GET은 from이 to보다 뒤이거나 범위가 3개월을 넘으면 400 to INVALID다`() {
		val me = startMember()

		expectField(getAnniversaries(me.accessToken, "2026-11-10", "2026-11-09").expectError(400, "VALIDATION_FAILED"), 0, "to", "INVALID")
		// from + 3개월(달력 월)까지는 된다. 하루 넘으면 400이다
		getAnniversaries(me.accessToken, "2026-11-30", "2027-02-28").andExpect(status().isOk)
		expectField(getAnniversaries(me.accessToken, "2026-11-30", "2027-03-01").expectError(400, "VALIDATION_FAILED"), 0, "to", "INVALID")
		getAnniversaries(me.accessToken, "2026-11-01", "2026-11-01").andExpect(status().isOk)
	}

	@Test
	fun `R-15 캘린더용 GET은 범위 밖 날짜를 오류 없이 잘라서 조회한다`() {
		val me = startMember()
		val space = activeSpaceIdOfUser(me.userId)
		insertAnniversary(space, me.userId, "1950년 초", date = "1950-01-15", repeatsYearly = false)
		insertAnniversary(space, me.userId, "2050년 말", date = "2050-12-25", repeatsYearly = false)

		val low = getAnniversaries(me.accessToken, "1949-12-01", "1950-01-31").andExpect(status().isOk)
		val high = getAnniversaries(me.accessToken, "2050-12-01", "2051-01-31").andExpect(status().isOk)

		assertEquals(listOf("1950년 초"), itemsOf(low, "name"))
		assertEquals(listOf("2050년 말"), itemsOf(high, "name"))
	}

	@Test
	fun `R-15 캘린더용 GET은 잘라낸 구간이 비면 빈 목록이고 검사는 잘라내기 전 값으로 먼저 한다`() {
		val me = startMember()

		getAnniversaries(me.accessToken, "2051-01-01", "2051-02-01").andExpect(status().isOk).andExpect(jsonPath("$.items").isEmpty)
		getAnniversaries(me.accessToken, "1949-11-01", "1949-12-31").andExpect(status().isOk).andExpect(jsonPath("$.items").isEmpty)
		// 잘라내기 전에 from > to와 3개월 한도를 본다
		getAnniversaries(me.accessToken, "2051-03-01", "2051-01-01").expectError(400, "VALIDATION_FAILED")
		getAnniversaries(me.accessToken, "2051-01-01", "2051-06-01").expectError(400, "VALIDATION_FAILED")
	}

	// ── 자동 기념일 이름·윤년·같은 날 순서 ─────────────────

	@Test
	fun `D-105 자동 기념일 이름은 일수가 N일이고 주년이 N주년이다`() {
		val couple = newCouple()
		setStartDate(couple.spaceId, "2026-01-01")

		val names = itemsOf(getAnniversaryList(couple.a.accessToken, "upcoming", size = "100"), "name")

		// 300일(2026-10-27) 다음은 1주년(2027-01-01)이고 400일(2027-02-04)은 그 뒤다
		assertEquals(listOf("300일", "1주년", "400일"), names.take(3))
		assertTrue(names.contains("1000일"))
		assertTrue(names.containsAll(listOf("1주년", "2주년", "24주년")))
		assertEquals(names.size, names.toSet().size)
		assertTrue(names.none { it == "100일" || it == "200일" })
	}

	@Test
	fun `D-105 100일은 처음 만난 날을 1일로 센 99일 뒤다`() {
		val couple = newCouple()
		setStartDate(couple.spaceId, "2026-07-19")

		val result = getAnniversaryList(couple.a.accessToken, "upcoming", size = "1").andExpect(status().isOk)

		assertEquals(listOf("100일"), itemsOf(result, "name"))
		assertEquals(listOf("2026-10-26"), itemsOf(result, "date"))
		assertEquals(listOf(0), itemsOf(result, "daysLeft"))
		assertEquals(listOf("AUTO"), itemsOf(result, "kind"))
	}

	@Test
	fun `FR-ANNIV-02 AC5 처음 만난 날이 2월 29일이면 윤년이 아닌 해의 주년은 2월 28일이고 윤년에는 2월 29일이다`() {
		val couple = newCouple()
		setStartDate(couple.spaceId, "2024-02-29")

		val nonLeap = getAnniversaries(couple.a.accessToken, "2027-02-01", "2027-03-01").andExpect(status().isOk)
		val leap = getAnniversaries(couple.a.accessToken, "2028-02-01", "2028-03-01").andExpect(status().isOk)

		assertEquals(listOf("3주년"), itemsOf(nonLeap, "name"))
		assertEquals(listOf("2027-02-28"), itemsOf(nonLeap, "date"))
		assertEquals(listOf("4주년"), itemsOf(leap, "name"))
		assertEquals(listOf("2028-02-29"), itemsOf(leap, "date"))
	}

	@Test
	fun `FR-ANNIV-04 AC5 같은 날에는 자동 기념일이 직접 기념일보다 먼저이고 직접끼리는 ID 순이다 캘린더와 upcoming이 같다`() {
		val couple = newCouple()
		setStartDate(couple.spaceId, "2026-01-01")
		// 부호 있는 비교로는 ffff…이 가장 작지만 PostgreSQL uuid 비교(부호 없는 바이트 순)로는 가장 크다
		val low = UUID.fromString("00000000-0000-0000-0000-000000000001")
		val mid = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff")
		val high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
		// 300일(2026-10-27)과 같은 날. 일부러 ID 순서와 다른 순서로 넣는다
		insertAnniversary(couple.spaceId, couple.a.userId, "높은", date = "2026-10-27", repeatsYearly = false, id = high)
		insertAnniversary(couple.spaceId, couple.b.userId, "낮은", date = "2026-10-27", repeatsYearly = false, id = low)
		insertAnniversary(couple.spaceId, couple.a.userId, "가운데", date = "2026-10-27", repeatsYearly = false, id = mid)

		val calendar = getAnniversaries(couple.a.accessToken, "2026-10-27", "2026-10-27")
		val upcoming = getAnniversaryList(couple.a.accessToken, "upcoming", size = "4")

		val expected = listOf("300일", "낮은", "가운데", "높은")
		assertEquals(expected, itemsOf(calendar, "name"))
		assertEquals(expected, itemsOf(upcoming, "name"))
		assertEquals(listOf("AUTO", "CUSTOM", "CUSTOM", "CUSTOM"), itemsOf(upcoming, "kind"))
	}

	// ── upcoming: 커서·size ─────────────────────────────

	@Test
	fun `FR-ANNIV-04 AC1 upcoming은 size를 생략하면 20개이고 nextCursor를 돌려준다`() {
		val couple = newCouple()
		// 자동 기념일 8 + 24개 = 32개
		setStartDate(couple.spaceId, "2026-01-01")

		val body = bodyOf(getAnniversaryList(couple.a.accessToken, "upcoming").andExpect(status().isOk))

		val page = readPage(body)
		assertEquals(20, page.items.size)
		assertNotNull(page.nextCursor)
	}

	@Test
	fun `FR-ANNIV-04 AC1 upcoming 페이지를 이어 붙이면 전체와 같고 빠지거나 겹치는 항목이 없다`() {
		val couple = newCouple()
		setStartDate(couple.spaceId, "2026-01-01")
		insertAnniversary(couple.spaceId, couple.a.userId, "매년", date = "2026-11-01", repeatsYearly = true)
		insertAnniversary(couple.spaceId, couple.b.userId, "한 번", date = "2027-02-04", repeatsYearly = false)
		val all = readPage(bodyOf(getAnniversaryList(couple.a.accessToken, "upcoming", size = "100")))
		assertNull(all.nextCursor)
		assertEquals(32 + 25 + 1, all.items.size)

		for (size in listOf(1, 5, 7, 20, 57, 58, 100)) {
			val pages = walk(couple.a, "upcoming", size)

			assertEquals(all.items, pages.flatMap { it.items }, "size=$size")
			assertEquals((all.items.size + size - 1) / size, pages.size, "size=$size")
			assertTrue(pages.dropLast(1).all { it.items.size == size && it.nextCursor != null }, "size=$size")
			assertNull(pages.last().nextCursor, "size=$size")
		}
	}

	@Test
	fun `FR-ANNIV-04 AC5 같은 날 항목이 페이지 경계에 걸려도 커서가 빠지거나 겹치지 않는다`() {
		val couple = newCouple()
		// 300일(2026-10-27)과 같은 날 직접 기념일 7개, 그 앞뒤로도 하나씩
		setStartDate(couple.spaceId, "2026-01-01")
		insertAnniversary(couple.spaceId, couple.a.userId, "앞", date = "2026-10-26", repeatsYearly = false)
		repeat(7) { insertAnniversary(couple.spaceId, couple.a.userId, "같은 날 $it", date = "2026-10-27", repeatsYearly = false) }
		insertAnniversary(couple.spaceId, couple.b.userId, "뒤", date = "2026-10-28", repeatsYearly = false)
		val sameDayIds = jdbc.queryForList("select id::text from anniversaries where date = '2026-10-27' order by id", String::class.java)

		val pages = walk(couple.a, "upcoming", 2)

		val firstTen = pages.flatMap { it.items }.take(10)
		assertEquals(listOf("앞", "300일"), firstTen.take(2).map { it["name"] })
		// 자동 → 직접(PostgreSQL uuid 순) → 다음 날. 같은 날 7개가 2개씩 끊겨도 모두 한 번씩만 나온다
		assertEquals(sameDayIds, firstTen.drop(2).take(7).map { it["anniversaryId"] })
		assertEquals("뒤", firstTen[9]["name"])
		val keys = pages.flatMap { it.items }.map { "${it["date"]}|${it["kind"]}|${it["name"]}|${it["anniversaryId"]}" }
		assertEquals(keys.size, keys.toSet().size)
	}

	@Test
	fun `FR-ANNIV-04 AC1 커서의 항목이 다음 요청 전에 삭제돼도 다음 페이지는 이어진다`() {
		val couple = newCouple()
		val ids = (1..5).map { insertAnniversary(couple.spaceId, couple.a.userId, "기념일 $it", date = "2026-11-0$it", repeatsYearly = false) }
		val first = readPage(bodyOf(getAnniversaryList(couple.a.accessToken, "upcoming", size = "2")))
		assertEquals(ids.take(2).map { it.toString() }, idsOf(first))

		// 커서가 가리키는 마지막 항목을 지운다
		deleteAnniversary(couple.a.accessToken, ids[1]).andExpect(status().isNoContent)
		val second = readPage(bodyOf(getAnniversaryList(couple.a.accessToken, "upcoming", first.nextCursor, "2")))

		assertEquals(ids.subList(2, 4).map { it.toString() }, idsOf(second))
	}

	@Test
	fun `FR-ANNIV-04 AC1 upcoming 항목은 AnniversaryOccurrence 모양이고 daysLeft는 늘 0 이상이다`() {
		val couple = newCouple()
		setStartDate(couple.spaceId, "2026-01-01")
		insertAnniversary(couple.spaceId, couple.b.userId, "오늘", date = "2026-10-26", repeatsYearly = false)
		insertAnniversary(couple.spaceId, couple.b.userId, "어제", date = "2026-10-25", repeatsYearly = false)

		val body = bodyOf(getAnniversaryList(couple.a.accessToken, "upcoming", size = "100").andExpect(status().isOk))

		val page = readPage(body)
		assertEquals("오늘", page.items.first()["name"])
		assertEquals(0, page.items.first()["daysLeft"])
		assertTrue(page.items.all { (it["daysLeft"] as Int) >= 0 })
		assertEquals(setOf("anniversaryId", "kind", "name", "date", "daysLeft", "repeatsYearly", "authorId"), page.items.first().keys)
		assertEquals(setOf("anniversaryId", "kind", "name", "date", "daysLeft", "repeatsYearly", "authorId"), page.items.last().keys)
		assertTrue(page.items.none { it["name"] == "어제" })
	}

	@Test
	fun `FR-ANNIV-04 AC2 AC4 개인 공간과 처음 만난 날이 없는 커플의 upcoming에는 AUTO가 없다`() {
		val me = startMember()
		insertAnniversary(activeSpaceIdOfUser(me.userId), me.userId, "내 것", date = "2026-11-01", repeatsYearly = false)
		val couple = newCouple()

		assertEquals(listOf("CUSTOM"), itemsOf(getAnniversaryList(me.accessToken, "upcoming"), "kind"))
		assertEquals(emptyList(), itemsOf(getAnniversaryList(couple.a.accessToken, "upcoming"), "kind"))
	}

	// ── past: 커서·size·totalCount ──────────────────────

	@Test
	fun `FR-ANNIV-04 AC3 past는 items nextCursor totalCount를 돌려주고 항목은 anniversaryId name date authorId다`() {
		val couple = newCouple()
		val id = insertAnniversary(couple.spaceId, couple.b.userId, "지난 약속", date = "2026-10-01", repeatsYearly = false)

		val result = getAnniversaryList(couple.a.accessToken, "past").andExpect(status().isOk)
			.andExpect(jsonPath("$.items[0].anniversaryId").value(id.toString()))
			.andExpect(jsonPath("$.items[0].name").value("지난 약속"))
			.andExpect(jsonPath("$.items[0].date").value("2026-10-01"))
			.andExpect(jsonPath("$.items[0].authorId").value(couple.b.userId.toString()))
			.andExpect(jsonPath("$.totalCount").value(1))

		val page = readPage(bodyOf(result))
		assertEquals(setOf("anniversaryId", "name", "date", "authorId"), page.items.single().keys)
		assertNull(page.nextCursor)
		assertTrue(bodyOf(result).contains(""""nextCursor":null"""))
	}

	@Test
	fun `FR-ANNIV-04 AC3 지난 기념일이 없으면 past는 빈 목록 nextCursor null totalCount 0이다`() {
		val me = startMember()
		insertAnniversary(activeSpaceIdOfUser(me.userId), me.userId, "매년", date = "2020-01-01", repeatsYearly = true)
		insertAnniversary(activeSpaceIdOfUser(me.userId), me.userId, "오늘", date = "2026-10-26", repeatsYearly = false)

		val result = getAnniversaryList(me.accessToken, "past").andExpect(status().isOk)
			.andExpect(jsonPath("$.items").isEmpty)
			.andExpect(jsonPath("$.totalCount").value(0))
		assertNull(readPage(bodyOf(result)).nextCursor)
	}

	@Test
	fun `FR-ANNIV-04 AC3 past 페이지를 이어 붙이면 날짜 내림차순 ID 내림차순이고 totalCount는 모든 페이지에서 같다`() {
		val couple = newCouple()
		val low = UUID.fromString("00000000-0000-0000-0000-000000000001")
		val mid = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff")
		val high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
		// 같은 날 세 개(부호 없는 uuid 순서 확인) + 서로 다른 날 네 개
		insertAnniversary(couple.spaceId, couple.a.userId, "낮은", date = "2026-10-20", repeatsYearly = false, id = low)
		insertAnniversary(couple.spaceId, couple.a.userId, "높은", date = "2026-10-20", repeatsYearly = false, id = high)
		insertAnniversary(couple.spaceId, couple.b.userId, "가운데", date = "2026-10-20", repeatsYearly = false, id = mid)
		insertAnniversary(couple.spaceId, couple.a.userId, "가장 최근", date = "2026-10-25", repeatsYearly = false)
		insertAnniversary(couple.spaceId, couple.a.userId, "작년", date = "2025-10-20", repeatsYearly = false)
		insertAnniversary(couple.spaceId, couple.a.userId, "오래전", date = "1990-01-01", repeatsYearly = false)
		insertAnniversary(couple.spaceId, couple.a.userId, "매년 반복은 제외", date = "2020-01-01", repeatsYearly = true)

		for (size in listOf(1, 2, 3, 7, 100)) {
			val pages = walk(couple.b, "past", size)

			assertEquals(
				listOf("가장 최근", "높은", "가운데", "낮은", "작년", "오래전"),
				pages.flatMap { it.items }.map { it["name"] },
				"size=$size",
			)
			assertTrue(pages.all { it.totalCount == 6 }, "size=$size")
			assertEquals((6 + size - 1) / size, pages.size, "size=$size")
			assertNull(pages.last().nextCursor, "size=$size")
			assertTrue(pages.dropLast(1).all { it.nextCursor != null }, "size=$size")
		}
	}

	@Test
	fun `FR-ANNIV-04 AC3 past의 커서는 같은 날 항목 사이에서도 이어진다`() {
		val couple = newCouple()
		repeat(5) { insertAnniversary(couple.spaceId, couple.a.userId, "같은 날 $it", date = "2026-10-20", repeatsYearly = false) }
		val expected = jdbc.queryForList("select id::text from anniversaries order by id desc", String::class.java)

		val pages = walk(couple.a, "past", 2)

		assertEquals(listOf(2, 2, 1), pages.map { it.items.size })
		assertEquals(expected, pages.flatMap { idsOf(it) })
	}

	@Test
	fun `R-01 past는 다른 공간의 기념일을 돌려주지 않고 totalCount에도 세지 않는다`() {
		val couple = newCouple()
		val other = newCouple()
		insertAnniversary(other.spaceId, other.a.userId, "남의 지난 것", date = "2020-01-01", repeatsYearly = false)
		insertAnniversary(couple.spaceId, couple.a.userId, "우리 지난 것", date = "2020-01-01", repeatsYearly = false)

		val page = readPage(bodyOf(getAnniversaryList(couple.b.accessToken, "past")))

		assertEquals(listOf("우리 지난 것"), page.items.map { it["name"] })
		assertEquals(1, page.totalCount)
	}

	// ── 커서·size 오류: API.md 1.1 ───────────────────────

	@Test
	fun `FR-ANNIV-04 size가 정수가 아니거나 1 ~ 100 밖이면 upcoming과 past 모두 400 size INVALID다`() {
		val me = startMember()

		for (path in listOf("upcoming", "past")) {
			for (bad in listOf("0", "101", "-1", "abc", "1.5", "", "99999999999")) {
				val result = getAnniversaryList(me.accessToken, path, size = bad).expectError(400, "VALIDATION_FAILED")
				expectField(result, 0, "size", "INVALID")
			}
			getAnniversaryList(me.accessToken, path, size = "1").andExpect(status().isOk)
			getAnniversaryList(me.accessToken, path, size = "100").andExpect(status().isOk)
		}
	}

	@Test
	fun `FR-ANNIV-04 읽을 수 없는 cursor는 400 cursor INVALID이고 다른 경로의 cursor도 읽지 못한 것이다`() {
		val couple = newCouple()
		setStartDate(couple.spaceId, "2026-01-01")
		insertAnniversary(couple.spaceId, couple.a.userId, "지난 1", date = "2020-01-01", repeatsYearly = false)
		insertAnniversary(couple.spaceId, couple.a.userId, "지난 2", date = "2020-01-02", repeatsYearly = false)
		val upcomingCursor = readPage(bodyOf(getAnniversaryList(couple.a.accessToken, "upcoming", size = "1"))).nextCursor
		val pastCursor = readPage(bodyOf(getAnniversaryList(couple.a.accessToken, "past", size = "1"))).nextCursor
		assertNotNull(upcomingCursor)
		assertNotNull(pastCursor)

		for (bad in listOf("garbage", "!!!", "", "dXw", "${upcomingCursor}x", "${pastCursor}x")) {
			expectField(getAnniversaryList(couple.a.accessToken, "upcoming", cursor = bad).expectError(400, "VALIDATION_FAILED"), 0, "cursor", "INVALID")
			expectField(getAnniversaryList(couple.a.accessToken, "past", cursor = bad).expectError(400, "VALIDATION_FAILED"), 0, "cursor", "INVALID")
		}
		getAnniversaryList(couple.a.accessToken, "upcoming", cursor = pastCursor).expectError(400, "VALIDATION_FAILED")
		getAnniversaryList(couple.a.accessToken, "past", cursor = upcomingCursor).expectError(400, "VALIDATION_FAILED")
		getAnniversaryList(couple.a.accessToken, "upcoming", cursor = upcomingCursor).andExpect(status().isOk)
	}

	@Test
	fun `FR-ANNIV-04 cursor와 size가 모두 틀리면 details fields에 둘 다 담는다`() {
		val me = startMember()

		val result = getAnniversaryList(me.accessToken, "upcoming", cursor = "garbage", size = "0").expectError(400, "VALIDATION_FAILED")

		assertEquals(setOf("cursor", "size"), fieldNamesOf(result).toSet())
		assertEquals(2, fieldNamesOf(result).size)
	}
}
