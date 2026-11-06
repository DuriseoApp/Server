package com.duriseo.server.anniversary.application

import java.time.LocalDate

/** 자동 기념일의 종류: 100일 단위 일수(`DAYS`)와 매년 N주년(`YEARS`) */
enum class AutoAnniversaryType { DAYS, YEARS }

/**
 * 자동 기념일 하나 (FR-ANNIV-02). [count]는 일수(100~1000) 또는 주년 수다. 저장하지 않고 계산한다 (DB-01).
 * [name]은 일수가 "100일" ~ "1000일", 주년이 "1주년", "2주년" …이다 (FR-ANNIV-02 AC4, D-105).
 * [rank]는 같은 날 순서의 순번이다: 일수 0, 주년 1 (D-106). 직접 기념일은 2다 (API.md 3.3 "커서 정렬 키").
 */
data class AutoAnniversary(val type: AutoAnniversaryType, val count: Int, val date: LocalDate) {

	val name: String get() = if (type == AutoAnniversaryType.DAYS) "${count}일" else "${count}주년"

	val rank: Int get() = type.ordinal
}

/**
 * 기념일의 발생일 계산 (FR-ANNIV-02·03, 시간대와 무관한 날짜 계산만 한다). 오늘 판단은 부른 쪽이 요청자 시간대로 한다.
 *
 * - 자동 기념일 (D-23, FR-ANNIV-02 AC1·AC2): 처음 만난 날 = 1일. N일째 = 처음 만난 날 + (N-1)일이고 100일부터 1000일까지 100일 단위(10개).
 *   N주년 = 처음 만난 날 + N년이고 [MAX_DATE](2050-12-31)까지만 계산한다. 2026-01-01이면 100일 = 2026-04-10, 1주년 = 2027-01-01.
 * - 직접 기념일 (FR-ANNIV-03): 매년 반복이 아니면 등록한 날짜 하루. 반복이면 등록한 해부터 [MAX_DATE]까지 해마다 한 번이고,
 *   2월 29일은 윤년이 아닌 해에 2월 28일이다 (AC3).
 * - 날짜 범위는 1950-01-01 ~ 2050-12-31이다 (D-74, R-15). 이 범위 밖 발생일은 만들지 않는다.
 *
 * 처음 만난 날이 2월 29일이고 그 해가 윤년이 아니면 N주년은 2월 28일이다 (FR-ANNIV-02 AC5, D-106). [yearsLater] 한 곳이다.
 */
object AnniversaryCalculator {

	val MIN_DATE: LocalDate = LocalDate.of(1950, 1, 1)
	val MAX_DATE: LocalDate = LocalDate.of(2050, 12, 31)

	/** 일수 기념일: 100일, 200일 … 1000일 */
	val DAY_COUNTS: List<Int> = (1..10).map { it * 100 }

	/** [startDate](처음 만난 날)의 자동 기념일 전부(2050-12-31까지), 날짜 오름차순. 같은 날이면 일수 → 주년 순 */
	fun autoAnniversaries(startDate: LocalDate): List<AutoAnniversary> {
		val days = DAY_COUNTS.map { AutoAnniversary(AutoAnniversaryType.DAYS, it, startDate.plusDays(it - 1L)) }
		val years = generateSequence(1) { it + 1 }
			.map { AutoAnniversary(AutoAnniversaryType.YEARS, it, yearsLater(startDate, it)) }
			.takeWhile { !it.date.isAfter(MAX_DATE) }
			.toList()
		return (days + years)
			.filter { !it.date.isAfter(MAX_DATE) }
			.sortedWith(compareBy<AutoAnniversary> { it.date }.thenBy { it.type.ordinal }.thenBy { it.count })
	}

	/** [startDate]의 자동 기념일 중 [from]~[to](양 끝 포함)에 드는 것 */
	fun autoAnniversariesBetween(startDate: LocalDate, from: LocalDate, to: LocalDate): List<AutoAnniversary> =
		autoAnniversaries(startDate).filter { !it.date.isBefore(from) && !it.date.isAfter(to) }

	/** 처음 만난 날 + [years]년. 2월 29일에서 시작해 윤년이 아닌 해가 되면 2월 28일 (`LocalDate.plusYears`) */
	private fun yearsLater(startDate: LocalDate, years: Int): LocalDate = startDate.plusYears(years.toLong())

	/**
	 * 직접 기념일([date], [repeatsYearly])의 발생일 중 [from]~[to](양 끝 포함)에 드는 것을 날짜 오름차순으로 돌려준다.
	 * 반복이면 등록한 해의 등록한 날짜부터 시작하므로 등록한 날짜보다 이른 발생일은 없다. [MIN_DATE]~[MAX_DATE] 밖은 뺀다.
	 */
	fun customOccurrencesBetween(date: LocalDate, repeatsYearly: Boolean, from: LocalDate, to: LocalDate): List<LocalDate> {
		val lower = if (from.isBefore(MIN_DATE)) MIN_DATE else from
		val upper = if (to.isAfter(MAX_DATE)) MAX_DATE else to
		if (lower.isAfter(upper)) return emptyList()
		if (!repeatsYearly) return listOf(date).filter { !it.isBefore(lower) && !it.isAfter(upper) }
		val firstYear = maxOf(date.year, lower.year)
		return (firstYear..upper.year)
			.map { sameDayInYear(date, it) }
			.filter { !it.isBefore(lower) && !it.isAfter(upper) }
	}

	/** [date]의 월·일을 [year]해로 옮긴다. 2월 29일은 윤년이 아닌 해에 2월 28일이다 (FR-ANNIV-03 AC3) */
	fun sameDayInYear(date: LocalDate, year: Int): LocalDate = date.withYear(year)
}
