package com.duriseo.server.event

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.TestClockConfig
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.hamcrest.Matchers.containsString
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Date
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 일정 쓰기 `POST /events`, `PATCH /events/{id}`, `DELETE /events/{id}`의 값 규칙
 * (FR-CAL-02 AC1~AC4, FR-CAL-03 AC1·AC3, NFR-SYNC-03, API.md 3.3 "쓰기 규칙"). 권한 규칙은 `EventPermissionTest`, 날짜 범위는 `EventDateRangeTest`.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class EventWriteTest : EventTestSupport() {

	private fun startAtOf(eventId: UUID): Instant? =
		jdbc.queryForObject("select start_at from events where id = ?", Timestamp::class.java, eventId)?.toInstant()

	private fun endAtOf(eventId: UUID): Instant? =
		jdbc.queryForObject("select end_at from events where id = ?", Timestamp::class.java, eventId)?.toInstant()

	private fun startDateOf(eventId: UUID): LocalDate? =
		jdbc.queryForObject("select start_date from events where id = ?", Date::class.java, eventId)?.toLocalDate()

	private fun endDateOf(eventId: UUID): LocalDate? =
		jdbc.queryForObject("select end_date from events where id = ?", Date::class.java, eventId)?.toLocalDate()

	// ── POST /events: FR-CAL-02 AC1 ──────────────────────

	@Test
	fun `FR-CAL-02 AC1 제목과 시작·종료를 보내면 요청자의 활성 공간에 일정이 만들어진다`() {
		val guest = startGuest()
		val spaceId = activeSpaceIdOfUser(guest.userId)

		val body = bodyOf(
			postEventRaw(guest.accessToken, allDayJson(title = "회식", start = "2026-11-03", end = "2026-11-03"))
				.andExpect(status().isCreated)
				.andExpect(jsonPath("$.title").value("회식"))
				.andExpect(jsonPath("$.note").doesNotExist())
				.andExpect(jsonPath("$.isAllDay").value(true))
				.andExpect(jsonPath("$.start").value("2026-11-03"))
				.andExpect(jsonPath("$.end").value("2026-11-03"))
				.andExpect(jsonPath("$.isTogether").value(false))
				.andExpect(jsonPath("$.authorId").value(guest.userId.toString()))
				.andExpect(jsonPath("$.createdAt").value("2026-10-26T00:00:00Z"))
				.andExpect(jsonPath("$.updatedAt").value("2026-10-26T00:00:00Z")),
		)

		val id = UUID.fromString(JsonPath.read<String>(body, "$.id"))
		assertEquals(1, eventCount())
		assertEquals(1, count("select count(*) from events where id = ? and space_id = ? and author_id = ?", id, spaceId, guest.userId))
	}

	@Test
	fun `FR-CAL-02 AC1 응답은 값이 없는 필드를 빼지 않고 null로 보낸다`() {
		val guest = startGuest()

		postEventRaw(guest.accessToken, allDayJson())
			.andExpect(status().isCreated)
			.andExpect(content().string(containsString("\"note\":null")))
			.andExpect(content().string(containsString("\"updatedBy\":null")))
			.andExpect(content().string(containsString("\"reminder\":null")))
	}

	@Test
	fun `FR-CAL-02 AC1 제목이 없거나 빈 값이면 400 VALIDATION_FAILED (title REQUIRED)`() {
		val guest = startGuest()
		val bodies = listOf(
			"""{"isAllDay":true,"start":"2026-11-03","end":"2026-11-03"}""",
			"""{"title":null,"isAllDay":true,"start":"2026-11-03","end":"2026-11-03"}""",
			"""{"title":"","isAllDay":true,"start":"2026-11-03","end":"2026-11-03"}""",
		)

		for (json in bodies) {
			expectField(postEventRaw(guest.accessToken, json).expectError(400, "VALIDATION_FAILED"), 0, "title", "REQUIRED")
		}
		assertEquals(0, eventCount())
	}

	@Test
	fun `FR-CAL-02 AC1 공백만 있는 제목은 400 VALIDATION_FAILED (title REQUIRED)이고 앞뒤 공백은 지우고 저장한다`() {
		val guest = startGuest()

		expectField(
			postEventRaw(guest.accessToken, allDayJson(title = "   ")).expectError(400, "VALIDATION_FAILED"),
			0, "title", "REQUIRED",
		)
		assertEquals(0, eventCount())

		// D-89: 앞뒤 공백을 지운 값으로 저장하고 응답도 같다
		postEventRaw(guest.accessToken, allDayJson(title = "  회식 약속  "))
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.title").value("회식 약속"))
		assertEquals("회식 약속", jdbc.queryForObject("select title from events", String::class.java))
	}

	@Test
	fun `FR-CAL-02 AC1 제목이 문자열이 아니면 400 VALIDATION_FAILED (title INVALID)`() {
		val guest = startGuest()

		expectField(
			postEventRaw(guest.accessToken, """{"title":123,"isAllDay":true,"start":"2026-11-03","end":"2026-11-03"}""")
				.expectError(400, "VALIDATION_FAILED"),
			0, "title", "INVALID",
		)
		assertEquals(0, eventCount())
	}

	@Test
	fun `FR-CAL-02 AC1 제목이 50자를 넘으면 400 VALIDATION_FAILED (title TOO_LONG)이고 코드 포인트로 센다`() {
		val guest = startGuest()
		val emoji = "😀" // 코드 포인트 1개, UTF-16 2개

		// 이모지 50개는 UTF-16으로 100이지만 코드 포인트 50개라 저장된다 (NFR-LIMIT-01, D-82)
		postEventRaw(guest.accessToken, allDayJson(title = emoji.repeat(50))).andExpect(status().isCreated)
		assertEquals(1, eventCount())

		// 51개는 거부
		expectField(
			postEventRaw(guest.accessToken, allDayJson(title = emoji.repeat(51))).expectError(400, "VALIDATION_FAILED"),
			0, "title", "TOO_LONG",
		)
		assertEquals(1, eventCount())
	}

	@Test
	fun `FR-CAL-02 AC1 제목 길이는 앞뒤 공백을 지운 뒤 센다`() {
		val guest = startGuest()

		// 공백 포함 54자지만 지운 뒤 50자
		postEventRaw(guest.accessToken, allDayJson(title = "  " + "가".repeat(50) + "  ")).andExpect(status().isCreated)
		expectField(
			postEventRaw(guest.accessToken, allDayJson(title = "  " + "가".repeat(51) + "  ")).expectError(400, "VALIDATION_FAILED"),
			0, "title", "TOO_LONG",
		)
		assertEquals(1, eventCount())
	}

	@Test
	fun `FR-CAL-02 AC1 일정 메모가 1000자를 넘으면 400 VALIDATION_FAILED (note TOO_LONG)`() {
		val guest = startGuest()

		postEventRaw(guest.accessToken, allDayJson(extra = ""","note":"${"메".repeat(1000)}"""")).andExpect(status().isCreated)
		expectField(
			postEventRaw(guest.accessToken, allDayJson(extra = ""","note":"${"메".repeat(1001)}"""")).expectError(400, "VALIDATION_FAILED"),
			0, "note", "TOO_LONG",
		)
		assertEquals(1, eventCount())
	}

	@Test
	fun `FR-CAL-02 AC1 필수 필드가 없으면 틀린 필드를 모두 details fields에 담는다`() {
		val guest = startGuest()

		val result = postEventRaw(guest.accessToken, "{}").expectError(400, "VALIDATION_FAILED")

		assertEquals(listOf("title", "isAllDay", "start", "end"), fieldNamesOf(result))
		// 본문이 아예 없어도 같다
		val noBody = postEventRaw(guest.accessToken, null).expectError(400, "VALIDATION_FAILED")
		assertEquals(listOf("title", "isAllDay", "start", "end"), fieldNamesOf(noBody))
		assertEquals(0, eventCount())
	}

	@Test
	fun `FR-CAL-02 AC1 필수 필드에 null을 보내면 REQUIRED이고 boolean이 아닌 isAllDay는 INVALID`() {
		val guest = startGuest()

		val nulls = postEventRaw(guest.accessToken, """{"title":"a","isAllDay":null,"start":null,"end":null}""")
			.expectError(400, "VALIDATION_FAILED")
		assertEquals(listOf("isAllDay", "start", "end"), fieldNamesOf(nulls))
		expectField(nulls, 0, "isAllDay", "REQUIRED")
		expectField(nulls, 1, "start", "REQUIRED")

		expectField(
			postEventRaw(guest.accessToken, """{"title":"a","isAllDay":"yes","start":"2026-11-03","end":"2026-11-03"}""")
				.expectError(400, "VALIDATION_FAILED"),
			0, "isAllDay", "INVALID",
		)
		assertEquals(0, eventCount())
	}

	@Test
	fun `FR-CAL-02 AC1 isTogether와 notifyPartner에 null을 보내면 REQUIRED이고 boolean이 아닌 값은 INVALID`() {
		val couple = newCouple()

		val nullTogether = postEventRaw(couple.a.accessToken, allDayJson(extra = ""","isTogether":null""")).expectError(400, "VALIDATION_FAILED")
		expectField(nullTogether, 0, "isTogether", "REQUIRED")
		val nullNotify = postEventRaw(couple.a.accessToken, allDayJson(extra = ""","notifyPartner":null""")).expectError(400, "VALIDATION_FAILED")
		expectField(nullNotify, 0, "notifyPartner", "REQUIRED")
		val textTogether = postEventRaw(couple.a.accessToken, allDayJson(extra = ""","isTogether":"yes"""")).expectError(400, "VALIDATION_FAILED")
		expectField(textTogether, 0, "isTogether", "INVALID")
		assertEquals(0, eventCount())
	}

	@Test
	fun `FR-CAL-02 AC1 여러 날 일정은 종료 날짜가 시작보다 뒤인 일정으로 저장된다`() {
		val guest = startGuest()

		val id = createEvent(guest.accessToken, allDayJson(title = "1박 2일 여행", start = "2026-11-03", end = "2026-11-05"))

		assertEquals(LocalDate.of(2026, 11, 3), startDateOf(id))
		assertEquals(LocalDate.of(2026, 11, 5), endDateOf(id))
	}

	// ── POST /events: FR-CAL-02 AC2 ──────────────────────

	@Test
	fun `FR-CAL-02 AC2 종일 일정은 start_date·end_date만 저장하고 start_at·end_at은 null이다`() {
		val guest = startGuest()

		val id = createEvent(guest.accessToken, allDayJson(start = "2026-11-03", end = "2026-11-04"))

		assertEquals(LocalDate.of(2026, 11, 3), startDateOf(id))
		assertEquals(LocalDate.of(2026, 11, 4), endDateOf(id))
		assertNull(startAtOf(id))
		assertNull(endAtOf(id))
	}

	@Test
	fun `FR-CAL-02 AC2 시간 일정은 start_at·end_at만 저장하고 응답은 UTC 일시다`() {
		val guest = startGuest()

		// 오프셋이 있는 일시는 UTC로 바꿔 저장한다
		postEventRaw(guest.accessToken, timedJson(start = "2026-11-03T10:00:00+09:00", end = "2026-11-03T12:30:00+09:00"))
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.isAllDay").value(false))
			.andExpect(jsonPath("$.start").value("2026-11-03T01:00:00Z"))
			.andExpect(jsonPath("$.end").value("2026-11-03T03:30:00Z"))

		val id = jdbc.queryForObject("select id from events", UUID::class.java)!!
		assertEquals(Instant.parse("2026-11-03T01:00:00Z"), startAtOf(id))
		assertEquals(Instant.parse("2026-11-03T03:30:00Z"), endAtOf(id))
		assertNull(startDateOf(id))
		assertNull(endDateOf(id))
	}

	@Test
	fun `FR-CAL-02 AC2 종일 일정에 시간이 있는 값을 보내면 400 VALIDATION_FAILED이고 시간 일정에 날짜만 보내도 같다`() {
		val guest = startGuest()

		val allDayWithTime = postEventRaw(
			guest.accessToken,
			"""{"title":"a","isAllDay":true,"start":"2026-11-03T01:00:00Z","end":"2026-11-03T02:00:00Z"}""",
		).expectError(400, "VALIDATION_FAILED")
		assertEquals(listOf("start", "end"), fieldNamesOf(allDayWithTime))
		expectField(allDayWithTime, 0, "start", "INVALID")
		expectField(allDayWithTime, 1, "end", "INVALID")

		val timedWithDate = postEventRaw(
			guest.accessToken,
			"""{"title":"a","isAllDay":false,"start":"2026-11-03","end":"2026-11-03"}""",
		).expectError(400, "VALIDATION_FAILED")
		expectField(timedWithDate, 0, "start", "INVALID")
		expectField(timedWithDate, 1, "end", "INVALID")
		assertEquals(0, eventCount())
	}

	@Test
	fun `FR-CAL-02 AC2 날짜 형식이 아니거나 UTC 표기가 없는 일시는 400 VALIDATION_FAILED (INVALID)`() {
		val guest = startGuest()

		for (start in listOf("2026-13-45", "2026/11/03", "20261103", "2026-11-3")) {
			expectField(
				postEventRaw(guest.accessToken, allDayJson(start = start, end = "2026-11-30")).expectError(400, "VALIDATION_FAILED"),
				0, "start", "INVALID",
			)
		}
		// 시간대 표기가 없는 일시, 초가 없는 일시
		for (start in listOf("2026-11-03T01:00:00", "2026-11-03T01:00Z", "not-a-time")) {
			expectField(
				postEventRaw(guest.accessToken, timedJson(start = start, end = "2026-11-03T02:00:00Z")).expectError(400, "VALIDATION_FAILED"),
				0, "start", "INVALID",
			)
		}
		assertEquals(0, eventCount())
	}

	// ── POST /events: FR-CAL-02 AC3 ──────────────────────

	@Test
	fun `FR-CAL-02 AC3 종료가 시작보다 이른 시간 일정은 400 VALIDATION_FAILED (end INVALID)이고 저장되지 않는다`() {
		val guest = startGuest()

		expectField(
			postEventRaw(guest.accessToken, timedJson(start = "2026-11-03T02:00:00Z", end = "2026-11-03T01:59:59Z"))
				.expectError(400, "VALIDATION_FAILED"),
			0, "end", "INVALID",
		)
		assertEquals(0, eventCount())
	}

	@Test
	fun `FR-CAL-02 AC3 종료 날짜가 시작 날짜보다 이른 종일 일정도 400 VALIDATION_FAILED (end INVALID)`() {
		val guest = startGuest()

		expectField(
			postEventRaw(guest.accessToken, allDayJson(start = "2026-11-04", end = "2026-11-03")).expectError(400, "VALIDATION_FAILED"),
			0, "end", "INVALID",
		)
		assertEquals(0, eventCount())
	}

	@Test
	fun `FR-CAL-02 AC3 종료가 시작과 같은 일정은 저장된다`() {
		val guest = startGuest()

		postEventRaw(guest.accessToken, timedJson(start = "2026-11-03T01:00:00Z", end = "2026-11-03T01:00:00Z")).andExpect(status().isCreated)
		postEventRaw(guest.accessToken, allDayJson(start = "2026-11-03", end = "2026-11-03")).andExpect(status().isCreated)
		assertEquals(2, eventCount())
	}

	// ── POST /events: FR-CAL-02 AC4 ──────────────────────

	@Test
	fun `FR-CAL-02 AC4 isTogether를 생략하면 false이고 note를 생략하면 null이다`() {
		val couple = newCouple()

		val id = createEvent(couple.a.accessToken, allDayJson())

		assertEquals(1, count("select count(*) from events where id = ? and is_together = false and note is null", id))
	}

	@Test
	fun `FR-CAL-02 AC4 커플 공간에서 isTogether true로 만들면 함께 일정이 된다`() {
		val couple = newCouple()

		postEventRaw(couple.a.accessToken, allDayJson(extra = ""","isTogether":true,"note":"같이 가요""""))
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.isTogether").value(true))
			.andExpect(jsonPath("$.note").value("같이 가요"))
			.andExpect(jsonPath("$.authorId").value(couple.a.userId.toString()))

		assertEquals(1, count("select count(*) from events where is_together = true and space_id = ? and author_id = ?", couple.spaceId, couple.a.userId))
	}

	@Test
	fun `FR-CAL-02 AC4 POST events는 event_reminders를 만들지 않고 응답의 알림은 null이다`() {
		val couple = newCouple()

		postEventRaw(couple.a.accessToken, allDayJson(extra = ""","isTogether":true"""))
			.andExpect(status().isCreated)
			.andExpect(content().string(containsString("\"reminder\":null")))

		assertEquals(0, count("select count(*) from event_reminders"))
	}

	@Test
	fun `FR-CAL-02 AC4 일정의 대상 사용자는 받지 않는다 - 본문의 다른 사용자 ID 필드는 일정의 작성자·대상을 바꾸지 않는다`() {
		val couple = newCouple()
		val extra = ""","authorId":"${couple.b.userId}","author_id":"${couple.b.userId}","targetUserId":"${couple.b.userId}",""" +
			""""updatedBy":"${couple.b.userId}","spaceId":"${UUID.randomUUID()}","unknownField":1"""

		postEventRaw(couple.a.accessToken, allDayJson(extra = extra))
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.authorId").value(couple.a.userId.toString()))
			.andExpect(jsonPath("$.updatedBy").doesNotExist())
			.andExpect(jsonPath("$.isTogether").value(false))

		assertEquals(
			1,
			count("select count(*) from events where author_id = ? and updated_by is null and space_id = ?", couple.a.userId, couple.spaceId),
		)
	}

	// ── GET /events/{id}, PATCH: FR-CAL-03 AC1·AC3 ───────

	@Test
	fun `FR-CAL-03 AC1 작성자는 자기 일정의 제목·메모를 수정할 수 있고 updated_by·updated_at이 기록된다`() {
		val guest = startGuest()
		val id = createEvent(guest.accessToken, allDayJson(title = "회식", extra = ""","note":"처음 메모""""))
		clock.advance(Duration.ofMinutes(1))

		patchEvent(guest.accessToken, id, """{"title":"  송년회  ","note":"고친 메모"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.id").value(id.toString()))
			.andExpect(jsonPath("$.title").value("송년회"))
			.andExpect(jsonPath("$.note").value("고친 메모"))
			.andExpect(jsonPath("$.updatedBy").value(guest.userId.toString()))
			.andExpect(jsonPath("$.createdAt").value("2026-10-26T00:00:00Z"))
			.andExpect(jsonPath("$.updatedAt").value("2026-10-26T00:01:00Z"))

		assertEquals("송년회", titleOf(id))
		assertEquals(1, count("select count(*) from events where id = ? and updated_by = ? and note = '고친 메모'", id, guest.userId))
	}

	@Test
	fun `FR-CAL-03 AC1 작성자는 자기 일정의 일시를 수정할 수 있다`() {
		val guest = startGuest()
		val id = createEvent(guest.accessToken, timedJson(start = "2026-11-03T01:00:00Z", end = "2026-11-03T02:00:00Z"))

		patchEvent(guest.accessToken, id, """{"start":"2026-11-04T05:00:00Z","end":"2026-11-04T06:30:00Z"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.start").value("2026-11-04T05:00:00Z"))
			.andExpect(jsonPath("$.end").value("2026-11-04T06:30:00Z"))
			.andExpect(jsonPath("$.isAllDay").value(false))

		assertEquals(Instant.parse("2026-11-04T05:00:00Z"), startAtOf(id))
		assertEquals(Instant.parse("2026-11-04T06:30:00Z"), endAtOf(id))
	}

	@Test
	fun `FR-CAL-03 AC1 작성자는 종일을 시간 일정으로, 시간 일정을 종일로 바꿀 수 있다`() {
		val guest = startGuest()
		val id = createEvent(guest.accessToken, allDayJson(start = "2026-11-03", end = "2026-11-03"))

		patchEvent(guest.accessToken, id, """{"isAllDay":false,"start":"2026-11-03T01:00:00Z","end":"2026-11-03T02:00:00Z"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.isAllDay").value(false))
			.andExpect(jsonPath("$.start").value("2026-11-03T01:00:00Z"))
		assertEquals(Instant.parse("2026-11-03T01:00:00Z"), startAtOf(id))
		assertNull(startDateOf(id))
		assertNull(endDateOf(id))

		patchEvent(guest.accessToken, id, """{"isAllDay":true,"start":"2026-11-05","end":"2026-11-06"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.isAllDay").value(true))
			.andExpect(jsonPath("$.start").value("2026-11-05"))
			.andExpect(jsonPath("$.end").value("2026-11-06"))
		assertEquals(LocalDate.of(2026, 11, 5), startDateOf(id))
		assertNull(startAtOf(id))
		assertNull(endAtOf(id))
	}

	@Test
	fun `FR-CAL-03 AC1 PATCH는 부분 수정이라 없는 필드는 그대로이고 note null은 메모를 지운다`() {
		val guest = startGuest()
		val id = createEvent(guest.accessToken, allDayJson(title = "회식", start = "2026-11-03", end = "2026-11-04", extra = ""","note":"메모""""))

		patchEvent(guest.accessToken, id, """{"title":"송년회"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.note").value("메모"))
			.andExpect(jsonPath("$.start").value("2026-11-03"))
			.andExpect(jsonPath("$.end").value("2026-11-04"))

		patchEvent(guest.accessToken, id, """{"note":null}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.note").doesNotExist())
			.andExpect(jsonPath("$.title").value("송년회"))
		assertEquals(1, count("select count(*) from events where id = ? and note is null", id))
	}

	@Test
	fun `FR-CAL-03 AC1 PATCH에서 note 밖의 필드에 null을 보내면 400 VALIDATION_FAILED (REQUIRED)이고 일정은 그대로다`() {
		val guest = startGuest()
		val id = createEvent(guest.accessToken, allDayJson())
		val before = eventSnapshot()

		val result = patchEvent(guest.accessToken, id, """{"title":null,"isAllDay":null,"start":null,"end":null,"isTogether":null}""")
			.expectError(400, "VALIDATION_FAILED")

		assertEquals(listOf("title", "isAllDay", "start", "end", "isTogether"), fieldNamesOf(result))
		expectField(result, 0, "title", "REQUIRED")
		assertEquals(before, eventSnapshot())
	}

	@Test
	fun `FR-CAL-03 AC1 PATCH에서 isAllDay를 바꾸면 start와 end를 함께 보내야 하고 빠진 필드는 REQUIRED다`() {
		val guest = startGuest()
		val id = createEvent(guest.accessToken, allDayJson())
		val before = eventSnapshot()

		val none = patchEvent(guest.accessToken, id, """{"isAllDay":false}""").expectError(400, "VALIDATION_FAILED")
		assertEquals(listOf("start", "end"), fieldNamesOf(none))
		expectField(none, 0, "start", "REQUIRED")
		expectField(none, 1, "end", "REQUIRED")

		val onlyStart = patchEvent(guest.accessToken, id, """{"isAllDay":false,"start":"2026-11-03T01:00:00Z"}""")
			.expectError(400, "VALIDATION_FAILED")
		assertEquals(listOf("end"), fieldNamesOf(onlyStart))
		expectField(onlyStart, 0, "end", "REQUIRED")
		assertEquals(before, eventSnapshot())
	}

	@Test
	fun `FR-CAL-03 AC1 PATCH에서 isAllDay가 없으면 start와 end는 저장된 종류에 맞아야 한다`() {
		val guest = startGuest()
		val allDay = createEvent(guest.accessToken, allDayJson())
		val before = eventSnapshot()

		val result = patchEvent(guest.accessToken, allDay, """{"start":"2026-11-03T01:00:00Z"}""").expectError(400, "VALIDATION_FAILED")

		expectField(result, 0, "start", "INVALID")
		assertEquals(before, eventSnapshot())
	}

	@Test
	fun `FR-CAL-03 AC1 PATCH에서 종료가 합친 시작보다 이르면 400 VALIDATION_FAILED (end INVALID)이고 일정은 그대로다`() {
		val guest = startGuest()
		val id = createEvent(guest.accessToken, allDayJson(start = "2026-11-10", end = "2026-11-12"))
		val before = eventSnapshot()

		// end만 보냈을 때: 저장된 시작과 비교
		expectField(
			patchEvent(guest.accessToken, id, """{"end":"2026-11-09"}""").expectError(400, "VALIDATION_FAILED"),
			0, "end", "INVALID",
		)
		// start만 보냈을 때: 저장된 종료와 비교 (오류 필드는 end)
		expectField(
			patchEvent(guest.accessToken, id, """{"start":"2026-11-13"}""").expectError(400, "VALIDATION_FAILED"),
			0, "end", "INVALID",
		)
		assertEquals(before, eventSnapshot())

		// 둘 다 바꾸면 합친 값이 맞으면 된다
		patchEvent(guest.accessToken, id, """{"start":"2026-11-13","end":"2026-11-14"}""").andExpect(status().isOk)
	}

	@Test
	fun `FR-CAL-03 AC1 PATCH는 모르는 필드와 notifyPartner를 무시한다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(title = "회식"))

		patchEvent(couple.a.accessToken, id, """{"title":"송년회","notifyPartner":false,"authorId":"${couple.b.userId}","foo":"bar"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.title").value("송년회"))
			.andExpect(jsonPath("$.authorId").value(couple.a.userId.toString()))
	}

	@Test
	fun `FR-CAL-03 AC1 작성자는 자기 일정을 삭제할 수 있고 event_reminders도 함께 지워진다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(extra = ""","isTogether":true"""))
		insertEventReminderRow(id, couple.a.userId, "ALLDAY_9AM", Instant.parse("2026-11-03T00:00:00Z"))
		insertEventReminderRow(id, couple.b.userId, "ALLDAY_9AM", Instant.parse("2026-11-03T00:00:00Z"))

		deleteEvent(couple.a.accessToken, id).andExpect(status().isNoContent).andExpect(content().string(""))

		assertEquals(0, count("select count(*) from events where id = ?", id))
		assertEquals(0, count("select count(*) from event_reminders where event_id = ?", id))
		getEvent(couple.a.accessToken, id).expectError(404, "NOT_FOUND")
	}

	@Test
	fun `FR-CAL-03 AC1 일정을 삭제해도 그 일정을 가리키는 알림 행은 지우지 않는다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(extra = ""","isTogether":true"""))
		// 상대 일정 추가 알림처럼 일정을 가리키는 알림 행 (target_id는 FK가 아니다, D-43)
		jdbc.update(
			"insert into notifications (id, user_id, space_id, type, title, target_type, target_id, created_at) " +
				"values (?, ?, ?, 'PARTNER_CREATED', '에이님이 일정을 추가했어요', 'EVENT', ?, ?)",
			UUID.randomUUID(), couple.b.userId, couple.spaceId, id, Timestamp.from(clock.instant()),
		)

		deleteEvent(couple.a.accessToken, id).andExpect(status().isNoContent)

		assertEquals(1, count("select count(*) from notifications where type = 'PARTNER_CREATED' and target_id = ?", id))
	}

	@Test
	fun `FR-CAL-03 AC3 일정 응답에 작성자 ID가 있고 GET space의 구성원 닉네임으로 찾을 수 있다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(extra = ""","isTogether":true"""))

		// 응답에 닉네임은 없고 authorId만 있다
		getEvent(couple.b.accessToken, id)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.authorId").value(couple.a.userId.toString()))
			.andExpect(jsonPath("$.authorNickname").doesNotExist())
		val space = bodyOf(getSpace(couple.b.accessToken).andExpect(status().isOk))
		assertEquals(listOf("에이"), JsonPath.read<List<String>>(space, "$.members[?(@.userId == '${couple.a.userId}')].nickname"))
	}

	@Test
	fun `FR-CAL-03 GET events id는 일정 하나를 돌려준다`() {
		val guest = startGuest()
		val id = createEvent(guest.accessToken, timedJson(title = "미팅"))

		getEvent(guest.accessToken, id)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.id").value(id.toString()))
			.andExpect(jsonPath("$.title").value("미팅"))
			.andExpect(jsonPath("$.start").value("2026-11-03T01:00:00Z"))
	}

	// ── 동시성 ──────────────────────────────────────────

	@Test
	fun `NFR-SYNC-03 함께 일정을 두 사람이 동시에 수정해도 오류 없이 한쪽 값으로 저장된다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(title = "처음", extra = ""","isTogether":true"""))

		val statuses = inParallel(
			listOf(
				{ statusOf(patchEvent(couple.a.accessToken, id, """{"title":"에이가 고침"}""")) },
				{ statusOf(patchEvent(couple.b.accessToken, id, """{"title":"비비가 고침"}""")) },
			),
		)

		assertEquals(listOf(200, 200), statuses)
		assertTrue(titleOf(id) in setOf("에이가 고침", "비비가 고침"))
		assertEquals(1, eventCount())
	}

	@Test
	fun `FR-CAL-03 AC1 삭제된 일정은 PATCH와 DELETE가 404다`() {
		val guest = startGuest()
		val id = createEvent(guest.accessToken, allDayJson())

		deleteEvent(guest.accessToken, id).andExpect(status().isNoContent)

		patchEvent(guest.accessToken, id, """{"title":"뒤늦은 수정"}""").expectError(404, "NOT_FOUND")
		deleteEvent(guest.accessToken, id).expectError(404, "NOT_FOUND")
	}
}
