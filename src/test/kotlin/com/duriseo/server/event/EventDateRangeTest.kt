package com.duriseo.server.event

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.TestClockConfig
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import kotlin.test.assertEquals

/**
 * 날짜 범위 1950-01-01 ~ 2050-12-31 (FR-CAL-01 AC6, R-15, D-74, API.md 1장·3.3).
 * 쓰기(`POST`·`PATCH /events`)는 400 `DATE_OUT_OF_RANGE`, 범위 조회(`GET /events`)는 오류 없이 범위 안으로 잘라서 조회한다.
 * 시간 일정의 날짜는 UTC가 아니라 요청자 `users.time_zone` 기준이다 (새 사용자는 `Asia/Seoul`).
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class EventDateRangeTest : EventTestSupport() {

	private fun titlesIn(accessToken: String, from: String, to: String): List<String> =
		JsonPath.read(bodyOf(getEvents(accessToken, from, to).andExpect(status().isOk)), "$.items[*].title")

	// ── 쓰기: 허용 ──────────────────────────────────────

	@Test
	fun `R-15 경계 날짜 1950-01-01과 2050-12-31은 일정으로 저장된다 (종일)`() {
		val guest = startGuest()

		postEventRaw(guest.accessToken, allDayJson(title = "첫날", start = "1950-01-01", end = "1950-01-01"))
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.start").value("1950-01-01"))
		postEventRaw(guest.accessToken, allDayJson(title = "마지막 날", start = "2050-12-31", end = "2050-12-31"))
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.end").value("2050-12-31"))
		postEventRaw(guest.accessToken, allDayJson(title = "처음부터 끝까지", start = "1950-01-01", end = "2050-12-31")).andExpect(status().isCreated)
		assertEquals(3, eventCount())
	}

	@Test
	fun `R-15 경계 날짜 1950-01-01과 2050-12-31은 일정으로 저장된다 (시간 일정)`() {
		val guest = startGuest()

		// 서울 시간대: UTC 1949-12-31T15:00Z = 서울 1950-01-01 00:00, UTC 2050-12-31T14:59:59Z = 서울 2050-12-31 23:59:59
		postEventRaw(guest.accessToken, timedJson(start = "1949-12-31T15:00:00Z", end = "1949-12-31T16:00:00Z")).andExpect(status().isCreated)
		postEventRaw(guest.accessToken, timedJson(start = "2050-12-31T14:00:00Z", end = "2050-12-31T14:59:59Z")).andExpect(status().isCreated)
		assertEquals(2, eventCount())
	}

	// ── 쓰기: 거부 ──────────────────────────────────────

	@Test
	fun `R-15 시작 날짜가 1950-01-01보다 앞이면 POST events가 400 DATE_OUT_OF_RANGE (start OUT_OF_RANGE)다`() {
		val guest = startGuest()

		val result = postEventRaw(guest.accessToken, allDayJson(start = "1949-12-31", end = "1950-01-02")).expectError(400, "DATE_OUT_OF_RANGE")

		assertEquals(listOf("start"), fieldNamesOf(result))
		expectField(result, 0, "start", "OUT_OF_RANGE")
		assertEquals(0, eventCount())
	}

	@Test
	fun `R-15 종료 날짜가 2050-12-31보다 뒤면 400 DATE_OUT_OF_RANGE (end OUT_OF_RANGE)다`() {
		val guest = startGuest()

		val result = postEventRaw(guest.accessToken, allDayJson(start = "2050-12-30", end = "2051-01-01")).expectError(400, "DATE_OUT_OF_RANGE")

		assertEquals(listOf("end"), fieldNamesOf(result))
		expectField(result, 0, "end", "OUT_OF_RANGE")
		assertEquals(0, eventCount())
	}

	@Test
	fun `R-15 시작·종료가 둘 다 범위 밖이면 둘 다 담는다`() {
		val guest = startGuest()

		val result = postEventRaw(guest.accessToken, allDayJson(start = "1949-01-01", end = "2051-01-01")).expectError(400, "DATE_OUT_OF_RANGE")

		assertEquals(listOf("start", "end"), fieldNamesOf(result))
		expectField(result, 0, "start", "OUT_OF_RANGE")
		expectField(result, 1, "end", "OUT_OF_RANGE")
	}

	@Test
	fun `R-15 PATCH events로 날짜를 범위 밖으로 바꾸면 400 DATE_OUT_OF_RANGE이고 일정이 그대로다`() {
		val guest = startGuest()
		val id = createEvent(guest.accessToken, allDayJson(start = "2026-11-03", end = "2026-11-04"))
		val before = eventSnapshot()

		val start = patchEvent(guest.accessToken, id, """{"start":"1949-12-31","end":"2026-11-04"}""").expectError(400, "DATE_OUT_OF_RANGE")
		expectField(start, 0, "start", "OUT_OF_RANGE")
		val end = patchEvent(guest.accessToken, id, """{"end":"2051-01-01"}""").expectError(400, "DATE_OUT_OF_RANGE")
		expectField(end, 0, "end", "OUT_OF_RANGE")
		// 종일 ↔ 시간 전환도 새 값으로 검사한다
		val switched = patchEvent(
			guest.accessToken, id,
			"""{"isAllDay":false,"start":"2051-01-01T00:00:00Z","end":"2051-01-01T01:00:00Z"}""",
		).expectError(400, "DATE_OUT_OF_RANGE")
		assertEquals(listOf("start", "end"), fieldNamesOf(switched))

		assertEquals(before, eventSnapshot())
	}

	@Test
	fun `R-15 PATCH는 요청에 있는 날짜만 범위를 본다 - 저장된 쪽은 다시 거부하지 않는다`() {
		val couple = newCouple()
		// UTC 사용자가 만든 2050-12-31T20:00Z 일정은 서울 사용자에게는 2051-01-01이다 (저장된 값, 함께 일정)
		setTimeZone(couple.b, "UTC")
		val id = createEvent(couple.b.accessToken, timedJson(start = "2050-12-31T20:00:00Z", end = "2050-12-31T21:00:00Z", extra = ""","isTogether":true"""))

		// 서울 사용자(A)가 제목만 고치면 시간은 건드리지 않으므로 거부하지 않는다
		patchEvent(couple.a.accessToken, id, """{"title":"서울 사용자가 고침"}""").andExpect(status().isOk)
	}

	// ── 시간대 ──────────────────────────────────────────

	@Test
	fun `R-15 시간 일정의 날짜는 UTC가 아니라 요청자 시간대 기준이다 - 서울 사용자의 2051-01-01은 거부, UTC 사용자는 허용`() {
		val couple = newCouple()
		setTimeZone(couple.b, "UTC")
		val json = timedJson(start = "2050-12-31T20:00:00Z", end = "2050-12-31T21:00:00Z")

		// 서울 사용자: UTC 2050-12-31T20:00Z는 서울 2051-01-01 05:00
		val seoul = postEventRaw(couple.a.accessToken, json).expectError(400, "DATE_OUT_OF_RANGE")
		assertEquals(listOf("start", "end"), fieldNamesOf(seoul))
		assertEquals(0, eventCount())
		// UTC 사용자: 2050-12-31
		postEventRaw(couple.b.accessToken, json).andExpect(status().isCreated)
		assertEquals(1, eventCount())
	}

	@Test
	fun `R-15 시간 일정의 날짜는 UTC가 아니라 요청자 시간대 기준이다 - UTC 1949-12-31T15시는 서울 사용자에게 1950-01-01이라 허용, UTC 사용자는 거부`() {
		val couple = newCouple()
		setTimeZone(couple.b, "UTC")
		val json = timedJson(start = "1949-12-31T15:00:00Z", end = "1949-12-31T16:00:00Z")

		postEventRaw(couple.a.accessToken, json).andExpect(status().isCreated)
		postEventRaw(couple.b.accessToken, json).expectError(400, "DATE_OUT_OF_RANGE")
		assertEquals(1, eventCount())
	}

	// ── VALIDATION_FAILED와 DATE_OUT_OF_RANGE 우선순위 ───

	@Test
	fun `R-15 VALIDATION_FAILED가 하나라도 있으면 그것만 응답하고 DATE_OUT_OF_RANGE는 그 뒤에 본다`() {
		val guest = startGuest()

		// 제목이 없고 시작 날짜도 범위 밖: VALIDATION_FAILED(title)만, 한 응답에 두 코드를 섞지 않는다
		val result = postEventRaw(guest.accessToken, """{"isAllDay":true,"start":"1949-01-01","end":"1949-01-02"}""")
			.expectError(400, "VALIDATION_FAILED")
		assertEquals(listOf("title"), fieldNamesOf(result))

		// 종료가 시작보다 이르고 범위 밖: (end INVALID)가 먼저다
		val order = postEventRaw(guest.accessToken, allDayJson(start = "1949-01-02", end = "1949-01-01")).expectError(400, "VALIDATION_FAILED")
		assertEquals(listOf("end"), fieldNamesOf(order))
		expectField(order, 0, "end", "INVALID")
		assertEquals(0, eventCount())
	}

	// ── 조회: 잘라서 조회 ───────────────────────────────

	@Test
	fun `R-15 GET events는 from이 1950-01-01보다 앞이어도 오류 없이 1950-01-01부터 조회한다`() {
		val guest = startGuest()
		val spaceId = activeSpaceIdOfUser(guest.userId)
		insertEventRow(spaceId, guest.userId, "1950년 첫날", startDate = "1950-01-01", endDate = "1950-01-02")
		insertEventRow(spaceId, guest.userId, "1950년 1월 말", startDate = "1950-01-30", endDate = "1950-01-30")

		// 월 달력이 앞 달 날짜까지 그린다: 1949-12-25 ~ 1950-02-05 (범위 안의 일정만 나온다)
		assertEquals(setOf("1950년 첫날", "1950년 1월 말"), titlesIn(guest.accessToken, "1949-12-25", "1950-02-05").toSet())
	}

	@Test
	fun `R-15 GET events는 to가 2050-12-31보다 뒤여도 오류 없이 2050-12-31까지 조회한다`() {
		val guest = startGuest()
		val spaceId = activeSpaceIdOfUser(guest.userId)
		insertEventRow(spaceId, guest.userId, "2050년 마지막 날", startDate = "2050-12-31", endDate = "2050-12-31")
		insertEventRow(spaceId, guest.userId, "2050년 12월 초", startDate = "2050-12-01", endDate = "2050-12-02")

		assertEquals(setOf("2050년 마지막 날", "2050년 12월 초"), titlesIn(guest.accessToken, "2050-11-25", "2051-01-05").toSet())
	}

	@Test
	fun `R-15 GET events는 범위 밖으로 나간 시간 일정도 잘라서 조회한다`() {
		val guest = startGuest()
		val spaceId = activeSpaceIdOfUser(guest.userId)
		// 서울 2050-12-31 23:00 ~ 2051-01-01 01:00 (여러 날 일정이 범위 끝을 넘어감)
		insertEventRow(
			spaceId, guest.userId, "해넘이", isAllDay = false,
			startAt = Instant.parse("2050-12-31T14:00:00Z"), endAt = Instant.parse("2050-12-31T16:00:00Z"),
		)

		assertEquals(listOf("해넘이"), titlesIn(guest.accessToken, "2050-12-31", "2051-01-02"))
	}

	@Test
	fun `R-15 잘라낸 조회 구간이 비면 빈 items를 돌려준다`() {
		val guest = startGuest()
		val spaceId = activeSpaceIdOfUser(guest.userId)
		insertEventRow(spaceId, guest.userId, "1950년 첫날", startDate = "1950-01-01", endDate = "1950-01-01")
		insertEventRow(spaceId, guest.userId, "2050년 마지막 날", startDate = "2050-12-31", endDate = "2050-12-31")

		// 전부 2051년: to를 잘라내면 from보다 앞이다
		getEvents(guest.accessToken, "2051-01-01", "2051-02-01")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.items.length()").value(0))
		// 전부 1950년 앞: from을 잘라내면 to보다 뒤다
		getEvents(guest.accessToken, "1900-01-01", "1900-03-01")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.items.length()").value(0))
		getEvents(guest.accessToken, "1949-11-01", "1949-12-31")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.items.length()").value(0))
	}

	@Test
	fun `R-15 from이 to보다 뒤인 검사와 3개월 초과 검사는 잘라내기 전 값으로 먼저 한다`() {
		val guest = startGuest()

		// 잘라내면 1950-01-01 ~ 1950-02-01 한 달이지만 요청받은 값 그대로는 25개월이라 400
		expectField(
			getEvents(guest.accessToken, "1948-01-01", "1950-02-01").expectError(400, "VALIDATION_FAILED"),
			0, "to", "INVALID",
		)
		expectField(
			getEvents(guest.accessToken, "2050-12-01", "2051-06-01").expectError(400, "VALIDATION_FAILED"),
			0, "to", "INVALID",
		)
		// from > to는 둘 다 범위 밖이어도 400이다
		getEvents(guest.accessToken, "2052-01-01", "2051-01-01").expectError(400, "VALIDATION_FAILED")
		getEvents(guest.accessToken, "1949-02-01", "1949-01-01").expectError(400, "VALIDATION_FAILED")
	}
}
