package com.duriseo.server.event

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.TestClockConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 상대 일정 추가 알림 행(`PARTNER_CREATED`, FR-NOTI-04 AC2·AC3, API.md 3.3 "상대 일정 추가 알림", 7장, PRD D-92)과
 * 일정 변경 도메인 이벤트(`EventCreated`·`EventUpdated`·`EventDeleted`, API.md 6장 연결점). 푸시와 동기화 신호 발송은 단위 6이다.
 * 2026-10-26은 월요일이고 새 사용자의 시간대는 `Asia/Seoul`(UTC+9)이다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class EventPartnerNotificationTest : EventTestSupport() {

	private fun partnerCreatedRows(userId: UUID): List<Map<String, Any?>> =
		jdbc.queryForList("select * from notifications where user_id = ? and type = 'PARTNER_CREATED' order by id", userId)

	private fun partnerCreatedCount(): Int = count("select count(*) from notifications where type = 'PARTNER_CREATED'")

	// ── FR-NOTI-04 AC2 ───────────────────────────────────

	@Test
	fun `FR-NOTI-04 AC2 커플 공간에서 notifyPartner를 생략하면 true로 보고 상대에게만 PARTNER_CREATED 알림 행이 생긴다`() {
		val couple = newCouple()

		val id = createEvent(couple.a.accessToken, allDayJson(title = "회식", start = "2026-11-03"))

		val row = partnerCreatedRows(couple.b.userId).single()
		assertEquals("에이님이 일정을 추가했어요", row["title"])
		assertEquals("11월 3일 (화) · 회식", row["body"])
		assertEquals("EVENT", row["target_type"])
		assertEquals(id, row["target_id"])
		assertEquals(couple.spaceId, row["space_id"])
		assertNull(row["read_at"])
		// 작성자 본인에게는 만들지 않는다
		assertEquals(0, partnerCreatedRows(couple.a.userId).size)
		assertEquals(1, partnerCreatedCount())
	}

	@Test
	fun `FR-NOTI-04 AC2 notifyPartner true를 명시해도 같고 상대가 만들면 에이가 받는다`() {
		val couple = newCouple()

		createEvent(couple.b.accessToken, allDayJson(title = "데이트", start = "2026-11-03", extra = ""","notifyPartner":true"""))

		val row = partnerCreatedRows(couple.a.userId).single()
		assertEquals("비비님이 일정을 추가했어요", row["title"])
		assertEquals("11월 3일 (화) · 데이트", row["body"])
		assertEquals(0, partnerCreatedRows(couple.b.userId).size)
	}

	@Test
	fun `FR-NOTI-04 AC2 notifyPartner false로 만들면 알림 행이 생기지 않는다`() {
		val couple = newCouple()

		createEvent(couple.a.accessToken, allDayJson(extra = ""","notifyPartner":false"""))

		assertEquals(0, partnerCreatedCount())
		assertEquals(1, eventCount())
	}

	@Test
	fun `FR-NOTI-04 AC2 상대의 partner_created 설정이 꺼져 있어도 알림 행은 생긴다`() {
		val couple = newCouple()
		jdbc.update("update notification_settings set partner_created = false where user_id = ?", couple.b.userId)

		createEvent(couple.a.accessToken, allDayJson())

		// 설정은 푸시만 막는다 (API.md 7장 머리말, 푸시는 단위 6)
		assertEquals(1, partnerCreatedRows(couple.b.userId).size)
	}

	@Test
	fun `FR-NOTI-04 AC2 날짜 서식은 연도와 앞자리 0이 없는 M월 d일 (요일)이고 요일은 한글 한 글자다`() {
		val couple = newCouple()
		// 2026-11-02 월요일부터 일요일까지, 그리고 두 자리 월과 일
		val dates = listOf(
			"2026-11-02" to "11월 2일 (월)",
			"2026-11-03" to "11월 3일 (화)",
			"2026-11-04" to "11월 4일 (수)",
			"2026-11-05" to "11월 5일 (목)",
			"2026-11-06" to "11월 6일 (금)",
			"2026-11-07" to "11월 7일 (토)",
			"2026-11-08" to "11월 8일 (일)",
			"2026-12-15" to "12월 15일 (화)",
			"2027-01-05" to "1월 5일 (화)",
		)
		dates.forEach { (date, _) -> createEvent(couple.a.accessToken, allDayJson(title = "일정", start = date)) }

		val bodies = partnerCreatedRows(couple.b.userId).map { it["body"] }
		assertEquals(dates.map { "${it.second} · 일정" }.toSet(), bodies.toSet())
		assertEquals(dates.size, bodies.size)
	}

	@Test
	fun `FR-NOTI-04 AC2 여러 날 일정은 시작 날짜만 쓴다`() {
		val couple = newCouple()

		createEvent(couple.a.accessToken, allDayJson(title = "1박 2일 여행", start = "2026-12-05", end = "2026-12-06"))

		assertEquals("12월 5일 (토) · 1박 2일 여행", partnerCreatedRows(couple.b.userId).single()["body"])
	}

	@Test
	fun `FR-NOTI-04 AC2 시간 일정의 날짜는 작성자가 아니라 받는 사람의 시간대로 정한다`() {
		val couple = newCouple()
		// 시작 2026-11-03T16:00Z: 서울(작성자)은 11월 4일 01:00, 로스앤젤레스(받는 사람)는 11월 3일 08:00
		setTimeZone(couple.b, "America/Los_Angeles")

		createEvent(couple.a.accessToken, timedJson(title = "미팅", start = "2026-11-03T16:00:00Z", end = "2026-11-03T17:00:00Z"))

		assertEquals("11월 3일 (화) · 미팅", partnerCreatedRows(couple.b.userId).single()["body"])
	}

	@Test
	fun `FR-NOTI-04 AC2 시간 일정의 날짜가 받는 사람의 시간대에서 다음 날이면 다음 날로 쓴다`() {
		val couple = newCouple()
		// 시작 2026-11-03T16:00Z: 받는 사람이 서울이면 11월 4일 01:00
		createEvent(couple.a.accessToken, timedJson(title = "미팅", start = "2026-11-03T16:00:00Z", end = "2026-11-03T17:00:00Z"))
		setTimeZone(couple.a, "America/Los_Angeles")
		createEvent(couple.a.accessToken, timedJson(title = "저녁", start = "2026-11-03T16:00:00Z", end = "2026-11-03T17:00:00Z"))

		val bodies = partnerCreatedRows(couple.b.userId).map { it["body"] }.toSet()
		assertEquals(setOf("11월 4일 (수) · 미팅", "11월 4일 (수) · 저녁"), bodies)
	}

	@Test
	fun `FR-NOTI-04 AC2 알림 본문의 제목은 앞뒤 공백을 지운 저장된 제목이다`() {
		val couple = newCouple()

		createEvent(couple.a.accessToken, allDayJson(title = "  회식  ", start = "2026-11-03"))

		assertEquals("11월 3일 (화) · 회식", partnerCreatedRows(couple.b.userId).single()["body"])
	}

	@Test
	fun `FR-NOTI-04 AC2 일정을 만들 때마다 알림 행이 하나씩 생긴다`() {
		val couple = newCouple()

		val first = createEvent(couple.a.accessToken, allDayJson(title = "하나"))
		val second = createEvent(couple.a.accessToken, allDayJson(title = "둘"))

		val targets = partnerCreatedRows(couple.b.userId).map { it["target_id"] }.toSet()
		assertEquals(setOf<Any?>(first, second), targets)
	}

	@Test
	fun `FR-NOTI-04 AC2 알림 행을 만들지 못하면 일정도 만들어지지 않는다`() {
		val couple = newCouple()

		withFailingTrigger("notifications", "insert") {
			postEventRaw(couple.a.accessToken, allDayJson()).expectError(500, "INTERNAL_ERROR")
		}

		assertEquals(0, eventCount())
		assertEquals(0, partnerCreatedCount())
		assertTrue(eventChanges.created.isEmpty())
	}

	// ── FR-NOTI-04 AC3 ───────────────────────────────────

	@Test
	fun `FR-NOTI-04 AC3 개인 공간에서 notifyPartner true로 만들어도 알림 행이 생기지 않는다`() {
		val guest = startGuest()

		postEventRaw(guest.accessToken, allDayJson(extra = ""","notifyPartner":true""")).andExpect(status().isCreated)

		assertEquals(0, count("select count(*) from notifications"))
	}

	@Test
	fun `FR-NOTI-04 AC3 PATCH와 DELETE events는 알림 행을 만들지 않는다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(extra = ""","isTogether":true"""))
		val before = count("select count(*) from notifications")

		patchEvent(couple.b.accessToken, id, """{"title":"새 제목","start":"2026-11-10","end":"2026-11-10"}""").andExpect(status().isOk)
		patchEvent(couple.a.accessToken, id, """{"note":"메모"}""").andExpect(status().isOk)
		deleteEvent(couple.b.accessToken, id).andExpect(status().isNoContent)

		assertEquals(before, count("select count(*) from notifications"))
		// 일정을 지워도 알림 행은 남는다 (D-43)
		assertEquals(1, partnerCreatedCount())
	}

	@Test
	fun `FR-NOTI-04 AC3 거부된 POST는 알림 행도 일정도 만들지 않는다`() {
		val couple = newCouple()
		val guest = startGuest()

		postEventRaw(couple.a.accessToken, """{"title":"","isAllDay":true,"start":"2026-11-03","end":"2026-11-03"}""")
			.expectError(400, "VALIDATION_FAILED")
		postEventRaw(couple.a.accessToken, allDayJson(start = "2051-01-01")).expectError(400, "DATE_OUT_OF_RANGE")
		postEventRaw(guest.accessToken, allDayJson(extra = ""","isTogether":true""")).expectError(400, "INVALID_TARGET")

		assertEquals(0, eventCount())
		assertEquals(0, count("select count(*) from notifications where type = 'PARTNER_CREATED'"))
	}

	@Test
	fun `D-45 PARTNER_CREATED 행의 space_id는 커플 공간이라 연결을 해제하면 지워진다`() {
		val couple = newCouple()
		createEvent(couple.a.accessToken, allDayJson())
		assertEquals(1, count("select count(*) from notifications where type = 'PARTNER_CREATED' and space_id = ?", couple.spaceId))

		unlinkCouple(couple.a.accessToken).andExpect(status().isNoContent)

		assertEquals(0, partnerCreatedCount())
	}

	@Test
	fun `FR-AUTH-06 AC1 상대가 탈퇴하면 그 사람이 받은 PARTNER_CREATED 알림도 지워진다`() {
		val couple = newCouple()
		createEvent(couple.a.accessToken, allDayJson())
		assertEquals(1, partnerCreatedCount())

		deleteMe(couple.b.accessToken).andExpect(status().isNoContent)

		assertEquals(0, count("select count(*) from notifications where user_id = ?", couple.b.userId))
	}

	@Test
	fun `FR-AUTH-06 AC1 일정을 만드는 요청과 상대의 탈퇴가 동시에 와도 교착 없이 끝난다`() {
		repeat(3) {
			val couple = newCouple()

			val results = inParallel(
				listOf(
					{ statusOf(postEventRaw(couple.a.accessToken, allDayJson(title = "동시 1"))) },
					{ statusOf(postEventRaw(couple.a.accessToken, allDayJson(title = "동시 2"))) },
					{ statusOf(deleteMe(couple.b.accessToken)) },
					{ statusOf(postEventRaw(couple.a.accessToken, allDayJson(title = "동시 3"))) },
				),
			)

			assertEquals(listOf(201, 201, 204, 201), results)
			// 탈퇴한 사람에게 남은 알림은 없다
			assertEquals(0, count("select count(*) from notifications where user_id = ? and type = 'PARTNER_CREATED'", couple.b.userId))
		}
	}

	@Test
	fun `D-45 일정을 만드는 요청과 연결 해제가 동시에 와도 해제된 공간에 PARTNER_CREATED 행이 남지 않는다`() {
		repeat(5) {
			val couple = newCouple()

			val results = inParallel(
				listOf(
					{ statusOf(postEventRaw(couple.a.accessToken, allDayJson(title = "동시 A"))) },
					{ statusOf(unlinkCouple(couple.b.accessToken)) },
					{ statusOf(postEventRaw(couple.a.accessToken, allDayJson(title = "동시 B"))) },
				),
			)

			assertEquals(listOf(201, 204, 201), results)
			// 해제 뒤에 해제된 공간으로 들어간 알림 행이 없다 (해제 앞이면 해제가 지웠고, 뒤면 개인 공간으로 갔다)
			assertEquals(0, count("select count(*) from notifications where space_id = ?", couple.spaceId))
			assertEquals(0, count("select count(*) from notifications where type = 'PARTNER_CREATED' and user_id = ?", couple.b.userId))
			// 두 일정 모두 만들어졌고, 해제 앞이면 커플 공간에, 뒤면 A의 현재(개인) 공간에 있다
			val currentSpace = activeSpaceIdOfUser(couple.a.userId)
			assertTrue(currentSpace != couple.spaceId)
			assertEquals(
				2,
				count("select count(*) from events where author_id = ? and space_id in (?, ?)", couple.a.userId, couple.spaceId, currentSpace),
			)
		}
	}

	// ── 도메인 이벤트: API.md 6장 ────────────────────────

	@Test
	fun `NFR-SYNC-01 일정 생성 이벤트는 커밋된 뒤에만 리스너에 전달된다`() {
		val couple = newCouple()
		// 전달되는 순간 다른 연결에서 커밋된 일정과 알림 행이 보여야 한다
		val eventsSeen = AtomicInteger(-1)
		val notificationsSeen = AtomicInteger(-1)
		eventChanges.onCreated = { created ->
			eventsSeen.set(committedCountOf("select count(*) from events where id = ?", created.eventId))
			notificationsSeen.set(committedCountOf("select count(*) from notifications where type = 'PARTNER_CREATED'"))
		}

		val id = createEvent(couple.a.accessToken, allDayJson())

		val created = eventChanges.created.single()
		assertEquals(id, created.eventId)
		assertEquals(couple.spaceId, created.spaceId)
		assertEquals(couple.a.userId, created.createdByUserId)
		assertEquals(couple.b.userId, created.partnerUserId)
		assertEquals(partnerCreatedRows(couple.b.userId).single()["id"], created.notificationId)
		assertEquals(1, eventsSeen.get())
		assertEquals(1, notificationsSeen.get())
	}

	@Test
	fun `NFR-SYNC-01 알림 행이 없으면 생성 이벤트의 상대와 알림 ID는 null이다`() {
		val couple = newCouple()
		val guest = startGuest()

		createEvent(couple.a.accessToken, allDayJson(extra = ""","notifyPartner":false"""))
		createEvent(guest.accessToken, allDayJson())

		assertEquals(2, eventChanges.created.size)
		eventChanges.created.forEach {
			assertNull(it.partnerUserId)
			assertNull(it.notificationId)
		}
	}

	@Test
	fun `NFR-SYNC-01 일정 수정과 삭제 이벤트는 커밋된 뒤 수정한 사람과 함께 전달된다`() {
		val couple = newCouple()
		val id = createEvent(couple.a.accessToken, allDayJson(extra = ""","isTogether":true"""))
		eventChanges.reset()

		patchEvent(couple.b.accessToken, id, """{"title":"수정"}""").andExpect(status().isOk)
		deleteEvent(couple.a.accessToken, id).andExpect(status().isNoContent)

		val updated = eventChanges.updated.single()
		assertEquals(id, updated.eventId)
		assertEquals(couple.spaceId, updated.spaceId)
		assertEquals(couple.b.userId, updated.changedByUserId)
		val deleted = eventChanges.deleted.single()
		assertEquals(id, deleted.eventId)
		assertEquals(couple.spaceId, deleted.spaceId)
		assertEquals(couple.a.userId, deleted.deletedByUserId)
		assertEquals(0, eventChanges.created.size)
	}

	@Test
	fun `NFR-SYNC-01 거부된 요청은 이벤트를 발행하지 않는다`() {
		val couple = newCouple()
		val other = newCouple()
		val mine = createEvent(couple.a.accessToken, allDayJson())
		val foreign = createEvent(other.a.accessToken, allDayJson())
		eventChanges.reset()

		patchEvent(couple.b.accessToken, mine, """{"title":"남의 것"}""").expectError(403, "NOT_AUTHOR")
		deleteEvent(couple.b.accessToken, mine).expectError(403, "NOT_AUTHOR")
		patchEvent(couple.a.accessToken, foreign, """{"title":"다른 공간"}""").expectError(404, "NOT_FOUND")
		deleteEvent(couple.a.accessToken, foreign).expectError(404, "NOT_FOUND")
		patchEvent(couple.a.accessToken, mine, """{"title":""}""").expectError(400, "VALIDATION_FAILED")
		postEventRaw(couple.a.accessToken, """{"title":""}""").expectError(400, "VALIDATION_FAILED")

		assertEquals(0, eventChanges.created.size + eventChanges.updated.size + eventChanges.deleted.size)
	}
}
