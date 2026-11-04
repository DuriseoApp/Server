package com.duriseo.server.anniversary

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.anniversary.application.AnniversaryKind
import com.duriseo.server.anniversary.application.AnniversaryQueryService
import com.duriseo.server.anniversary.application.AutoAnniversaryType
import com.duriseo.server.support.TestClockConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 기념일 목록 계산 (FR-ANNIV-02·04, D-71, D-75, R-01)을 서비스로 확인한다.
 * `GET /anniversaries/upcoming`·`/past` 경로와 커서·`size`는 PRD v0.10 반영 뒤에 더하므로 지금은 전체 목록을 돌려주는 서비스를 직접 부른다.
 * 시각은 `MutableClock`이 `2026-10-26T00:00:00Z`로 고정한다: 서울(기본) 사용자의 오늘은 2026-10-26이다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class AnniversaryQueryTest : AnniversaryTestSupport() {

	@Autowired
	private lateinit var queries: AnniversaryQueryService

	private val today = LocalDate.of(2026, 10, 26)

	// ── FR-ANNIV-02 AC1 / FR-ANNIV-04 AC1 ───────────────

	@Test
	fun `FR-ANNIV-04 AC1 upcoming은 오늘 이후의 자동 기념일을 날짜순으로 돌려주고 지난 기념일은 뺀다`() {
		val couple = newCouple()
		setStartDate(couple.spaceId, "2026-01-01")

		val items = queries.upcoming(couple.a.userId)

		val auto = items.filter { it.kind == AnniversaryKind.AUTO }
		assertEquals(items.size, auto.size)
		// 100일(2026-04-10)과 200일(2026-07-19)은 지났고 300일(2026-10-27)부터 1000일까지 8개 + 1주년~24주년
		assertEquals(8, auto.count { it.auto?.type == AutoAnniversaryType.DAYS })
		assertEquals(24, auto.count { it.auto?.type == AutoAnniversaryType.YEARS })
		assertEquals(LocalDate.of(2026, 10, 27), items.first().date)
		assertEquals(300, items.first().auto?.count)
		assertEquals(items.map { it.date }.sortedWith(compareBy<LocalDate> { it }), items.map { it.date })
		assertTrue(items.all { !it.date.isBefore(today) && !it.date.isAfter(LocalDate.of(2050, 12, 31)) })
	}

	@Test
	fun `FR-ANNIV-04 AC1 daysLeft는 오늘이면 0이고 그 뒤 항목은 오늘부터 남은 일수다`() {
		val couple = newCouple()
		// 2026-07-19가 1일이면 100일은 2026-10-26(오늘)이다
		setStartDate(couple.spaceId, "2026-07-19")

		val first = queries.upcoming(couple.a.userId).first()

		assertEquals(LocalDate.of(2026, 10, 26), first.date)
		assertEquals(0L, first.daysLeft)
		// 다음은 200일(2027-02-03)이다
		assertEquals(100L, queries.upcoming(couple.a.userId)[1].daysLeft)
	}

	@Test
	fun `FR-ANNIV-04 AC1 매년 반복하는 직접 기념일은 해마다 한 항목씩 나온다`() {
		val couple = newCouple()
		insertAnniversary(couple.spaceId, couple.a.userId, "생일", date = "2026-10-27", repeatsYearly = true)

		val items = queries.upcoming(couple.a.userId).filter { it.kind == AnniversaryKind.CUSTOM }

		assertEquals((2026..2050).map { LocalDate.of(it, 10, 27) }, items.map { it.date })
		assertEquals(1L, items.first().daysLeft)
		assertTrue(items.all { it.repeatsYearly && it.name == "생일" && it.authorId == couple.a.userId })
	}

	@Test
	fun `FR-ANNIV-04 AC1 매년 반복하는 직접 기념일의 지난 해 발생일은 나오지 않고 올해 이후만 나온다`() {
		val couple = newCouple()
		insertAnniversary(couple.spaceId, couple.a.userId, "입사일", date = "2020-03-01", repeatsYearly = true)

		val dates = queries.upcoming(couple.a.userId).map { it.date }

		// 2026-03-01은 오늘(2026-10-26)보다 앞이라 빠지고 2027-03-01부터다
		assertEquals((2027..2050).map { LocalDate.of(it, 3, 1) }, dates)
	}

	@Test
	fun `FR-ANNIV-04 AC1 지난 날짜의 1회성 직접 기념일은 upcoming에 나오지 않는다`() {
		val couple = newCouple()
		insertAnniversary(couple.spaceId, couple.a.userId, "지난 약속", date = "2026-10-25", repeatsYearly = false)
		insertAnniversary(couple.spaceId, couple.a.userId, "다음 약속", date = "2026-12-25", repeatsYearly = false)

		val items = queries.upcoming(couple.a.userId)

		assertEquals(listOf("다음 약속"), items.map { it.name })
	}

	@Test
	fun `FR-ANNIV-04 AC1 같은 날 자동 기념일은 직접 기념일보다 먼저 나온다`() {
		val couple = newCouple()
		setStartDate(couple.spaceId, "2026-01-01")
		// 300일(2026-10-27)과 같은 날
		insertAnniversary(couple.spaceId, couple.b.userId, "같은 날", date = "2026-10-27", repeatsYearly = false)

		val items = queries.upcoming(couple.a.userId)

		assertEquals(AnniversaryKind.AUTO, items[0].kind)
		assertEquals(AnniversaryKind.CUSTOM, items[1].kind)
		assertEquals(items[0].date, items[1].date)
		assertNull(items[0].anniversaryId)
		assertNotNull(items[1].anniversaryId)
	}

	@Test
	fun `FR-ANNIV-04 AC1 daysLeft는 요청자 시간대의 오늘 기준이다`() {
		val couple = newCouple()
		// 시계는 UTC 2026-10-26 00:00 이다: 서울은 10월 26일 09:00, 로스앤젤레스는 10월 25일 17:00
		insertAnniversary(couple.spaceId, couple.a.userId, "하루 전", date = "2026-10-25", repeatsYearly = false)
		setTimeZone(couple.a, "America/Los_Angeles")

		val la = queries.upcoming(couple.a.userId)
		val seoul = queries.upcoming(couple.b.userId)

		assertEquals(listOf(0L), la.map { it.daysLeft })
		assertEquals(emptyList(), seoul)
	}

	// ── FR-ANNIV-04 AC2·AC4 ─────────────────────────────

	@Test
	fun `FR-ANNIV-04 AC2 개인 공간 사용자의 upcoming에는 AUTO가 없고 직접 기념일만 나온다`() {
		val me = startMember("에이")
		insertAnniversary(activeSpaceIdOfUser(me.userId), me.userId, "내 기념일", date = "2026-11-01", repeatsYearly = false)

		val items = queries.upcoming(me.userId)

		assertEquals(listOf(AnniversaryKind.CUSTOM), items.map { it.kind })
		assertEquals("내 기념일", items.single().name)
	}

	@Test
	fun `FR-ANNIV-04 AC4 처음 만난 날이 없는 커플 공간에는 AUTO가 없고 직접 기념일만 나온다`() {
		val couple = newCouple()
		insertAnniversary(couple.spaceId, couple.b.userId, "비비의 기념일", date = "2026-11-01", repeatsYearly = false)

		val items = queries.upcoming(couple.a.userId)

		assertEquals(listOf(AnniversaryKind.CUSTOM), items.map { it.kind })
		assertEquals(couple.b.userId, items.single().authorId)
	}

	@Test
	fun `FR-ANNIV-02 AC3 처음 만난 날을 바꾸면 다음 조회의 자동 기념일이 새 날짜 기준으로 다시 계산된다`() {
		val couple = newCouple()
		setStartDate(couple.spaceId, "2026-01-01")
		assertEquals(LocalDate.of(2026, 10, 27), queries.upcoming(couple.a.userId).first().date)

		setStartDate(couple.spaceId, "2026-02-01")

		// 저장된 자동 기념일 행은 없다: 새 처음 만난 날로 그때그때 계산한다
		assertEquals(LocalDate.of(2026, 11, 27), queries.upcoming(couple.a.userId).first().date)
		assertEquals(0, anniversaryCount())
	}

	// ── R-01 ────────────────────────────────────────────

	@Test
	fun `R-01 upcoming과 past는 다른 공간의 직접 기념일을 결과에 넣지 않는다`() {
		val couple = newCouple()
		val other = newCouple()
		insertAnniversary(other.spaceId, other.a.userId, "다른 커플", date = "2026-11-01", repeatsYearly = false)
		insertAnniversary(other.spaceId, other.a.userId, "다른 커플 지난", date = "2020-01-01", repeatsYearly = false)
		insertAnniversary(couple.spaceId, couple.a.userId, "우리", date = "2026-11-02", repeatsYearly = false)

		assertEquals(listOf("우리"), queries.upcoming(couple.a.userId).map { it.name })
		assertEquals(emptyList(), queries.past(couple.a.userId).items)
	}

	// ── FR-ANNIV-04 AC3 ─────────────────────────────────

	@Test
	fun `FR-ANNIV-04 AC3 past는 1회성이고 날짜가 오늘보다 앞인 직접 기념일만 최근 순으로 돌려준다`() {
		val couple = newCouple()
		val older = insertAnniversary(couple.spaceId, couple.a.userId, "오래전", date = "2020-05-05", repeatsYearly = false)
		val recent = insertAnniversary(couple.spaceId, couple.b.userId, "최근", date = "2026-10-25", repeatsYearly = false)
		insertAnniversary(couple.spaceId, couple.a.userId, "매년 반복", date = "2020-01-01", repeatsYearly = true)
		insertAnniversary(couple.spaceId, couple.a.userId, "앞으로", date = "2026-12-25", repeatsYearly = false)

		val past = queries.past(couple.a.userId)

		assertEquals(listOf(recent, older), past.items.map { it.anniversaryId })
		assertEquals(listOf("최근", "오래전"), past.items.map { it.name })
		assertEquals(couple.b.userId, past.items.first().authorId)
		assertEquals(LocalDate.of(2026, 10, 25), past.items.first().date)
	}

	@Test
	fun `FR-ANNIV-04 AC3 오늘 날짜인 1회성 기념일은 past가 아니라 upcoming에 있다`() {
		val couple = newCouple()
		insertAnniversary(couple.spaceId, couple.a.userId, "오늘", date = "2026-10-26", repeatsYearly = false)

		assertEquals(emptyList(), queries.past(couple.a.userId).items)
		val upcoming = queries.upcoming(couple.a.userId)
		assertEquals(listOf("오늘"), upcoming.map { it.name })
		assertEquals(0L, upcoming.single().daysLeft)
	}

	@Test
	fun `FR-ANNIV-04 AC3 past의 totalCount는 조건에 맞는 전체 개수다`() {
		val couple = newCouple()
		repeat(3) { insertAnniversary(couple.spaceId, couple.a.userId, "지난 $it", date = "2020-01-0${it + 1}", repeatsYearly = false) }
		insertAnniversary(couple.spaceId, couple.a.userId, "매년", date = "2020-02-01", repeatsYearly = true)

		val past = queries.past(couple.b.userId)

		assertEquals(3L, past.totalCount)
		assertEquals(3, past.items.size)
	}

	@Test
	fun `FR-ANNIV-04 AC3 past의 오늘 판단은 요청자 시간대 기준이다`() {
		val couple = newCouple()
		insertAnniversary(couple.spaceId, couple.a.userId, "10월 25일", date = "2026-10-25", repeatsYearly = false)
		setTimeZone(couple.a, "America/Los_Angeles")

		// 서울(B)의 오늘은 10월 26일이라 10월 25일은 지났다. 로스앤젤레스(A)의 오늘은 10월 25일이라 아직 지나지 않았다
		assertEquals(listOf("10월 25일"), queries.past(couple.b.userId).items.map { it.name })
		assertEquals(emptyList(), queries.past(couple.a.userId).items)
	}
}
