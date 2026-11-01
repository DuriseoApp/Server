package com.duriseo.server.event

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.TestClockConfig
import com.jayway.jsonpath.JsonPath
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/**
 * 일정 권한 규칙 (API.md 2장 R-01·R-02·R-07·R-10·R-13)을 경로로 확인한다 (FR-CAL-03 AC2·AC4·AC5, NFR-SEC-02).
 * 규칙마다 허용·거부 테스트를 둔다. 일정 알림 설정 경로(`PUT`·`DELETE /events/{id}/reminder`)의 R-01·R-07은 단위 3 2부가 더한다.
 * 이 테스트의 알림 행은 `event_reminders`에 직접 넣은 픽스처다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class EventPermissionTest : EventTestSupport() {

	private val fireAt: Instant = Instant.parse("2026-11-03T00:50:00Z")

	/** 응답 본문에서 오류 코드·메시지·details만 뽑는다 (`traceId`는 요청마다 다르다) */
	private fun errorShape(body: String): List<Any?> = listOf(
		JsonPath.read<String>(body, "$.code"),
		JsonPath.read<String>(body, "$.message"),
		JsonPath.read<Any?>(body, "$.details"),
	)

	// ── R-01 ────────────────────────────────────────────

	@Test
	fun `R-01 요청자의 공간 일정은 GET·PATCH·DELETE가 된다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(title = "회식", extra = ""","isTogether":true"""))

		getEvent(couple.a.accessToken, id).andExpect(status().isOk)
		getEvent(couple.b.accessToken, id).andExpect(status().isOk)
		patchEvent(couple.b.accessToken, id, """{"title":"송년회"}""").andExpect(status().isOk)
		deleteEvent(couple.a.accessToken, id).andExpect(status().isNoContent)
	}

	@Test
	fun `R-01 다른 커플 공간의 일정은 GET events id가 404 NOT_FOUND다`() {
		val couple = newCouple()
		val other = newCouple()
		val id = createEvent(other.a.accessToken, allDayJson(title = "다른 커플 일정", extra = ""","isTogether":true"""))

		getEvent(couple.a.accessToken, id).expectError(404, "NOT_FOUND")
		getEvent(couple.b.accessToken, id).expectError(404, "NOT_FOUND")
	}

	@Test
	fun `R-01 다른 공간의 일정은 PATCH·DELETE도 404이고 아무것도 바뀌지 않는다`() {
		val couple = newCouple()
		val other = newCouple()
		val id = createEvent(other.a.accessToken, allDayJson(title = "다른 커플 일정", extra = ""","isTogether":true"""))
		insertEventReminderRow(id, other.a.userId, "ALLDAY_9AM", fireAt)
		val before = eventSnapshot()

		patchEvent(couple.a.accessToken, id, """{"title":"가로채기","isTogether":false}""").expectError(404, "NOT_FOUND")
		deleteEvent(couple.a.accessToken, id).expectError(404, "NOT_FOUND")

		assertEquals(before, eventSnapshot())
	}

	@Test
	fun `R-01 없는 일정 ID와 다른 공간 일정은 본문까지 같은 404다`() {
		val couple = newCouple()
		val other = newCouple()
		val otherId = createEvent(other.a.accessToken, allDayJson(extra = ""","isTogether":true"""))

		val missing = bodyOf(getEvent(couple.a.accessToken, UUID.randomUUID()).expectError(404, "NOT_FOUND"))
		val foreign = bodyOf(getEvent(couple.a.accessToken, otherId).expectError(404, "NOT_FOUND"))
		assertEquals(errorShape(missing), errorShape(foreign))

		val missingPatch = bodyOf(patchEvent(couple.a.accessToken, UUID.randomUUID(), """{"title":"a"}""").expectError(404, "NOT_FOUND"))
		val foreignPatch = bodyOf(patchEvent(couple.a.accessToken, otherId, """{"title":"a"}""").expectError(404, "NOT_FOUND"))
		assertEquals(errorShape(missingPatch), errorShape(foreignPatch))
		assertEquals(errorShape(missing), errorShape(missingPatch))

		val missingDelete = bodyOf(deleteEvent(couple.a.accessToken, UUID.randomUUID()).expectError(404, "NOT_FOUND"))
		val foreignDelete = bodyOf(deleteEvent(couple.a.accessToken, otherId).expectError(404, "NOT_FOUND"))
		assertEquals(errorShape(missingDelete), errorShape(foreignDelete))
	}

	@Test
	fun `R-01 UUID 형식이 아닌 일정 ID도 같은 404다`() {
		val guest = startGuest()

		getEvent(guest.accessToken, "not-a-uuid").expectError(404, "NOT_FOUND")
		patchEvent(guest.accessToken, "not-a-uuid", """{"title":"a"}""").expectError(404, "NOT_FOUND")
		deleteEvent(guest.accessToken, "not-a-uuid").expectError(404, "NOT_FOUND")
	}

	@Test
	fun `R-01 권한이 없는 요청에는 값 검사 오류를 보여주지 않는다 - 다른 공간 일정의 잘못된 PATCH도 404다`() {
		val couple = newCouple()
		val other = newCouple()
		val id = createEvent(other.a.accessToken, allDayJson())

		patchEvent(couple.a.accessToken, id, """{"title":"","isAllDay":"x","start":null}""").expectError(404, "NOT_FOUND")
	}

	@Test
	fun `R-01 해제된 이전 커플 공간의 일정은 404다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(title = "해제 전 일정", extra = ""","isTogether":true"""))

		unlinkCouple(couple.a.accessToken).andExpect(status().isNoContent)

		// 해제 뒤 두 사람 모두 새 개인 공간에 있다. 해제된 공간은 활성 공간이 아니다
		getEvent(couple.a.accessToken, id).expectError(404, "NOT_FOUND")
		getEvent(couple.b.accessToken, id).expectError(404, "NOT_FOUND")
		patchEvent(couple.b.accessToken, id, """{"title":"해제 뒤 수정"}""").expectError(404, "NOT_FOUND")
		deleteEvent(couple.b.accessToken, id).expectError(404, "NOT_FOUND")
		assertEquals("해제 전 일정", titleOf(id))
	}

	@Test
	fun `R-01 가져오기 대기 중인 개인 공간의 일정은 커플 공간에서 404이고 가져온 뒤에는 보인다`() {
		val a = startMember("에이")
		val b = startMember("비비")
		val personalOfB = activeSpaceIdOfUser(b.userId)
		val id = insertEventRow(personalOfB, b.userId, "비비의 개인 일정", startDate = "2026-11-03", endDate = "2026-11-03")
		connect(a, b)

		// 활성 공간만 R-01의 기준이다. 대기 중인 개인 공간은 활성 공간이 아니다
		getEvent(b.accessToken, id).expectError(404, "NOT_FOUND")
		patchEvent(b.accessToken, id, """{"title":"가져오기 전 수정"}""").expectError(404, "NOT_FOUND")

		importRecords(b.accessToken, true).andExpect(status().isNoContent)

		getEvent(b.accessToken, id).andExpect(status().isOk).andExpect(jsonPath("$.title").value("비비의 개인 일정"))
		// 가져온 일정은 작성자를 유지한 채 커플 공간의 것이다. 상대(A)는 볼 수 있지만 '함께'가 아니라 고칠 수 없다
		getEvent(a.accessToken, id).andExpect(status().isOk).andExpect(jsonPath("$.authorId").value(b.userId.toString()))
		patchEvent(a.accessToken, id, """{"title":"에이가 고침"}""").expectError(403, "NOT_AUTHOR")
	}

	// ── R-02 ────────────────────────────────────────────

	@Test
	fun `R-02 작성자는 함께가 아닌 자기 일정을 수정·삭제할 수 있다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(title = "내 일정"))

		patchEvent(couple.a.accessToken, id, """{"title":"고친 내 일정"}""").andExpect(status().isOk)
		deleteEvent(couple.a.accessToken, id).andExpect(status().isNoContent)
	}

	@Test
	fun `R-02 함께 일정은 작성자가 아니어도 수정·삭제할 수 있다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(title = "함께 일정", extra = ""","isTogether":true"""))

		patchEvent(couple.b.accessToken, id, """{"title":"비비가 고침"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.authorId").value(couple.a.userId.toString()))
			.andExpect(jsonPath("$.updatedBy").value(couple.b.userId.toString()))
			.andExpect(jsonPath("$.isTogether").value(true))
		deleteEvent(couple.b.accessToken, id).andExpect(status().isNoContent)
		assertEquals(0, eventCount())
	}

	@Test
	fun `R-02 작성자가 아니고 함께도 아니면 PATCH·DELETE가 403 NOT_AUTHOR이다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(title = "에이의 개인 일정"))

		patchEvent(couple.b.accessToken, id, """{"title":"비비가 고침"}""").expectError(403, "NOT_AUTHOR")
		deleteEvent(couple.b.accessToken, id).expectError(403, "NOT_AUTHOR")
	}

	@Test
	fun `FR-CAL-03 AC2 상대의 개인 일정을 PATCH하면 403 NOT_AUTHOR이고 아무것도 바뀌지 않는다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(title = "에이의 개인 일정"))
		val before = eventSnapshot()

		patchEvent(couple.b.accessToken, id, """{"title":"비비가 고침","note":"메모","start":"2026-11-05","end":"2026-11-06"}""")
			.expectError(403, "NOT_AUTHOR")

		assertEquals(before, eventSnapshot())
	}

	@Test
	fun `FR-CAL-03 AC2 상대의 개인 일정을 DELETE하면 403 NOT_AUTHOR이고 일정이 남는다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(title = "에이의 개인 일정"))

		deleteEvent(couple.b.accessToken, id).expectError(403, "NOT_AUTHOR")

		assertEquals(1, eventCount())
		assertEquals("에이의 개인 일정", titleOf(id))
	}

	@Test
	fun `FR-CAL-03 AC2 상대의 개인 일정도 GET으로 볼 수 있다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(title = "에이의 개인 일정"))

		getEvent(couple.b.accessToken, id)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.title").value("에이의 개인 일정"))
			.andExpect(jsonPath("$.isTogether").value(false))
			.andExpect(jsonPath("$.authorId").value(couple.a.userId.toString()))
		getEvents(couple.b.accessToken, "2026-11-01", "2026-11-30")
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].title").value("에이의 개인 일정"))
	}

	@Test
	fun `R-02 조회는 작성자가 아니어도 된다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson())

		getEvent(couple.b.accessToken, id).andExpect(status().isOk)
		getEvents(couple.b.accessToken, "2026-11-01", "2026-11-30").andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(1))
	}

	@Test
	fun `R-02 거부되면 일정·알림이 하나도 바뀌지 않는다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(title = "에이의 개인 일정"))
		insertEventReminderRow(id, couple.a.userId, "ALLDAY_9AM", fireAt)
		insertEventReminderRow(id, couple.b.userId, "ALLDAY_PREV_9AM", fireAt)
		val before = eventSnapshot()

		patchEvent(couple.b.accessToken, id, """{"isAllDay":false,"start":"2026-11-03T01:00:00Z","end":"2026-11-03T02:00:00Z"}""")
			.expectError(403, "NOT_AUTHOR")
		deleteEvent(couple.b.accessToken, id).expectError(403, "NOT_AUTHOR")

		assertEquals(before, eventSnapshot())
		assertEquals(2, count("select count(*) from event_reminders where event_id = ?", id))
	}

	@Test
	fun `FR-CAL-03 AC2 R-02 권한이 없는 PATCH에는 값 검사 오류를 보여주지 않고 403이 먼저다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(title = "에이의 개인 일정"))

		patchEvent(couple.b.accessToken, id, """{"title":"","isAllDay":"x"}""").expectError(403, "NOT_AUTHOR")
	}

	@Test
	fun `FR-CAL-03 AC5 함께가 아닌 일정은 작성자만 수정·삭제한다`() {
		val couple = newCouple()
		val idOfA = createEvent(couple.a.accessToken, allDayJson(title = "에이 개인"))
		val idOfB = createEvent(couple.b.accessToken, allDayJson(title = "비비 개인"))

		patchEvent(couple.a.accessToken, idOfA, """{"title":"에이 개인 수정"}""").andExpect(status().isOk)
		patchEvent(couple.b.accessToken, idOfB, """{"title":"비비 개인 수정"}""").andExpect(status().isOk)
		patchEvent(couple.a.accessToken, idOfB, """{"title":"에이가 비비 것을"}""").expectError(403, "NOT_AUTHOR")
		patchEvent(couple.b.accessToken, idOfA, """{"title":"비비가 에이 것을"}""").expectError(403, "NOT_AUTHOR")
		deleteEvent(couple.a.accessToken, idOfB).expectError(403, "NOT_AUTHOR")
		deleteEvent(couple.b.accessToken, idOfA).expectError(403, "NOT_AUTHOR")
		deleteEvent(couple.a.accessToken, idOfA).andExpect(status().isNoContent)
		deleteEvent(couple.b.accessToken, idOfB).andExpect(status().isNoContent)
	}

	// ── R-13 ────────────────────────────────────────────

	@Test
	fun `FR-CAL-03 AC4 함께 일정은 상대도 수정할 수 있다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(title = "함께", extra = ""","isTogether":true"""))

		patchEvent(couple.b.accessToken, id, """{"title":"비비가 고침","note":"메모"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.title").value("비비가 고침"))
			.andExpect(jsonPath("$.note").value("메모"))
	}

	@Test
	fun `FR-CAL-03 AC4 함께 일정은 상대도 삭제할 수 있다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(extra = ""","isTogether":true"""))

		deleteEvent(couple.b.accessToken, id).andExpect(status().isNoContent)
		assertEquals(0, eventCount())
	}

	@Test
	fun `FR-CAL-03 AC4 상대가 함께 일정의 isTogether를 false로 바꾸면 403 NOT_AUTHOR이고 일정은 그대로다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(title = "함께", extra = ""","isTogether":true"""))
		val before = eventSnapshot()

		patchEvent(couple.b.accessToken, id, """{"isTogether":false}""").expectError(403, "NOT_AUTHOR")

		assertEquals(before, eventSnapshot())
		assertEquals(1, count("select count(*) from events where id = ? and is_together = true", id))
	}

	@Test
	fun `R-13 상대가 함께 일정의 isTogether를 바꾸면 403 NOT_AUTHOR이고 다른 필드도 바뀌지 않는다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(title = "함께", extra = ""","isTogether":true"""))
		val before = eventSnapshot()

		// 같은 요청에 제목도 있으면 통째로 거부한다
		patchEvent(couple.b.accessToken, id, """{"title":"비비가 고침","isTogether":false}""").expectError(403, "NOT_AUTHOR")

		assertEquals(before, eventSnapshot())
		assertEquals("함께", titleOf(id))
	}

	@Test
	fun `FR-CAL-03 AC4 상대가 함께 일정을 isTogether true 그대로 보내며 수정하면 허용된다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(title = "함께", extra = ""","isTogether":true"""))

		// 값을 바꾸지 않는 요청이라 R-02만 적용한다
		patchEvent(couple.b.accessToken, id, """{"title":"비비가 고침","isTogether":true}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.title").value("비비가 고침"))
			.andExpect(jsonPath("$.isTogether").value(true))
	}

	@Test
	fun `FR-CAL-03 AC4 작성자는 함께 일정을 개인 일정으로, 개인 일정을 함께 일정으로 바꿀 수 있다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(title = "함께", extra = ""","isTogether":true"""))

		patchEvent(couple.a.accessToken, id, """{"isTogether":false}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.isTogether").value(false))
		// 개인 일정이 되면 상대는 더 수정할 수 없다
		patchEvent(couple.b.accessToken, id, """{"title":"비비가 고침"}""").expectError(403, "NOT_AUTHOR")

		patchEvent(couple.a.accessToken, id, """{"isTogether":true}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.isTogether").value(true))
		patchEvent(couple.b.accessToken, id, """{"title":"비비가 고침"}""").andExpect(status().isOk)
	}

	@Test
	fun `R-13 작성자는 isTogether를 바꿀 수 있다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson())

		patchEvent(couple.a.accessToken, id, """{"isTogether":true}""").andExpect(status().isOk)
		assertEquals(1, count("select count(*) from events where id = ? and is_together = true", id))
	}

	@Test
	fun `R-13 상대가 isTogether 값을 바꾸지 않으면 함께 일정의 다른 필드를 수정할 수 있다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(extra = ""","isTogether":true"""))

		patchEvent(couple.b.accessToken, id, """{"note":"비비 메모","start":"2026-11-04","end":"2026-11-04"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.start").value("2026-11-04"))
	}

	@Test
	fun `R-13 상대의 개인 일정을 isTogether true로 바꾸려 해도 R-02가 먼저라 403이다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(title = "에이 개인"))
		val before = eventSnapshot()

		patchEvent(couple.b.accessToken, id, """{"isTogether":true}""").expectError(403, "NOT_AUTHOR")

		assertEquals(before, eventSnapshot())
	}

	// ── R-10 ────────────────────────────────────────────

	@Test
	fun `R-10 커플 공간은 isTogether true·false 모두 만들 수 있다`() {
		val couple = newCouple()

		postEventRaw(couple.a.accessToken, allDayJson(extra = ""","isTogether":true""")).andExpect(status().isCreated).andExpect(jsonPath("$.isTogether").value(true))
		postEventRaw(couple.a.accessToken, allDayJson(extra = ""","isTogether":false""")).andExpect(status().isCreated).andExpect(jsonPath("$.isTogether").value(false))
		assertEquals(2, eventCount())
	}

	@Test
	fun `R-10 개인 공간은 isTogether false로 만들 수 있다`() {
		val guest = startGuest()

		postEventRaw(guest.accessToken, allDayJson(extra = ""","isTogether":false""")).andExpect(status().isCreated)
		postEventRaw(guest.accessToken, allDayJson()).andExpect(status().isCreated)
		assertEquals(2, eventCount())
	}

	@Test
	fun `R-10 개인 공간에서 POST events의 isTogether true는 400 INVALID_TARGET이고 저장되지 않는다`() {
		val guest = startGuest()

		postEventRaw(guest.accessToken, allDayJson(extra = ""","isTogether":true""")).expectError(400, "INVALID_TARGET")

		assertEquals(0, eventCount())
	}

	@Test
	fun `FR-CAL-02 AC4 개인 공간에서 isTogether true면 400 INVALID_TARGET이고 저장되지 않는다`() {
		val member = startMember()

		postEventRaw(member.accessToken, timedJson(extra = ""","isTogether":true""")).expectError(400, "INVALID_TARGET")

		assertEquals(0, eventCount())
	}

	@Test
	fun `R-10 개인 공간에서 INVALID_TARGET은 값 검사보다 먼저 나간다`() {
		val guest = startGuest()

		// 제목이 없어도 R-10(INVALID_TARGET)이 먼저다 (API.md 3.3 검사 순서: R-10 → 값 검사)
		postEventRaw(guest.accessToken, """{"isAllDay":true,"start":"2026-11-03","end":"2026-11-03","isTogether":true}""")
			.expectError(400, "INVALID_TARGET")
	}

	@Test
	fun `R-10 개인 공간에서 PATCH events로 isTogether를 true로 바꾸면 400 INVALID_TARGET이고 일정은 그대로다`() {
		val guest = startGuest()
		val id = createEvent(guest.accessToken, allDayJson(title = "개인"))
		val before = eventSnapshot()

		patchEvent(guest.accessToken, id, """{"title":"고침","isTogether":true}""").expectError(400, "INVALID_TARGET")

		assertEquals(before, eventSnapshot())
	}

	@Test
	fun `R-10 개인 공간에서 PATCH events로 isTogether를 false로 보내는 것은 허용된다`() {
		val guest = startGuest()
		val id = createEvent(guest.accessToken, allDayJson(title = "개인"))

		patchEvent(guest.accessToken, id, """{"title":"고침","isTogether":false}""").andExpect(status().isOk)
	}

	@Test
	fun `R-10 isTogether가 null이면 R-10·R-13은 건너뛰고 값 검사가 REQUIRED로 응답한다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(extra = ""","isTogether":true"""))

		// 상대(B)가 함께 일정에 null을 보내도 403이 아니라 400이다 (API.md 3.3 검사 순서 마지막 항목)
		expectField(
			patchEvent(couple.b.accessToken, id, """{"isTogether":null}""").expectError(400, "VALIDATION_FAILED"),
			0, "isTogether", "REQUIRED",
		)
		expectField(
			patchEvent(couple.b.accessToken, id, """{"isTogether":"false"}""").expectError(400, "VALIDATION_FAILED"),
			0, "isTogether", "INVALID",
		)
	}

	// ── R-07 ────────────────────────────────────────────

	@Test
	fun `R-07 일정 응답은 요청자 자신의 알림을 담는다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, timedJson(extra = ""","isTogether":true"""))
		insertEventReminderRow(id, couple.a.userId, "MIN_10", fireAt)

		getEvent(couple.a.accessToken, id)
			.andExpect(jsonPath("$.reminder.option").value("MIN_10"))
			.andExpect(jsonPath("$.reminder.fireAt").value("2026-11-03T00:50:00Z"))
		getEvents(couple.a.accessToken, "2026-11-01", "2026-11-30")
			.andExpect(jsonPath("$.items[0].reminder.option").value("MIN_10"))
			.andExpect(jsonPath("$.items[0].reminder.fireAt").value("2026-11-03T00:50:00Z"))
		patchEvent(couple.a.accessToken, id, """{"title":"제목만 수정"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.reminder.option").value("MIN_10"))
			.andExpect(jsonPath("$.reminder.fireAt").value("2026-11-03T00:50:00Z"))
	}

	@Test
	fun `R-07 일정 응답에 상대의 알림 option·fireAt이 들어가지 않는다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, timedJson(extra = ""","isTogether":true"""))
		insertEventReminderRow(id, couple.a.userId, "MIN_10", fireAt)

		getEvent(couple.b.accessToken, id)
			.andExpect(status().isOk)
			.andExpect(content().string(containsString("\"reminder\":null")))
			.andExpect(content().string(not(containsString("MIN_10"))))
			.andExpect(content().string(not(containsString("00:50:00Z"))))
		getEvents(couple.b.accessToken, "2026-11-01", "2026-11-30")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(content().string(containsString("\"reminder\":null")))
			.andExpect(content().string(not(containsString("MIN_10"))))
		// 수정 응답도 요청자 자신의 알림만이다
		patchEvent(couple.b.accessToken, id, """{"title":"비비가 고침"}""")
			.andExpect(status().isOk)
			.andExpect(content().string(containsString("\"reminder\":null")))
			.andExpect(content().string(not(containsString("MIN_10"))))
	}

	@Test
	fun `FR-CAL-05 AC2 A가 알림을 설정해도 B의 GET events id와 GET events 응답의 알림은 null이다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(extra = ""","isTogether":true"""))
		insertEventReminderRow(id, couple.a.userId, "ALLDAY_9AM", fireAt)

		getEvent(couple.b.accessToken, id).andExpect(content().string(containsString("\"reminder\":null")))
		getEvents(couple.b.accessToken, "2026-11-01", "2026-11-30")
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(content().string(containsString("\"reminder\":null")))
	}

	@Test
	fun `FR-CAL-05 AC2 A와 B가 각자 알림이 있으면 각자 자기 알림만 본다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(extra = ""","isTogether":true"""))
		insertEventReminderRow(id, couple.a.userId, "ALLDAY_9AM", Instant.parse("2026-11-03T00:00:00Z"))
		insertEventReminderRow(id, couple.b.userId, "ALLDAY_PREV_9AM", Instant.parse("2026-11-02T00:00:00Z"))

		getEvent(couple.a.accessToken, id)
			.andExpect(jsonPath("$.reminder.option").value("ALLDAY_9AM"))
			.andExpect(jsonPath("$.reminder.fireAt").value("2026-11-03T00:00:00Z"))
		getEvent(couple.b.accessToken, id)
			.andExpect(jsonPath("$.reminder.option").value("ALLDAY_PREV_9AM"))
			.andExpect(jsonPath("$.reminder.fireAt").value("2026-11-02T00:00:00Z"))
	}

	@Test
	fun `R-07 알림 응답에는 sent_at을 내보내지 않는다`() {
		val guest = startGuest()
		val id = createEvent(guest.accessToken, timedJson())
		insertEventReminderRow(id, guest.userId, "AT_TIME", fireAt, sentAt = fireAt)

		getEvent(guest.accessToken, id)
			.andExpect(jsonPath("$.reminder.option").value("AT_TIME"))
			.andExpect(content().string(not(containsString("sentAt"))))
			.andExpect(content().string(not(containsString("sent_at"))))
	}
}
