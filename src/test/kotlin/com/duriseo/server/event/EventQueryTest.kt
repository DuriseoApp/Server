package com.duriseo.server.event

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.TestClockConfig
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import kotlin.test.assertEquals

/**
 * 일정 범위 조회 `GET /events?from=&to=` (FR-CAL-01 AC2, FR-CAL-02 AC5, R-01, API.md 3.3 "조회 규칙").
 * 날짜 범위 자르기(R-15)는 `EventDateRangeTest`.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class EventQueryTest : EventTestSupport() {

	private fun titlesOf(body: String): List<String> = JsonPath.read(body, "$.items[*].title")

	private fun titlesIn(accessToken: String, from: String, to: String): List<String> =
		titlesOf(bodyOf(getEvents(accessToken, from, to).andExpect(status().isOk)))

	// ── FR-CAL-01 AC2: 걸치는 일정 ───────────────────────

	@Test
	fun `FR-CAL-01 AC2 GET events는 범위 안에 걸치는 일정만 돌려주고 범위 밖 일정은 빼고 돌려준다`() {
		val guest = startGuest()
		val spaceId = activeSpaceIdOfUser(guest.userId)
		insertEventRow(spaceId, guest.userId, "안쪽", startDate = "2026-11-10", endDate = "2026-11-10")
		insertEventRow(spaceId, guest.userId, "앞에서 걸침", startDate = "2026-10-30", endDate = "2026-11-01")
		insertEventRow(spaceId, guest.userId, "뒤로 걸침", startDate = "2026-11-30", endDate = "2026-12-02")
		insertEventRow(spaceId, guest.userId, "범위를 덮음", startDate = "2026-10-01", endDate = "2026-12-31")
		insertEventRow(spaceId, guest.userId, "범위 전날 끝", startDate = "2026-10-30", endDate = "2026-10-31")
		insertEventRow(spaceId, guest.userId, "범위 다음 날 시작", startDate = "2026-12-01", endDate = "2026-12-01")

		val titles = titlesIn(guest.accessToken, "2026-11-01", "2026-11-30")

		assertEquals(setOf("안쪽", "앞에서 걸침", "뒤로 걸침", "범위를 덮음"), titles.toSet())
		assertEquals(4, titles.size)
	}

	@Test
	fun `FR-CAL-01 AC2 from과 to 날짜 자체에 있는 일정은 포함한다 (to를 포함)`() {
		val guest = startGuest()
		val spaceId = activeSpaceIdOfUser(guest.userId)
		insertEventRow(spaceId, guest.userId, "from 날", startDate = "2026-11-01", endDate = "2026-11-01")
		insertEventRow(spaceId, guest.userId, "to 날", startDate = "2026-11-30", endDate = "2026-11-30")

		assertEquals(setOf("from 날", "to 날"), titlesIn(guest.accessToken, "2026-11-01", "2026-11-30").toSet())
		// from = to인 하루 조회
		assertEquals(listOf("to 날"), titlesIn(guest.accessToken, "2026-11-30", "2026-11-30"))
	}

	@Test
	fun `FR-CAL-01 AC2 GET events는 종일 일정과 시간 일정을 함께 돌려준다`() {
		val guest = startGuest()
		postEventRaw(guest.accessToken, allDayJson(title = "종일", start = "2026-11-03", end = "2026-11-03")).andExpect(status().isCreated)
		postEventRaw(guest.accessToken, timedJson(title = "시간", start = "2026-11-03T01:00:00Z", end = "2026-11-03T02:00:00Z"))
			.andExpect(status().isCreated)

		getEvents(guest.accessToken, "2026-11-01", "2026-11-30")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].title").value("종일"))
			.andExpect(jsonPath("$.items[0].isAllDay").value(true))
			.andExpect(jsonPath("$.items[0].start").value("2026-11-03"))
			.andExpect(jsonPath("$.items[1].title").value("시간"))
			.andExpect(jsonPath("$.items[1].isAllDay").value(false))
			.andExpect(jsonPath("$.items[1].start").value("2026-11-03T01:00:00Z"))
			.andExpect(jsonPath("$.items[1].end").value("2026-11-03T02:00:00Z"))
	}

	@Test
	fun `FR-CAL-01 AC2 일정이 없으면 빈 items를 돌려준다`() {
		val guest = startGuest()

		getEvents(guest.accessToken, "2026-11-01", "2026-11-30")
			.andExpect(status().isOk)
			.andExpect(content().string("""{"items":[]}"""))
	}

	@Test
	fun `FR-CAL-02 AC5 3일 일정은 시작일·중간일·종료일 어느 날을 조회해도 나온다`() {
		val guest = startGuest()
		createEvent(guest.accessToken, allDayJson(title = "여행", start = "2026-11-10", end = "2026-11-12"))
		createEvent(guest.accessToken, timedJson(title = "행사", start = "2026-11-10T03:00:00Z", end = "2026-11-12T03:00:00Z"))

		for (day in listOf("2026-11-10", "2026-11-11", "2026-11-12")) {
			assertEquals(setOf("여행", "행사"), titlesIn(guest.accessToken, day, day).toSet(), day)
		}
		assertEquals(emptyList(), titlesIn(guest.accessToken, "2026-11-09", "2026-11-09"))
		assertEquals(emptyList(), titlesIn(guest.accessToken, "2026-11-13", "2026-11-13"))
	}

	// ── 요청자 시간대 기준 경계 ───────────────────────────

	@Test
	fun `FR-CAL-01 AC2 시간 일정의 범위 경계는 요청자 시간대 기준 날짜로 판단한다`() {
		val couple = newCouple()
		// UTC 15:30 = 서울 다음 날 00:30
		insertEventRow(
			couple.spaceId, couple.a.userId, "자정 직후", isAllDay = false,
			startAt = Instant.parse("2026-11-03T15:30:00Z"), endAt = Instant.parse("2026-11-03T16:30:00Z"),
		)

		// 서울 사용자(기본)에게는 11월 4일 일정이다
		assertEquals(emptyList(), titlesIn(couple.a.accessToken, "2026-11-03", "2026-11-03"))
		assertEquals(listOf("자정 직후"), titlesIn(couple.a.accessToken, "2026-11-04", "2026-11-04"))
		// UTC 사용자에게는 11월 3일 일정이다
		setTimeZone(couple.b, "UTC")
		assertEquals(listOf("자정 직후"), titlesIn(couple.b.accessToken, "2026-11-03", "2026-11-03"))
		assertEquals(emptyList(), titlesIn(couple.b.accessToken, "2026-11-04", "2026-11-04"))
	}

	@Test
	fun `FR-CAL-01 AC2 시간 일정이 다음 날 00시에 시작하면 앞 날 조회에 나오지 않는다`() {
		val guest = startGuest()
		val spaceId = activeSpaceIdOfUser(guest.userId)
		// 서울 2026-11-04 00:00 = UTC 2026-11-03T15:00:00Z
		insertEventRow(
			spaceId, guest.userId, "자정 시작", isAllDay = false,
			startAt = Instant.parse("2026-11-03T15:00:00Z"), endAt = Instant.parse("2026-11-03T16:00:00Z"),
		)
		// 서울 2026-11-03 23:59:59가 끝인 일정 (UTC 14:59:59)은 3일에 나온다
		insertEventRow(
			spaceId, guest.userId, "자정 직전 끝", isAllDay = false,
			startAt = Instant.parse("2026-11-03T14:00:00Z"), endAt = Instant.parse("2026-11-03T14:59:59Z"),
		)

		assertEquals(listOf("자정 직전 끝"), titlesIn(guest.accessToken, "2026-11-03", "2026-11-03"))
		assertEquals(listOf("자정 시작"), titlesIn(guest.accessToken, "2026-11-04", "2026-11-04"))
	}

	// ── 정렬 ────────────────────────────────────────────

	@Test
	fun `FR-CAL-01 AC2 정렬은 시작 날짜 오름차순, 같은 날이면 종일 먼저, 시간 일정은 시작 일시 순이다`() {
		val guest = startGuest()
		val spaceId = activeSpaceIdOfUser(guest.userId)
		// 서울 시간대 기준 시작 날짜: 시간 일정은 UTC에 9시간을 더한 날짜
		insertEventRow(spaceId, guest.userId, "3일 14시", isAllDay = false, startAt = Instant.parse("2026-11-03T05:00:00Z"), endAt = Instant.parse("2026-11-03T06:00:00Z"))
		insertEventRow(spaceId, guest.userId, "3일 종일", startDate = "2026-11-03", endDate = "2026-11-03")
		insertEventRow(spaceId, guest.userId, "3일 08시", isAllDay = false, startAt = Instant.parse("2026-11-02T23:00:00Z"), endAt = Instant.parse("2026-11-03T00:00:00Z"))
		insertEventRow(spaceId, guest.userId, "2일 19시", isAllDay = false, startAt = Instant.parse("2026-11-02T10:00:00Z"), endAt = Instant.parse("2026-11-02T11:00:00Z"))
		insertEventRow(spaceId, guest.userId, "2일 종일", startDate = "2026-11-02", endDate = "2026-11-02")
		insertEventRow(spaceId, guest.userId, "3일 10시", isAllDay = false, startAt = Instant.parse("2026-11-03T01:00:00Z"), endAt = Instant.parse("2026-11-03T02:00:00Z"))

		assertEquals(
			listOf("2일 종일", "2일 19시", "3일 종일", "3일 08시", "3일 10시", "3일 14시"),
			titlesIn(guest.accessToken, "2026-11-01", "2026-11-30"),
		)
	}

	@Test
	fun `FR-CAL-01 AC2 시작 날짜와 일시가 모두 같으면 id 순서로 돌려준다`() {
		val guest = startGuest()
		val spaceId = activeSpaceIdOfUser(guest.userId)
		val ids = (1..4).map { insertEventRow(spaceId, guest.userId, "같은 날 $it", startDate = "2026-11-03", endDate = "2026-11-03") }

		val body = bodyOf(getEvents(guest.accessToken, "2026-11-03", "2026-11-03").andExpect(status().isOk))

		assertEquals(ids.sorted().map { it.toString() }, JsonPath.read<List<String>>(body, "$.items[*].id"))
	}

	// ── R-01: 요청자의 공간 일정만 ────────────────────────

	@Test
	fun `FR-CAL-01 AC2 GET events는 요청자의 공간 일정만 돌려준다`() {
		val couple = newCouple()
		createEvent(couple.a.accessToken, allDayJson(title = "우리 일정", extra = ""","isTogether":true"""))
		// 다른 커플의 공간 일정
		val other = newCouple()
		createEvent(other.a.accessToken, allDayJson(title = "다른 커플 일정"))
		// 혼자 쓰는 사용자의 개인 공간 일정
		val guest = startGuest()
		createEvent(guest.accessToken, allDayJson(title = "게스트 일정"))

		assertEquals(listOf("우리 일정"), titlesIn(couple.b.accessToken, "2026-11-01", "2026-11-30"))
		assertEquals(listOf("다른 커플 일정"), titlesIn(other.b.accessToken, "2026-11-01", "2026-11-30"))
		assertEquals(listOf("게스트 일정"), titlesIn(guest.accessToken, "2026-11-01", "2026-11-30"))
	}

	@Test
	fun `R-01 GET events는 다른 공간의 일정을 결과에 넣지 않는다`() {
		val couple = newCouple()
		val guest = startGuest()
		insertEventRow(activeSpaceIdOfUser(guest.userId), guest.userId, "남의 일정", startDate = "2026-11-03", endDate = "2026-11-03")

		assertEquals(emptyList(), titlesIn(couple.a.accessToken, "2026-11-01", "2026-11-30"))
	}

	@Test
	fun `R-01 GET events는 해제된 이전 커플 공간의 일정을 돌려주지 않는다`() {
		val couple = newCouple()
		createEvent(couple.a.accessToken, allDayJson(title = "해제 전 일정", extra = ""","isTogether":true"""))
		assertEquals(listOf("해제 전 일정"), titlesIn(couple.b.accessToken, "2026-11-01", "2026-11-30"))

		unlinkCouple(couple.a.accessToken).andExpect(status().isNoContent)

		// 해제된 공간은 활성 공간이 아니다. 두 사람 모두 새 빈 개인 공간으로 돌아간다
		assertEquals(emptyList(), titlesIn(couple.a.accessToken, "2026-11-01", "2026-11-30"))
		assertEquals(emptyList(), titlesIn(couple.b.accessToken, "2026-11-01", "2026-11-30"))
	}

	// ── 쿼리 오류 ───────────────────────────────────────

	@Test
	fun `FR-CAL-01 AC2 from이 to보다 뒤면 400 VALIDATION_FAILED (to INVALID)이다`() {
		val guest = startGuest()

		expectField(
			getEvents(guest.accessToken, "2026-11-30", "2026-11-01").expectError(400, "VALIDATION_FAILED"),
			0, "to", "INVALID",
		)
	}

	@Test
	fun `FR-CAL-01 AC2 범위가 3개월을 넘으면 400 VALIDATION_FAILED (to INVALID)이고 3개월까지는 허용한다`() {
		val guest = startGuest()

		// from + 3개월(달력 월)까지는 허용: 2026-11-01 + 3개월 = 2027-02-01
		getEvents(guest.accessToken, "2026-11-01", "2027-02-01").andExpect(status().isOk)
		expectField(
			getEvents(guest.accessToken, "2026-11-01", "2027-02-02").expectError(400, "VALIDATION_FAILED"),
			0, "to", "INVALID",
		)
		// 달력 월로 더한다: 2026-11-30 + 3개월 = 2027-02-28 (31일 달이 없는 달은 마지막 날로 맞춘다)
		getEvents(guest.accessToken, "2026-11-30", "2027-02-28").andExpect(status().isOk)
		getEvents(guest.accessToken, "2026-11-30", "2027-03-01").expectError(400, "VALIDATION_FAILED")
	}

	@Test
	fun `FR-CAL-01 AC2 from이나 to가 없으면 400 VALIDATION_FAILED (REQUIRED)이고 틀린 필드를 모두 담는다`() {
		val guest = startGuest()

		val none = getEvents(guest.accessToken, null, null).expectError(400, "VALIDATION_FAILED")
		assertEquals(listOf("from", "to"), fieldNamesOf(none))
		expectField(none, 0, "from", "REQUIRED")
		expectField(none, 1, "to", "REQUIRED")

		val noTo = getEvents(guest.accessToken, "2026-11-01", null).expectError(400, "VALIDATION_FAILED")
		assertEquals(listOf("to"), fieldNamesOf(noTo))
		expectField(noTo, 0, "to", "REQUIRED")
		val noFrom = getEvents(guest.accessToken, null, "2026-11-30").expectError(400, "VALIDATION_FAILED")
		expectField(noFrom, 0, "from", "REQUIRED")
	}

	@Test
	fun `FR-CAL-01 AC2 from이나 to가 날짜 형식이 아니면 400 VALIDATION_FAILED (INVALID)`() {
		val guest = startGuest()

		for (bad in listOf("2026-13-01", "2026/11/01", "20261101", "2026-11-1", "", "abc", "2026-11-01T00:00:00Z")) {
			expectField(
				getEvents(guest.accessToken, bad, "2026-11-30").expectError(400, "VALIDATION_FAILED"),
				0, "from", "INVALID",
			)
			expectField(
				getEvents(guest.accessToken, "2026-11-01", bad).expectError(400, "VALIDATION_FAILED"),
				0, "to", "INVALID",
			)
		}
	}
}
