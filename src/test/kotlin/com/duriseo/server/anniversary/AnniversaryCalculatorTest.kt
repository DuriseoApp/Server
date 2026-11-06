package com.duriseo.server.anniversary

import com.duriseo.server.anniversary.application.AnniversaryCalculator
import com.duriseo.server.anniversary.application.AutoAnniversary
import com.duriseo.server.anniversary.application.AutoAnniversaryType
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 자동·직접 기념일의 발생일 계산 (FR-ANNIV-02·03, D-23, D-74). 순수 계산이라 Spring·DB 없이 확인한다.
 * 처음 만난 날이 2월 29일일 때의 주년 날짜와 같은 날 자동 기념일의 정렬은 PRD v0.10 반영 대기라 여기서 다루지 않는다.
 */
class AnniversaryCalculatorTest {

	private val start = LocalDate.of(2026, 1, 1)

	private fun daysOf(startDate: LocalDate): List<AutoAnniversary> =
		AnniversaryCalculator.autoAnniversaries(startDate).filter { it.type == AutoAnniversaryType.DAYS }

	private fun yearsOf(startDate: LocalDate): List<AutoAnniversary> =
		AnniversaryCalculator.autoAnniversaries(startDate).filter { it.type == AutoAnniversaryType.YEARS }

	@Test
	fun `FR-ANNIV-02 AC1 처음 만난 날이 있으면 100일부터 1000일까지 100일 단위 일수 기념일 10개가 계산된다`() {
		val days = daysOf(start)

		assertEquals((1..10).map { it * 100 }, days.map { it.count })
	}

	@Test
	fun `FR-ANNIV-02 AC1 N주년은 처음 만난 날에서 N년 뒤이고 2050-12-31 이후는 계산하지 않는다`() {
		val years = yearsOf(start)

		assertEquals(LocalDate.of(2027, 1, 1), years.first().date)
		assertEquals(24, years.last().count)
		assertEquals(LocalDate.of(2050, 1, 1), years.last().date)
		assertTrue(years.all { !it.date.isAfter(LocalDate.of(2050, 12, 31)) })
		// 25주년(2051-01-01)은 범위 밖이다
		assertTrue(years.none { it.count == 25 })
	}

	@Test
	fun `FR-ANNIV-02 AC1 2050-12-31에 걸리는 주년은 포함하고 하루 뒤는 뺀다`() {
		// 2049-12-31의 1주년이 2050-12-31이다. 2050-01-01의 1주년은 2051-01-01이라 범위 밖이다
		assertEquals(listOf(LocalDate.of(2050, 12, 31)), yearsOf(LocalDate.of(2049, 12, 31)).map { it.date })
		assertEquals(emptyList(), yearsOf(LocalDate.of(2050, 1, 1)).map { it.date })
	}

	@Test
	fun `FR-ANNIV-02 AC2 처음 만난 날 2026-01-01의 100일은 2026-04-10이고 1주년은 2027-01-01이다`() {
		val all = AnniversaryCalculator.autoAnniversaries(start)

		assertEquals(LocalDate.of(2026, 4, 10), all.single { it.type == AutoAnniversaryType.DAYS && it.count == 100 }.date)
		assertEquals(LocalDate.of(2027, 1, 1), all.single { it.type == AutoAnniversaryType.YEARS && it.count == 1 }.date)
	}

	@Test
	fun `FR-ANNIV-02 AC2 1000일은 처음 만난 날 더하기 999일이다`() {
		val thousand = daysOf(start).single { it.count == 1000 }

		assertEquals(start.plusDays(999), thousand.date)
		assertEquals(LocalDate.of(2028, 9, 26), thousand.date)
	}

	@Test
	fun `FR-ANNIV-02 AC1 자동 기념일은 날짜 오름차순으로 나온다`() {
		val dates = AnniversaryCalculator.autoAnniversaries(start).map { it.date }

		// LocalDate는 Comparable<LocalDate>가 아니라 sorted()를 쓸 수 없다
		assertEquals(dates.sortedWith(compareBy<LocalDate> { it }), dates)
	}

	@Test
	fun `FR-ANNIV-02 AC1 범위 조회는 양 끝을 포함한 범위 안의 자동 기념일만 돌려준다`() {
		val onlyHundredth = AnniversaryCalculator.autoAnniversariesBetween(start, LocalDate.of(2026, 4, 10), LocalDate.of(2026, 4, 10))
		val none = AnniversaryCalculator.autoAnniversariesBetween(start, LocalDate.of(2026, 4, 11), LocalDate.of(2026, 5, 1))

		assertEquals(listOf(100), onlyHundredth.map { it.count })
		assertEquals(emptyList(), none)
	}

	@Test
	fun `FR-ANNIV-03 AC3 2월 29일에 매년 반복하는 기념일은 윤년이 아닌 해에 2월 28일로 나온다`() {
		val dates = AnniversaryCalculator.customOccurrencesBetween(
			date = LocalDate.of(2024, 2, 29),
			repeatsYearly = true,
			from = LocalDate.of(2025, 1, 1),
			to = LocalDate.of(2025, 12, 31),
		)

		assertEquals(listOf(LocalDate.of(2025, 2, 28)), dates)
	}

	@Test
	fun `FR-ANNIV-03 AC3 2월 29일 반복 기념일은 윤년에는 2월 29일로 나온다`() {
		val dates = AnniversaryCalculator.customOccurrencesBetween(
			date = LocalDate.of(2024, 2, 29),
			repeatsYearly = true,
			from = LocalDate.of(2027, 1, 1),
			to = LocalDate.of(2028, 12, 31),
		)

		assertEquals(listOf(LocalDate.of(2027, 2, 28), LocalDate.of(2028, 2, 29)), dates)
	}

	@Test
	fun `FR-ANNIV-04 AC1 매년 반복하는 직접 기념일은 해마다 한 번 나오고 등록한 날짜보다 이른 발생일은 없다`() {
		val registered = LocalDate.of(2026, 10, 27)

		val dates = AnniversaryCalculator.customOccurrencesBetween(registered, true, LocalDate.of(2020, 1, 1), LocalDate.of(2029, 12, 31))

		assertEquals((2026..2029).map { LocalDate.of(it, 10, 27) }, dates)
	}

	@Test
	fun `FR-ANNIV-04 AC1 매년 반복하는 직접 기념일은 2050-12-31까지만 나온다`() {
		val dates = AnniversaryCalculator.customOccurrencesBetween(
			LocalDate.of(2026, 12, 31),
			true,
			LocalDate.of(2026, 1, 1),
			LocalDate.of(2060, 12, 31),
		)

		assertEquals(LocalDate.of(2026, 12, 31), dates.first())
		assertEquals(LocalDate.of(2050, 12, 31), dates.last())
		assertEquals(25, dates.size)
	}

	@Test
	fun `FR-ANNIV-03 AC1 매년 반복하지 않는 직접 기념일은 등록한 날짜 하루만 나온다`() {
		val registered = LocalDate.of(2026, 10, 27)

		val inside = AnniversaryCalculator.customOccurrencesBetween(registered, false, LocalDate.of(2026, 1, 1), LocalDate.of(2030, 12, 31))
		val outside = AnniversaryCalculator.customOccurrencesBetween(registered, false, LocalDate.of(2027, 1, 1), LocalDate.of(2030, 12, 31))

		assertEquals(listOf(registered), inside)
		assertEquals(emptyList(), outside)
	}

	@Test
	fun `R-15 직접 기념일 발생일 계산은 1950-01-01 이전과 2050-12-31 이후를 만들지 않는다`() {
		val early = AnniversaryCalculator.customOccurrencesBetween(
			LocalDate.of(1950, 1, 1),
			true,
			LocalDate.of(1940, 1, 1),
			LocalDate.of(1951, 12, 31),
		)
		val late = AnniversaryCalculator.customOccurrencesBetween(
			LocalDate.of(2050, 12, 31),
			false,
			LocalDate.of(2051, 1, 1),
			LocalDate.of(2052, 1, 1),
		)

		assertEquals(listOf(LocalDate.of(1950, 1, 1), LocalDate.of(1951, 1, 1)), early)
		assertEquals(emptyList(), late)
	}
}
