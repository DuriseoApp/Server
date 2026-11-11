package com.duriseo.server.notification

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.event.application.EventReminderTexts
import com.duriseo.server.job.EventReminderJob
import com.duriseo.server.push.application.PushPriority
import com.duriseo.server.support.NotificationTestSupport
import com.duriseo.server.support.TestClockConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 일정 알림 작업 (API.md 7장 "일정 알림", FR-CAL-05, D-90·D-137·D-139). 스케줄러는 꺼져 있고(`duriseo.jobs.enabled=false`) 시계를 옮기며
 * 작업 메서드를 직접 부른다. 시계는 `2026-10-26T00:00:00Z`로 시작한다. 새 사용자의 시간대는 `Asia/Seoul`(UTC+9)이다.
 * 시계를 1시간 넘게 옮기면 access 토큰이 만료되므로, 옮긴 뒤에는 HTTP 요청 없이 작업과 DB만 본다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class EventReminderJobTest : NotificationTestSupport() {

	@Autowired
	private lateinit var job: EventReminderJob

	private fun timedEventJson(start: String, end: String, title: String = "치과", extra: String = "") =
		"""{"title":"$title","isAllDay":false,"start":"$start","end":"$end"$extra}"""

	private fun at(iso: String) = Instant.parse(iso)

	private fun reminderRows(userId: UUID) = notificationRows(userId, "EVENT_REMINDER")

	private fun sentAtOfReminder(eventId: UUID, userId: UUID): Instant? =
		jdbc.queryForObject("select sent_at from event_reminders where event_id = ? and user_id = ?", Timestamp::class.java, eventId, userId)
			?.toInstant()

	/** 함께 일정 한 건(시간 일정). 시작은 UTC [start] */
	private fun timedEvent(couple: Couple, start: Instant, title: String = "치과"): UUID =
		insertEventRow(couple.spaceId, couple.a.userId, title = title, isTogether = true, isAllDay = false, startAt = start, endAt = start.plusSeconds(3600))

	@Test
	fun `FR-CAL-05 알림 시각이 되면 EVENT_REMINDER 행을 만들고 sent_at을 기록하고 푸시한다`() {
		val couple = newCouple()
		val eventId = createEvent(couple.a.accessToken, timedEventJson("2026-10-26T10:00:00Z", "2026-10-26T11:00:00Z"))
		putReminder(couple.a.accessToken, eventId, "MIN_10")
		push.reset()

		// 알림 시각(09:50Z) 전에는 아무것도 보내지 않는다
		clock.set(at("2026-10-26T09:49:00Z"))
		assertEquals(0, job.run())
		assertEquals(0, reminderRows(couple.a.userId).size)
		assertNull(sentAtOfReminder(eventId, couple.a.userId))

		clock.set(at("2026-10-26T09:50:30Z"))
		assertEquals(1, job.run())

		val row = reminderRows(couple.a.userId).single()
		assertEquals("치과", row["title"])
		assertEquals("10분 뒤 · 오후 7:00", row["body"])
		assertEquals(couple.spaceId, row["space_id"])
		assertEquals("EVENT", row["target_type"])
		assertEquals(eventId, row["target_id"])
		assertEquals(at("2026-10-26T09:50:30Z"), sentAtOfReminder(eventId, couple.a.userId))
		// 받는 사람은 알림 주인뿐이다
		assertEquals(0, reminderRows(couple.b.userId).size)
		assertEquals(0, push.to(TOKEN_B).size)

		val pushes = push.pushesTo(TOKEN_A)
		assertEquals(1, pushes.size)
		assertEquals("EVENT_REMINDER", pushes.single().data["kind"])
		assertEquals("치과", pushes.single().data["title"])
		assertEquals("10분 뒤 · 오후 7:00", pushes.single().data["body"])
		assertEquals(PushPriority.HIGH, pushes.single().priority)
		assertEquals(row["id"].toString(), pushes.single().data["notificationId"])
		assertEquals(1, push.syncsTo(TOKEN_A, "NOTIFICATION").size)

		// 다시 돌려도 더 만들지 않는다
		assertEquals(0, job.run())
		assertEquals(1, reminderRows(couple.a.userId).size)
		assertEquals(1, push.pushesTo(TOKEN_A).size)
	}

	@Test
	fun `FR-CAL-05 푸시 설정을 끄거나 유효 세션이 없으면 행과 NOTIFICATION 신호만 남는다`() {
		val couple = newCouple()
		val eventId = timedEvent(couple, at("2026-10-26T10:00:00Z"))
		insertEventReminderRow(eventId, couple.a.userId, "AT_TIME", at("2026-10-26T10:00:00Z"))
		insertEventReminderRow(eventId, couple.b.userId, "AT_TIME", at("2026-10-26T10:00:00Z"))
		jdbc.update("insert into notification_settings (user_id, event_reminder, updated_at) values (?, false, ?) on conflict (user_id) do update set event_reminder = false", couple.a.userId, ts(clock.instant()))
		revokeSessions(couple.b.userId)

		clock.set(at("2026-10-26T10:00:00Z"))
		assertEquals(2, job.run())

		assertEquals(1, reminderRows(couple.a.userId).size)
		assertEquals(1, reminderRows(couple.b.userId).size)
		assertEquals(0, push.pushesTo(TOKEN_A).size)
		assertEquals(0, push.pushesTo(TOKEN_B).size)
		assertEquals(1, push.syncsTo(TOKEN_A, "NOTIFICATION").size)
		assertEquals(1, push.syncsTo(TOKEN_B, "NOTIFICATION").size)
	}

	@Test
	fun `FR-CAL-05 AC7 10분 넘게 늦은 알림은 행만 만들고 푸시하지 않는다`() {
		val couple = newCouple()
		val eventId = timedEvent(couple, at("2026-10-26T10:00:00Z"))
		val now = at("2026-10-26T10:30:00Z")
		clock.set(now)
		// 10분 11초 전에 보냈어야 한다
		insertEventReminderRow(eventId, couple.a.userId, "AT_TIME", now.minus(Duration.ofMinutes(10)).minusSeconds(1))

		assertEquals(1, job.run())

		val row = reminderRows(couple.a.userId).single()
		assertEquals("EVENT", row["target_type"])
		assertNotNull(sentAtOfReminder(eventId, couple.a.userId))
		assertEquals(0, push.pushesTo(TOKEN_A).size)
		// 알림 행이 생겼다는 신호는 간다 (7장 머리말)
		assertEquals(1, push.syncsTo(TOKEN_A, "NOTIFICATION").size)
	}

	@Test
	fun `FR-CAL-05 AC7 정확히 10분 늦은 알림은 아직 푸시한다`() {
		val couple = newCouple()
		val eventId = timedEvent(couple, at("2026-10-26T10:00:00Z"))
		val now = at("2026-10-26T10:10:00Z")
		clock.set(now)
		insertEventReminderRow(eventId, couple.a.userId, "AT_TIME", now.minus(Duration.ofMinutes(10)))

		assertEquals(1, job.run())

		assertEquals(1, push.pushesTo(TOKEN_A).size)
	}

	@Test
	fun `FR-CAL-05 이미 sent_at이 있는 알림과 아직 시각이 안 된 알림은 보내지 않는다`() {
		val couple = newCouple()
		val eventId = timedEvent(couple, at("2026-10-26T10:00:00Z"))
		insertEventReminderRow(eventId, couple.a.userId, "AT_TIME", at("2026-10-26T09:00:00Z"), sentAt = at("2026-10-26T09:00:00Z"))
		insertEventReminderRow(eventId, couple.b.userId, "AT_TIME", at("2026-10-26T12:00:00Z"))

		clock.set(at("2026-10-26T09:30:00Z"))
		assertEquals(0, job.run())

		assertEquals(0, count("select count(*) from notifications"))
	}

	@Test
	fun `D-139 일정 알림 본문은 알림 주인 시간대의 시작 시각이다`() {
		val couple = newCouple()
		// 두 사람의 시간대가 다르면 같은 일정도 본문의 시각이 다르다. 2026-11-03T10:30:00Z = 서울 19:30 = 뉴욕 05:30(서머타임 끝남)
		jdbc.update("update users set time_zone = 'America/New_York' where id = ?", couple.b.userId)
		val timed = timedEvent(couple, at("2026-11-03T10:30:00Z"))
		val allDay = insertEventRow(couple.spaceId, couple.a.userId, isTogether = true, isAllDay = true, startDate = "2026-11-03", endDate = "2026-11-03")
		val due = at("2026-11-03T00:00:00Z")
		clock.set(due.plusSeconds(1))
		val expectedTimed = mapOf(
			"AT_TIME" to ("지금 · 오후 7:30" to "지금 · 오전 5:30"),
			"MIN_10" to ("10분 뒤 · 오후 7:30" to "10분 뒤 · 오전 5:30"),
			"HOUR_1" to ("1시간 뒤 · 오후 7:30" to "1시간 뒤 · 오전 5:30"),
			"DAY_1" to ("내일 · 오후 7:30" to "내일 · 오전 5:30"),
		)
		val expectedAllDay = mapOf(
			"ALLDAY_9AM" to "오늘 · 하루 종일",
			"ALLDAY_PREV_9AM" to "내일 · 하루 종일",
		)
		for (option in expectedTimed.keys) {
			jdbc.update("delete from notifications")
			jdbc.update("delete from event_reminders")
			insertEventReminderRow(timed, couple.a.userId, option, due)
			insertEventReminderRow(timed, couple.b.userId, option, due)
			assertEquals(2, job.run(), option)
			assertEquals(expectedTimed.getValue(option).first, reminderRows(couple.a.userId).single()["body"], "A $option")
			assertEquals(expectedTimed.getValue(option).second, reminderRows(couple.b.userId).single()["body"], "B $option")
		}
		for ((option, body) in expectedAllDay) {
			jdbc.update("delete from notifications")
			jdbc.update("delete from event_reminders")
			insertEventReminderRow(allDay, couple.a.userId, option, due)
			insertEventReminderRow(allDay, couple.b.userId, option, due)
			assertEquals(2, job.run(), option)
			assertEquals(body, reminderRows(couple.a.userId).single()["body"], "A $option")
			assertEquals(body, reminderRows(couple.b.userId).single()["body"], "B $option")
		}
	}

	@Test
	fun `D-139 시작 시각은 오전 오후 12시간제이고 0시와 12시는 12다`() {
		val seoul = ZoneId.of("Asia/Seoul")
		assertEquals("오전 12:00", EventReminderTexts.startTime(at("2026-10-26T15:00:00Z"), seoul))
		assertEquals("오전 9:05", EventReminderTexts.startTime(at("2026-10-26T00:05:00Z"), seoul))
		assertEquals("오후 12:30", EventReminderTexts.startTime(at("2026-10-26T03:30:00Z"), seoul))
		assertEquals("오후 11:59", EventReminderTexts.startTime(at("2026-10-26T14:59:00Z"), seoul))
	}

	@Test
	fun `FR-CAL-05 같은 알림이 두 번 실행돼도 행과 푸시는 하나다`() {
		val couple = newCouple()
		val eventId = timedEvent(couple, at("2026-10-26T10:00:00Z"))
		insertEventReminderRow(eventId, couple.a.userId, "AT_TIME", at("2026-10-26T10:00:00Z"))
		clock.set(at("2026-10-26T10:00:30Z"))

		val processed = inParallel(List(4) { { job.run() } })

		assertEquals(1, processed.sum())
		assertEquals(1, committedCountOf("select count(*) from notifications where user_id = ? and type = 'EVENT_REMINDER'", couple.a.userId))
		assertEquals(1, push.pushesTo(TOKEN_A).size)
		assertEquals(1, push.syncsTo(TOKEN_A, "NOTIFICATION").size)
	}

	@Test
	fun `FR-CAL-05 다시 계산과 겹쳐도 옛 시각으로 보내지 않는다`() {
		val couple = newCouple()
		val eventId = createEvent(couple.a.accessToken, timedEventJson("2026-10-26T10:00:00Z", "2026-10-26T11:00:00Z"))
		putReminder(couple.a.accessToken, eventId, "MIN_10")
		// 시작을 한 시간 늦춘다: fire_at이 10:50Z로 다시 계산된다 (D-91)
		patchEvent(couple.a.accessToken, eventId, """{"start":"2026-10-26T11:00:00Z","end":"2026-10-26T12:00:00Z"}""")
		push.reset()

		// 옛 시각(09:50Z)이 지나도 보내지 않는다
		clock.set(at("2026-10-26T09:55:00Z"))
		assertEquals(0, job.run())
		assertEquals(0, count("select count(*) from notifications where type = 'EVENT_REMINDER'"))

		clock.set(at("2026-10-26T10:50:00Z"))
		assertEquals(1, job.run())
		assertEquals("10분 뒤 · 오후 8:00", reminderRows(couple.a.userId).single()["body"])
	}

	@Test
	fun `FR-CAL-05 다시 계산이 알림 행을 잡고 있으면 건너뛰고 새 시각이 미래면 보내지 않는다`() {
		val couple = newCouple()
		val eventId = timedEvent(couple, at("2026-10-26T10:00:00Z"))
		insertEventReminderRow(eventId, couple.a.userId, "AT_TIME", at("2026-10-26T10:00:00Z"))
		clock.set(at("2026-10-26T10:00:30Z"))

		jdbc.dataSource!!.connection.use { recalculating ->
			recalculating.autoCommit = false
			// PATCH /events의 다시 계산이 하는 일: 행을 잠그고 fire_at을 미래로, sent_at을 비운다 (아직 커밋 전)
			recalculating.prepareStatement("select id from event_reminders where event_id = ? and user_id = ? for update").use {
				it.setObject(1, eventId)
				it.setObject(2, couple.a.userId)
				it.executeQuery().close()
			}
			recalculating.prepareStatement("update event_reminders set fire_at = ?, sent_at = null where event_id = ? and user_id = ?").use {
				it.setTimestamp(1, ts(at("2026-10-26T12:00:00Z")))
				it.setObject(2, eventId)
				it.setObject(3, couple.a.userId)
				it.executeUpdate()
			}

			// 잠긴 행은 기다리지 않고 건너뛴다 (SKIP LOCKED)
			assertEquals(0, job.run())
			recalculating.commit()
		}

		assertEquals(0, job.run())
		assertEquals(0, count("select count(*) from notifications"))
		assertEquals(0, push.to(TOKEN_A).size)
		assertNull(sentAtOfReminder(eventId, couple.a.userId))

		clock.set(at("2026-10-26T12:00:00Z"))
		assertEquals(1, job.run())
	}

	@Test
	fun `FR-CAL-05 한 알림이 실패해도 다음 알림은 보낸다`() {
		val couple = newCouple()
		val eventId = timedEvent(couple, at("2026-10-26T10:00:00Z"))
		insertEventReminderRow(eventId, couple.a.userId, "AT_TIME", at("2026-10-26T10:00:00Z"))
		insertEventReminderRow(eventId, couple.b.userId, "AT_TIME", at("2026-10-26T10:00:01Z"))
		clock.set(at("2026-10-26T10:01:00Z"))
		// A의 알림 행을 만들 수 없게 한다 (title이 100자를 넘기는 일정 제목은 API가 막지만, 여기서는 DB 제약으로 강제로 실패시킨다)
		jdbc.execute("alter table notifications add constraint ck_test_block_a check (user_id <> '${couple.a.userId}') not valid")
		try {
			assertEquals(1, job.run())
		} finally {
			jdbc.execute("alter table notifications drop constraint ck_test_block_a")
		}

		assertEquals(0, reminderRows(couple.a.userId).size)
		assertNull(sentAtOfReminder(eventId, couple.a.userId))
		assertEquals(1, reminderRows(couple.b.userId).size)
	}
}
