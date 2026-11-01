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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * 일정 알림 `PUT`·`DELETE /events/{id}/reminder`와 다시 계산 (FR-CAL-05 AC1~AC6, R-01·R-07, API.md 3.3 "알림 시각 계산"·"다시 계산", D-90, D-91).
 * 시계는 `2026-10-26T00:00:00Z`로 고정이다. 새 사용자의 시간대는 `Asia/Seoul`(UTC+9)이다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class EventReminderTest : EventTestSupport() {

	private val now: Instant = Instant.parse("2026-10-26T00:00:00Z")

	/** 응답 본문에서 오류 코드·메시지·details만 뽑는다 (`traceId`는 요청마다 다르다) */
	private fun errorShape(body: String): List<Any?> = listOf(
		JsonPath.read<String>(body, "$.code"),
		JsonPath.read<String>(body, "$.message"),
		JsonPath.read<Any?>(body, "$.details"),
	)

	/** 함께 일정으로 만든다 (상대도 고칠 수 있다) */
	private fun timedTogether(token: String, start: String = "2026-11-03T01:00:00Z", end: String = "2026-11-03T02:00:00Z"): UUID =
		createEvent(token, timedJson(start = start, end = end, extra = ""","isTogether":true"""))

	private fun allDayTogether(token: String, start: String = "2026-11-03", end: String = start): UUID =
		createEvent(token, allDayJson(start = start, end = end, extra = ""","isTogether":true"""))

	// ── PUT: FR-CAL-05 AC1·AC5 ───────────────────────────

	@Test
	fun `FR-CAL-05 AC1 시간 일정에 AT_TIME MIN_10 HOUR_1 DAY_1을 고르면 요청자의 행이 생기고 fire_at이 시작 시각 기준이다`() {
		val couple = newCouple()
		val expected = mapOf(
			"AT_TIME" to "2026-11-03T01:00:00Z",
			"MIN_10" to "2026-11-03T00:50:00Z",
			"HOUR_1" to "2026-11-03T00:00:00Z",
			"DAY_1" to "2026-11-02T01:00:00Z",
		)
		expected.forEach { (option, fireAt) ->
			val id = timedTogether(couple.a.accessToken)

			putReminder(couple.a.accessToken, id, option)
				.andExpect(status().isOk)
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.reminder.option").value(option))
				.andExpect(jsonPath("$.reminder.fireAt").value(fireAt))

			assertEquals(option, optionOf(id, couple.a.userId))
			assertEquals(Instant.parse(fireAt), fireAtOf(id, couple.a.userId))
			assertNull(sentAtOf(id, couple.a.userId))
			assertEquals(0, reminderCount(id, couple.b.userId))
		}
	}

	@Test
	fun `FR-CAL-05 AC1 PUT 응답은 sent_at을 내보내지 않고 GET으로 다시 읽어도 같은 알림이다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken)

		val body = bodyOf(putReminder(couple.a.accessToken, id, "MIN_10").andExpect(status().isOk))
		assertFalse(body.contains("sentAt") || body.contains("sent_at"))

		getEvent(couple.a.accessToken, id)
			.andExpect(jsonPath("$.reminder.option").value("MIN_10"))
			.andExpect(jsonPath("$.reminder.fireAt").value("2026-11-03T00:50:00Z"))
	}

	@Test
	fun `FR-CAL-05 AC1 상대가 만든 개인 일정에도 내 알림을 설정할 수 있다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, timedJson())

		putReminder(couple.b.accessToken, id, "HOUR_1").andExpect(status().isOk)

		assertEquals("HOUR_1", optionOf(id, couple.b.userId))
		assertEquals(0, reminderCount(id, couple.a.userId))
		// 일정 자체는 바뀌지 않는다
		assertEquals(null, jdbc.queryForObject("select updated_by from events where id = ?", UUID::class.java, id))
	}

	@Test
	fun `FR-CAL-05 AC1 알림을 다시 고르면 행이 하나로 유지되고 option과 fire_at이 바뀐다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken)

		putReminder(couple.a.accessToken, id, "MIN_10").andExpect(status().isOk)
		putReminder(couple.a.accessToken, id, "DAY_1")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.reminder.option").value("DAY_1"))
			.andExpect(jsonPath("$.reminder.fireAt").value("2026-11-02T01:00:00Z"))

		assertEquals(1, reminderCount(id, couple.a.userId))
		assertEquals("DAY_1", optionOf(id, couple.a.userId))
		assertEquals(Instant.parse("2026-11-02T01:00:00Z"), fireAtOf(id, couple.a.userId))
	}

	@Test
	fun `FR-CAL-05 AC1 같은 PUT을 다시 보내도 같은 결과이고 행은 하나다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken)

		val first = bodyOf(putReminder(couple.a.accessToken, id, "MIN_10").andExpect(status().isOk))
		val second = bodyOf(putReminder(couple.a.accessToken, id, "MIN_10").andExpect(status().isOk))

		assertEquals(first, second)
		assertEquals(1, reminderCount(id, couple.a.userId))
	}

	@Test
	fun `FR-CAL-05 AC1 같은 사용자의 PUT 두 개가 동시에 와도 둘 다 200이고 행은 하나다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken)

		val statuses = inParallel(
			listOf(
				{ statusOf(putReminder(couple.a.accessToken, id, "MIN_10")) },
				{ statusOf(putReminder(couple.a.accessToken, id, "HOUR_1")) },
			),
		)

		assertEquals(listOf(200, 200), statuses)
		assertEquals(1, reminderCount(id, couple.a.userId))
	}

	@Test
	fun `FR-CAL-05 AC1 알림 끄기는 내 행만 지우고 204이며 상대의 행은 남는다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken)
		putReminder(couple.a.accessToken, id, "MIN_10").andExpect(status().isOk)
		putReminder(couple.b.accessToken, id, "HOUR_1").andExpect(status().isOk)

		deleteReminder(couple.a.accessToken, id).andExpect(status().isNoContent).andExpect(content().string(""))

		assertEquals(0, reminderCount(id, couple.a.userId))
		assertEquals(1, reminderCount(id, couple.b.userId))
		getEvent(couple.a.accessToken, id).andExpect(jsonPath("$.reminder").doesNotExist())
		getEvent(couple.b.accessToken, id).andExpect(jsonPath("$.reminder.option").value("HOUR_1"))
	}

	@Test
	fun `FR-CAL-05 AC1 알림이 없는 일정에 DELETE reminder를 불러도 204다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken)

		deleteReminder(couple.a.accessToken, id).andExpect(status().isNoContent)
		deleteReminder(couple.a.accessToken, id).andExpect(status().isNoContent)

		assertEquals(0, count("select count(*) from event_reminders"))
	}

	@Test
	fun `FR-CAL-05 AC1 알림 끄기 뒤 다시 PUT하면 새 행이 생긴다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken)
		putReminder(couple.a.accessToken, id, "MIN_10").andExpect(status().isOk)
		deleteReminder(couple.a.accessToken, id).andExpect(status().isNoContent)

		putReminder(couple.a.accessToken, id, "AT_TIME").andExpect(status().isOk)

		assertEquals("AT_TIME", optionOf(id, couple.a.userId))
	}

	@Test
	fun `FR-CAL-05 AC1 option이 없거나 null이면 400 VALIDATION_FAILED REQUIRED다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken)

		listOf("""{}""", """{"option":null}""", null, """{"opt":"MIN_10"}""").forEach { json ->
			expectField(putReminderRaw(couple.a.accessToken, id, json).expectError(400, "VALIDATION_FAILED"), 0, "option", "REQUIRED")
		}

		assertEquals(0, count("select count(*) from event_reminders"))
	}

	@Test
	fun `FR-CAL-05 AC1 모르는 option 값과 문자열이 아닌 값은 400 VALIDATION_FAILED INVALID이고 NONE도 없는 값이다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken)

		listOf("""{"option":"NONE"}""", """{"option":"min_10"}""", """{"option":""}""", """{"option":10}""", """{"option":["MIN_10"]}""")
			.forEach { json ->
				expectField(putReminderRaw(couple.a.accessToken, id, json).expectError(400, "VALIDATION_FAILED"), 0, "option", "INVALID")
			}

		assertEquals(0, count("select count(*) from event_reminders"))
	}

	@Test
	fun `FR-CAL-05 AC1 거부된 PUT은 이미 있는 알림을 바꾸지 않는다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken)
		putReminder(couple.a.accessToken, id, "MIN_10").andExpect(status().isOk)
		val before = eventSnapshot()

		putReminder(couple.a.accessToken, id, "ALLDAY_9AM").expectError(400, "VALIDATION_FAILED")

		assertEquals(before, eventSnapshot())
	}

	// ── PUT: FR-CAL-05 AC3 ───────────────────────────────

	@Test
	fun `FR-CAL-05 AC3 종일 일정에 ALLDAY_9AM과 ALLDAY_PREV_9AM을 고르면 fire_at이 요청자 시간대 기준 당일과 전날 09시다`() {
		val couple = newCouple()
		val id = allDayTogether(couple.a.accessToken, start = "2026-11-03")

		putReminder(couple.a.accessToken, id, "ALLDAY_9AM")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.reminder.option").value("ALLDAY_9AM"))
			// 서울 11월 3일 09:00 = UTC 00:00
			.andExpect(jsonPath("$.reminder.fireAt").value("2026-11-03T00:00:00Z"))
		putReminder(couple.b.accessToken, id, "ALLDAY_PREV_9AM")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.reminder.fireAt").value("2026-11-02T00:00:00Z"))

		assertNull(sentAtOf(id, couple.a.userId))
		assertNull(sentAtOf(id, couple.b.userId))
	}

	@Test
	fun `FR-CAL-05 AC3 종일 알림의 오전 9시는 알림 주인의 시간대를 쓴다 - 같은 일정에 두 사람이 다른 fire_at을 갖는다`() {
		val couple = newCouple()
		setTimeZone(couple.b, "UTC")
		val id = allDayTogether(couple.a.accessToken, start = "2026-11-03")

		putReminder(couple.a.accessToken, id, "ALLDAY_9AM").andExpect(status().isOk)
		putReminder(couple.b.accessToken, id, "ALLDAY_9AM").andExpect(status().isOk)

		assertEquals(Instant.parse("2026-11-03T00:00:00Z"), fireAtOf(id, couple.a.userId))
		assertEquals(Instant.parse("2026-11-03T09:00:00Z"), fireAtOf(id, couple.b.userId))
	}

	@Test
	fun `FR-CAL-05 AC3 종일 일정에 시간 일정용 option을 고르면 400 VALIDATION_FAILED option INVALID다`() {
		val couple = newCouple()
		val id = allDayTogether(couple.a.accessToken)

		listOf("AT_TIME", "MIN_10", "HOUR_1", "DAY_1").forEach { option ->
			expectField(putReminder(couple.a.accessToken, id, option).expectError(400, "VALIDATION_FAILED"), 0, "option", "INVALID")
		}

		assertEquals(0, count("select count(*) from event_reminders"))
	}

	@Test
	fun `FR-CAL-05 AC3 시간 일정에 종일용 option을 고르면 400 VALIDATION_FAILED option INVALID다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken)

		listOf("ALLDAY_9AM", "ALLDAY_PREV_9AM").forEach { option ->
			expectField(putReminder(couple.a.accessToken, id, option).expectError(400, "VALIDATION_FAILED"), 0, "option", "INVALID")
		}

		assertEquals(0, count("select count(*) from event_reminders"))
	}

	// ── 지난 시각: FR-CAL-05 AC5, D-90 ────────────────────

	@Test
	fun `FR-CAL-05 AC5 계산한 fire_at이 이미 지났으면 행은 저장되고 sent_at이 지금으로 기록된다`() {
		val couple = newCouple()
		// 시작 00:30Z의 1시간 전 = 어제 23:30Z, 지금은 00:00Z
		val id = timedTogether(couple.a.accessToken, start = "2026-10-26T00:30:00Z", end = "2026-10-26T01:00:00Z")

		putReminder(couple.a.accessToken, id, "HOUR_1")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.reminder.fireAt").value("2026-10-25T23:30:00Z"))

		assertEquals(Instant.parse("2026-10-25T23:30:00Z"), fireAtOf(id, couple.a.userId))
		assertEquals(now, sentAtOf(id, couple.a.userId))
	}

	@Test
	fun `FR-CAL-05 AC5 fire_at이 지금과 같아도 지난 것으로 보고 sent_at을 기록한다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken, start = "2026-10-26T01:00:00Z", end = "2026-10-26T02:00:00Z")

		putReminder(couple.a.accessToken, id, "HOUR_1").andExpect(status().isOk)

		assertEquals(now, fireAtOf(id, couple.a.userId))
		assertEquals(now, sentAtOf(id, couple.a.userId))
	}

	@Test
	fun `FR-CAL-05 AC5 fire_at이 아직 오지 않았으면 sent_at은 null이다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken, start = "2026-10-26T01:00:00Z", end = "2026-10-26T02:00:00Z")

		putReminder(couple.a.accessToken, id, "MIN_10").andExpect(status().isOk)

		assertEquals(Instant.parse("2026-10-26T00:50:00Z"), fireAtOf(id, couple.a.userId))
		assertNull(sentAtOf(id, couple.a.userId))
	}

	@Test
	fun `FR-CAL-05 AC5 이미 끝난 일정에도 알림을 설정할 수 있고 응답은 지난 fire_at을 그대로 보여준다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken, start = "2026-10-20T01:00:00Z", end = "2026-10-20T02:00:00Z")

		putReminder(couple.a.accessToken, id, "AT_TIME")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.reminder.fireAt").value("2026-10-20T01:00:00Z"))

		assertEquals(now, sentAtOf(id, couple.a.userId))
	}

	@Test
	fun `FR-CAL-05 AC5 지난 종일 알림도 저장되고 sent_at이 기록된다`() {
		val couple = newCouple()
		val id = allDayTogether(couple.a.accessToken, start = "2026-10-26")

		// 서울 10월 26일 09:00 = 00:00Z = 지금
		putReminder(couple.a.accessToken, id, "ALLDAY_9AM").andExpect(status().isOk)

		assertEquals(now, fireAtOf(id, couple.a.userId))
		assertEquals(now, sentAtOf(id, couple.a.userId))
	}

	// ── R-01·R-07 ────────────────────────────────────────

	@Test
	fun `R-01 다른 공간의 일정에 PUT reminder와 DELETE reminder는 404 NOT_FOUND이고 아무것도 바뀌지 않는다`() {
		val couple = newCouple()
		val other = newCouple()
		val id = timedTogether(other.a.accessToken)
		putReminder(other.a.accessToken, id, "MIN_10").andExpect(status().isOk)
		val before = eventSnapshot()

		putReminder(couple.a.accessToken, id, "HOUR_1").expectError(404, "NOT_FOUND")
		putReminder(couple.b.accessToken, id, "HOUR_1").expectError(404, "NOT_FOUND")
		deleteReminder(couple.a.accessToken, id).expectError(404, "NOT_FOUND")

		assertEquals(before, eventSnapshot())
	}

	@Test
	fun `R-01 없는 일정과 UUID 형식이 아닌 ID는 다른 공간 일정과 같은 404다`() {
		val couple = newCouple()
		val other = newCouple()
		val otherEvent = timedTogether(other.a.accessToken)
		val missing = UUID.randomUUID()

		val foreign = errorShape(bodyOf(putReminder(couple.a.accessToken, otherEvent, "MIN_10").expectError(404, "NOT_FOUND")))
		val absent = errorShape(bodyOf(putReminder(couple.a.accessToken, missing, "MIN_10").expectError(404, "NOT_FOUND")))
		putReminder(couple.a.accessToken, "not-a-uuid", "MIN_10").expectError(404, "NOT_FOUND")
		deleteReminder(couple.a.accessToken, missing).expectError(404, "NOT_FOUND")
		deleteReminder(couple.a.accessToken, "not-a-uuid").expectError(404, "NOT_FOUND")

		assertEquals(foreign, absent)
	}

	@Test
	fun `R-01 권한이 없는 요청에는 option 값 검사 오류를 보여주지 않고 404가 먼저다`() {
		val couple = newCouple()
		val other = newCouple()
		val id = timedTogether(other.a.accessToken)

		putReminderRaw(couple.a.accessToken, id, """{"option":"NONE"}""").expectError(404, "NOT_FOUND")
		putReminderRaw(couple.a.accessToken, id, null).expectError(404, "NOT_FOUND")
	}

	@Test
	fun `R-01 해제된 이전 커플 공간의 일정에는 알림을 설정할 수 없다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken)
		unlinkCouple(couple.a.accessToken).andExpect(status().isNoContent)

		putReminder(couple.a.accessToken, id, "MIN_10").expectError(404, "NOT_FOUND")
		putReminder(couple.b.accessToken, id, "MIN_10").expectError(404, "NOT_FOUND")
	}

	@Test
	fun `R-07 PUT과 DELETE는 요청자 자신의 행만 만들고 바꾸고 지운다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken)
		val bFireAt = Instant.parse("2026-11-03T00:00:00Z")
		insertEventReminderRow(id, couple.b.userId, "HOUR_1", bFireAt)

		putReminder(couple.a.accessToken, id, "MIN_10").andExpect(status().isOk).andExpect(jsonPath("$.reminder.option").value("MIN_10"))
		assertEquals("HOUR_1", optionOf(id, couple.b.userId))
		assertEquals(bFireAt, fireAtOf(id, couple.b.userId))

		deleteReminder(couple.a.accessToken, id).andExpect(status().isNoContent)
		assertEquals(1, reminderCount(id, couple.b.userId))
		assertEquals("HOUR_1", optionOf(id, couple.b.userId))
	}

	@Test
	fun `R-07 PUT 응답과 GET 응답에는 요청자의 알림만 들어간다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken)
		putReminder(couple.b.accessToken, id, "DAY_1").andExpect(status().isOk)

		putReminder(couple.a.accessToken, id, "MIN_10")
			.andExpect(jsonPath("$.reminder.option").value("MIN_10"))
		getEvent(couple.b.accessToken, id).andExpect(jsonPath("$.reminder.option").value("DAY_1"))
	}

	@Test
	fun `NFR-SYNC-01 PUT과 DELETE reminder는 일정 변경 이벤트를 발행하지 않는다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken)
		eventChanges.reset()

		putReminder(couple.a.accessToken, id, "MIN_10").andExpect(status().isOk)
		deleteReminder(couple.a.accessToken, id).andExpect(status().isNoContent)

		assertEquals(0, eventChanges.created.size + eventChanges.updated.size + eventChanges.deleted.size)
	}

	// ── 다시 계산: FR-CAL-05 AC4, D-91 ────────────────────

	@Test
	fun `FR-CAL-05 AC4 작성자가 일시를 바꾸면 알림을 건 두 사람의 fire_at이 새 일시 기준으로 다시 계산된다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken)
		putReminder(couple.a.accessToken, id, "MIN_10").andExpect(status().isOk)
		putReminder(couple.b.accessToken, id, "HOUR_1").andExpect(status().isOk)

		patchEvent(
			couple.a.accessToken,
			id,
			"""{"start":"2026-11-05T05:00:00Z","end":"2026-11-05T06:00:00Z"}""",
		)
			.andExpect(status().isOk)
			// 응답에는 요청자의 알림만
			.andExpect(jsonPath("$.reminder.option").value("MIN_10"))
			.andExpect(jsonPath("$.reminder.fireAt").value("2026-11-05T04:50:00Z"))

		assertEquals(Instant.parse("2026-11-05T04:50:00Z"), fireAtOf(id, couple.a.userId))
		assertEquals(Instant.parse("2026-11-05T04:00:00Z"), fireAtOf(id, couple.b.userId))
		assertEquals("HOUR_1", optionOf(id, couple.b.userId))
		assertNull(sentAtOf(id, couple.a.userId))
		assertNull(sentAtOf(id, couple.b.userId))
	}

	@Test
	fun `FR-CAL-05 AC4 상대가 함께 일정의 일시를 바꿔도 알림을 건 두 사람의 fire_at이 다시 계산된다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken)
		putReminder(couple.a.accessToken, id, "AT_TIME").andExpect(status().isOk)
		putReminder(couple.b.accessToken, id, "DAY_1").andExpect(status().isOk)

		patchEvent(couple.b.accessToken, id, """{"start":"2026-11-10T03:00:00Z","end":"2026-11-10T04:00:00Z"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.reminder.fireAt").value("2026-11-09T03:00:00Z"))

		assertEquals(Instant.parse("2026-11-10T03:00:00Z"), fireAtOf(id, couple.a.userId))
		assertEquals(Instant.parse("2026-11-09T03:00:00Z"), fireAtOf(id, couple.b.userId))
	}

	@Test
	fun `FR-CAL-05 AC4 종일 일정의 시작 날짜를 바꾸면 각 사람의 시간대로 다시 계산된다`() {
		val couple = newCouple()
		setTimeZone(couple.b, "UTC")
		val id = allDayTogether(couple.a.accessToken, start = "2026-11-03")
		putReminder(couple.a.accessToken, id, "ALLDAY_9AM").andExpect(status().isOk)
		putReminder(couple.b.accessToken, id, "ALLDAY_PREV_9AM").andExpect(status().isOk)

		patchEvent(couple.a.accessToken, id, """{"start":"2026-11-10","end":"2026-11-10"}""").andExpect(status().isOk)

		assertEquals(Instant.parse("2026-11-10T00:00:00Z"), fireAtOf(id, couple.a.userId))
		assertEquals(Instant.parse("2026-11-09T09:00:00Z"), fireAtOf(id, couple.b.userId))
	}

	@Test
	fun `FR-CAL-05 AC4 제목 메모 종료 시각 대상만 바꾸면 알림은 그대로다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken)
		putReminder(couple.a.accessToken, id, "MIN_10").andExpect(status().isOk)
		insertEventReminderRow(id, couple.b.userId, "HOUR_1", Instant.parse("2026-11-03T00:00:00Z"), sentAt = Instant.parse("2026-10-25T00:00:00Z"))
		val before = jdbc.queryForList("select * from event_reminders order by user_id").toString()

		patchEvent(couple.a.accessToken, id, """{"title":"새 제목","note":"새 메모"}""").andExpect(status().isOk)
		patchEvent(couple.b.accessToken, id, """{"end":"2026-11-03T05:00:00Z"}""").andExpect(status().isOk)
		patchEvent(couple.a.accessToken, id, """{"isTogether":false}""").andExpect(status().isOk)
		// 시작을 같은 값으로 다시 보내는 것도 바뀐 것이 아니다
		patchEvent(couple.a.accessToken, id, """{"start":"2026-11-03T01:00:00Z","end":"2026-11-03T05:00:00Z"}""").andExpect(status().isOk)

		assertEquals(before, jdbc.queryForList("select * from event_reminders order by user_id").toString())
	}

	@Test
	fun `FR-CAL-05 AC4 일정을 종일에서 시간 일정으로 바꾸면 그 일정의 모든 사용자 알림이 지워지고 응답의 reminder는 null이다`() {
		val couple = newCouple()
		val id = allDayTogether(couple.a.accessToken)
		val otherEvent = allDayTogether(couple.a.accessToken, start = "2026-11-04")
		putReminder(couple.a.accessToken, id, "ALLDAY_9AM").andExpect(status().isOk)
		putReminder(couple.b.accessToken, id, "ALLDAY_PREV_9AM").andExpect(status().isOk)
		putReminder(couple.a.accessToken, otherEvent, "ALLDAY_9AM").andExpect(status().isOk)

		patchEvent(couple.a.accessToken, id, """{"isAllDay":false,"start":"2026-11-03T01:00:00Z","end":"2026-11-03T02:00:00Z"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.isAllDay").value(false))
			.andExpect(jsonPath("$.reminder").doesNotExist())

		assertEquals(0, count("select count(*) from event_reminders where event_id = ?", id))
		// 다른 일정의 알림은 그대로
		assertEquals(1, reminderCount(otherEvent, couple.a.userId))
	}

	@Test
	fun `FR-CAL-05 AC4 일정을 시간 일정에서 종일로 바꿔도 상대가 바꾼 경우까지 모든 사용자 알림이 지워진다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken)
		putReminder(couple.a.accessToken, id, "MIN_10").andExpect(status().isOk)
		putReminder(couple.b.accessToken, id, "DAY_1").andExpect(status().isOk)

		patchEvent(couple.b.accessToken, id, """{"isAllDay":true,"start":"2026-11-03","end":"2026-11-03"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.reminder").doesNotExist())

		assertEquals(0, count("select count(*) from event_reminders where event_id = ?", id))
		getEvent(couple.a.accessToken, id).andExpect(jsonPath("$.reminder").doesNotExist())
	}

	@Test
	fun `FR-CAL-05 AC4 이미 보낸 알림이 있는 일정의 시작을 바꿔 새 fire_at이 미래이면 sent_at이 비워져 다시 보낸다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken, start = "2026-10-25T23:30:00Z", end = "2026-10-26T01:00:00Z")
		putReminder(couple.a.accessToken, id, "AT_TIME").andExpect(status().isOk)
		// 시작이 이미 지난 알림이라 저장할 때 sent_at이 찍혔다
		assertEquals(now, sentAtOf(id, couple.a.userId))
		insertEventReminderRow(id, couple.b.userId, "MIN_10", Instant.parse("2026-10-25T23:20:00Z"), sentAt = Instant.parse("2026-10-25T23:20:00Z"))

		patchEvent(couple.a.accessToken, id, """{"start":"2026-11-03T01:00:00Z","end":"2026-11-03T02:00:00Z"}""").andExpect(status().isOk)

		assertEquals(Instant.parse("2026-11-03T01:00:00Z"), fireAtOf(id, couple.a.userId))
		assertNull(sentAtOf(id, couple.a.userId))
		assertEquals(Instant.parse("2026-11-03T00:50:00Z"), fireAtOf(id, couple.b.userId))
		assertNull(sentAtOf(id, couple.b.userId))
	}

	@Test
	fun `FR-CAL-05 AC4 시작을 바꿔 새 fire_at이 이미 지났으면 sent_at이 지금으로 기록된다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken)
		putReminder(couple.a.accessToken, id, "MIN_10").andExpect(status().isOk)
		assertNull(sentAtOf(id, couple.a.userId))

		patchEvent(couple.a.accessToken, id, """{"start":"2026-10-20T01:00:00Z","end":"2026-10-20T02:00:00Z"}""").andExpect(status().isOk)

		assertEquals(Instant.parse("2026-10-20T00:50:00Z"), fireAtOf(id, couple.a.userId))
		assertEquals(now, sentAtOf(id, couple.a.userId))
	}

	@Test
	fun `FR-CAL-05 AC4 거부된 PATCH는 알림을 바꾸지 않는다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, timedJson())
		putReminder(couple.a.accessToken, id, "MIN_10").andExpect(status().isOk)
		putReminder(couple.b.accessToken, id, "HOUR_1").andExpect(status().isOk)
		val before = eventSnapshot()

		// 상대의 개인 일정은 R-02로 거부
		patchEvent(couple.b.accessToken, id, """{"start":"2026-11-10T03:00:00Z","end":"2026-11-10T04:00:00Z"}""").expectError(403, "NOT_AUTHOR")
		// 값 검사로 거부
		patchEvent(couple.a.accessToken, id, """{"start":"2026-11-10T03:00:00Z","end":"2026-11-10T02:00:00Z"}""").expectError(400, "VALIDATION_FAILED")
		// 날짜 범위로 거부
		patchEvent(couple.a.accessToken, id, """{"start":"2051-01-10T03:00:00Z","end":"2051-01-10T04:00:00Z"}""").expectError(400, "DATE_OUT_OF_RANGE")

		assertEquals(before, eventSnapshot())
	}

	@Test
	fun `FR-CAL-05 AC4 일정을 삭제하면 그 일정의 알림도 모두 지워진다`() {
		val couple = newCouple()
		val id = timedTogether(couple.a.accessToken)
		putReminder(couple.a.accessToken, id, "MIN_10").andExpect(status().isOk)
		putReminder(couple.b.accessToken, id, "HOUR_1").andExpect(status().isOk)

		deleteEvent(couple.a.accessToken, id).andExpect(status().isNoContent)

		assertEquals(0, count("select count(*) from event_reminders"))
	}

	// ── 시간대 변경: FR-CAL-05 AC6, D-91 ──────────────────

	@Test
	fun `FR-CAL-05 AC6 사용자의 시간대가 바뀌면 그 사용자의 아직 보내지 않은 종일 알림만 새 시간대로 다시 계산된다`() {
		val couple = newCouple()
		setTimeZone(couple.a, "UTC")
		val allDay = allDayTogether(couple.a.accessToken, start = "2026-11-03")
		val prev = allDayTogether(couple.a.accessToken, start = "2026-11-04")
		val timed = timedTogether(couple.a.accessToken)
		putReminder(couple.a.accessToken, allDay, "ALLDAY_9AM").andExpect(status().isOk)
		putReminder(couple.a.accessToken, prev, "ALLDAY_PREV_9AM").andExpect(status().isOk)
		putReminder(couple.a.accessToken, timed, "MIN_10").andExpect(status().isOk)
		putReminder(couple.b.accessToken, allDay, "ALLDAY_9AM").andExpect(status().isOk)
		val timedBefore = fireAtOf(timed, couple.a.userId)
		val bBefore = fireAtOf(allDay, couple.b.userId)
		assertEquals(Instant.parse("2026-11-03T09:00:00Z"), fireAtOf(allDay, couple.a.userId))

		patchMe(couple.a.accessToken, """{"timeZone":"America/Los_Angeles"}""").andExpect(status().isOk)

		// 로스앤젤레스는 11월 3일 PST(UTC-8)다: 09:00 = 17:00Z
		assertEquals(Instant.parse("2026-11-03T17:00:00Z"), fireAtOf(allDay, couple.a.userId))
		assertEquals(Instant.parse("2026-11-03T17:00:00Z"), fireAtOf(prev, couple.a.userId))
		assertEquals(timedBefore, fireAtOf(timed, couple.a.userId))
		assertEquals(bBefore, fireAtOf(allDay, couple.b.userId))
		assertNull(sentAtOf(allDay, couple.a.userId))
	}

	@Test
	fun `FR-CAL-05 AC6 이미 보낸 종일 알림은 시간대가 바뀌어도 다시 계산하지 않는다`() {
		val couple = newCouple()
		setTimeZone(couple.a, "UTC")
		val id = allDayTogether(couple.a.accessToken, start = "2026-11-03")
		val sent = Instant.parse("2026-10-25T00:00:00Z")
		val fireAt = Instant.parse("2026-11-03T09:00:00Z")
		insertEventReminderRow(id, couple.a.userId, "ALLDAY_9AM", fireAt, sentAt = sent)

		patchMe(couple.a.accessToken, """{"timeZone":"Asia/Tokyo"}""").andExpect(status().isOk)

		assertEquals(fireAt, fireAtOf(id, couple.a.userId))
		assertEquals(sent, sentAtOf(id, couple.a.userId))
	}

	@Test
	fun `FR-CAL-05 AC6 시간대를 바꿔 새 fire_at이 이미 지났으면 sent_at이 지금으로 기록된다`() {
		val couple = newCouple()
		setTimeZone(couple.a, "UTC")
		val id = allDayTogether(couple.a.accessToken, start = "2026-10-26")
		// UTC 10월 26일 09:00은 아직 오지 않았다
		putReminder(couple.a.accessToken, id, "ALLDAY_9AM").andExpect(status().isOk)
		assertNull(sentAtOf(id, couple.a.userId))

		// 서울 10월 26일 09:00 = 00:00Z = 지금
		patchMe(couple.a.accessToken, """{"timeZone":"Asia/Seoul"}""").andExpect(status().isOk)

		assertEquals(now, fireAtOf(id, couple.a.userId))
		assertEquals(now, sentAtOf(id, couple.a.userId))
	}

	@Test
	fun `FR-CAL-05 AC6 시간대가 바뀌지 않거나 닉네임만 바꾸면 알림은 그대로다`() {
		val couple = newCouple()
		val id = allDayTogether(couple.a.accessToken, start = "2026-11-03")
		putReminder(couple.a.accessToken, id, "ALLDAY_9AM").andExpect(status().isOk)
		val before = jdbc.queryForList("select * from event_reminders").toString()

		patchMe(couple.a.accessToken, """{"timeZone":"Asia/Seoul"}""").andExpect(status().isOk)
		patchMe(couple.a.accessToken, """{"nickname":"새이름"}""").andExpect(status().isOk)

		assertEquals(before, jdbc.queryForList("select * from event_reminders").toString())
	}

	@Test
	fun `FR-CAL-05 AC6 잘못된 시간대는 거부되고 알림은 그대로다`() {
		val couple = newCouple()
		setTimeZone(couple.a, "UTC")
		val id = allDayTogether(couple.a.accessToken, start = "2026-11-03")
		putReminder(couple.a.accessToken, id, "ALLDAY_9AM").andExpect(status().isOk)
		val before = fireAtOf(id, couple.a.userId)

		// IANA 이름이 아니라서 거부되고 아무것도 바뀌지 않는다
		patchMe(couple.a.accessToken, """{"timeZone":"Mars/Olympus"}""").expectError(400, "VALIDATION_FAILED")

		assertEquals(before, fireAtOf(id, couple.a.userId))
	}
}
