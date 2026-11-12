package com.duriseo.server.push

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.push.application.PushPriority
import com.duriseo.server.push.application.PushResult
import com.duriseo.server.support.NotificationTestSupport
import com.duriseo.server.support.TestClockConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 보이는 푸시를 보낼지 정하는 규칙(API.md 7장 ①②, R-16, DB-14), data 메시지 형식(D-136, D-142), 푸시 실패(7장).
 * 발송기는 기록하는 가짜(`RecordingPushSender`)다. 기기 토큰은 `TOKEN_A`(에이), `TOKEN_B`(비비)다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class PushDeliveryTest : NotificationTestSupport() {

	@Test
	fun `R-16 유효 세션이 없으면 푸시는 보내지 않고 알림 행과 NOTIFICATION 신호는 남는다`() {
		val couple = newCouple()
		revokeSessions(couple.b.userId)

		createEvent(couple.a.accessToken, allDayEventJson())

		assertEquals(1, notificationRows(couple.b.userId, "PARTNER_CREATED").size)
		assertEquals(0, push.pushesTo(TOKEN_B).size)
		// 동기화 신호는 세션과 관계없이 간다 (R-16, 7장)
		assertEquals(1, push.syncsTo(TOKEN_B, "EVENT").size)
		val notificationSignals = push.syncsTo(TOKEN_B, "NOTIFICATION")
		assertEquals(1, notificationSignals.size)
		assertEquals(notificationRows(couple.b.userId, "PARTNER_CREATED").single()["id"].toString(), notificationSignals.single().data["id"])
	}

	@Test
	fun `R-16 세션이 만료됐어도 푸시는 보내지 않는다`() {
		val couple = newCouple()
		jdbc.update("update auth_sessions set expires_at = ? where user_id = ?", ts(clock.instant()), couple.b.userId)

		createEvent(couple.a.accessToken, allDayEventJson())

		assertEquals(0, push.pushesTo(TOKEN_B).size)
		assertEquals(1, push.syncsTo(TOKEN_B, "NOTIFICATION").size)
	}

	@Test
	fun `R-16 유효 세션이 있으면 푸시가 간다`() {
		val couple = newCouple()

		createEvent(couple.a.accessToken, allDayEventJson())

		assertEquals(1, push.pushesTo(TOKEN_B).size)
	}

	@Test
	fun `FR-NOTI-02 AC1 종류 설정을 끄면 그 종류 푸시가 가지 않는다`() {
		val couple = newCouple()
		patchSettings(couple.b.accessToken, """{"partnerCreated":false}""").andExpect(status().isOk)

		createEvent(couple.a.accessToken, allDayEventJson())

		// 행과 NOTIFICATION 신호는 푸시를 끈 종류도 남는다 (FR-NOTI-05 AC1, 6장)
		assertEquals(1, notificationRows(couple.b.userId, "PARTNER_CREATED").size)
		assertEquals(0, push.pushesTo(TOKEN_B).size)
		assertEquals(1, push.syncsTo(TOKEN_B, "NOTIFICATION").size)
	}

	@Test
	fun `FR-NOTI-02 AC1 한 종류를 꺼도 다른 종류 푸시는 간다`() {
		val couple = newCouple()
		patchSettings(couple.b.accessToken, """{"partnerCreated":false}""").andExpect(status().isOk)
		val memo = postMemo(couple.a.accessToken, """{"title":"장보기","notifyPartner":false}""")

		pinMemo(couple.a.accessToken, memo).andExpect(status().isNoContent)

		assertEquals(listOf("MEMO_PINNED"), push.pushesTo(TOKEN_B).map { it.data["kind"] })
	}

	@Test
	fun `FR-NOTI-02 AC1 memoPinned를 끄면 메모 고정 푸시가 가지 않는다`() {
		val couple = newCouple()
		patchSettings(couple.b.accessToken, """{"memoPinned":false}""").andExpect(status().isOk)
		val memo = postMemo(couple.a.accessToken, """{"title":"장보기","notifyPartner":false}""")

		pinMemo(couple.a.accessToken, memo).andExpect(status().isNoContent)

		assertEquals(1, notificationRows(couple.b.userId, "MEMO_PINNED").size)
		assertEquals(0, push.pushesTo(TOKEN_B).size)
		assertEquals(1, push.syncsTo(TOKEN_B, "NOTIFICATION").size)
	}

	@Test
	fun `D-85 연결 해제 알림은 설정과 관계없이 푸시한다`() {
		val couple = newCouple()
		patchSettings(
			couple.b.accessToken,
			"""{"eventReminder":false,"partnerCreated":false,"memoPinned":false,"anniversary":false}""",
		).andExpect(status().isOk)

		unlinkCouple(couple.a.accessToken).andExpect(status().isNoContent)

		assertEquals(listOf("COUPLE_UNLINKED"), push.pushesTo(TOKEN_B).map { it.data["kind"] })
	}

	@Test
	fun `D-85 연결 해제 알림도 유효 세션이 없으면 푸시하지 않는다`() {
		val couple = newCouple()
		revokeSessions(couple.b.userId)

		unlinkCouple(couple.a.accessToken).andExpect(status().isNoContent)

		assertEquals(1, notificationRows(couple.b.userId, "COUPLE_UNLINKED").size)
		assertEquals(0, push.pushesTo(TOKEN_B).size)
		assertEquals(1, push.syncsTo(TOKEN_B, "NOTIFICATION").size)
	}

	@Test
	fun `D-136 푸시는 data 메시지이고 notificationId kind title body target을 싣는다`() {
		val couple = newCouple()
		val eventId = createEvent(couple.a.accessToken, allDayEventJson(title = "1박 2일 여행", start = "2026-10-17"))

		val row = notificationRows(couple.b.userId, "PARTNER_CREATED").single()
		val message = push.pushesTo(TOKEN_B).single()
		assertEquals(
			mapOf(
				"type" to "PUSH",
				"notificationId" to row["id"].toString(),
				"kind" to "PARTNER_CREATED",
				"title" to "에이님이 일정을 추가했어요",
				"body" to "10월 17일 (토) · 1박 2일 여행",
				"targetType" to "EVENT",
				"targetId" to eventId.toString(),
			),
			message.data,
		)
		// 알림 행의 title·body와 같은 값을 보낸다 (D-46)
		assertEquals(row["title"], message.data["title"])
		assertEquals(row["body"], message.data["body"])
	}

	@Test
	fun `D-142 우선순위는 푸시 high이고 SYNC와 NOTIFICATION 신호는 normal이다`() {
		val couple = newCouple()

		createEvent(couple.a.accessToken, allDayEventJson())

		assertEquals(PushPriority.HIGH, push.pushesTo(TOKEN_B).single().priority)
		assertEquals(PushPriority.NORMAL, push.syncsTo(TOKEN_B, "EVENT").single().priority)
		assertEquals(PushPriority.NORMAL, push.syncsTo(TOKEN_B, "NOTIFICATION").single().priority)
	}

	@Test
	fun `D-136 대상이 없는 알림은 body와 target 키를 넣지 않는다`() {
		val couple = newCouple()

		unlinkCouple(couple.a.accessToken).andExpect(status().isNoContent)

		val data = push.pushesTo(TOKEN_B).single().data
		assertEquals("SETTINGS", data["targetType"])
		assertNull(data["targetId"])
		assertEquals("COUPLE_UNLINKED", data["kind"])
	}

	@Test
	fun `7장 무효 토큰 응답이면 그 기기 행을 지운다`() {
		val couple = newCouple()
		push.resultFor = { if (it.token == TOKEN_B) PushResult.INVALID_TOKEN else PushResult.SENT }

		createEvent(couple.a.accessToken, allDayEventJson())

		assertEquals(0, count("select count(*) from devices where user_id = ?", couple.b.userId))
		// 에이의 기기는 그대로다. 요청은 성공했고 알림 행도 있다
		assertEquals(1, count("select count(*) from devices where user_id = ?", couple.a.userId))
		assertEquals(1, notificationRows(couple.b.userId, "PARTNER_CREATED").size)
	}

	@Test
	fun `7장 일시 오류는 기기 행을 남기고 재시도하지 않는다`() {
		val couple = newCouple()
		push.resultFor = { PushResult.FAILED }

		createEvent(couple.a.accessToken, allDayEventJson())

		assertEquals(1, count("select count(*) from devices where user_id = ?", couple.b.userId))
		// 푸시 한 번, EVENT 신호 한 번, NOTIFICATION 신호 한 번: 재시도가 없다
		assertEquals(3, push.to(TOKEN_B).size)
	}

	@Test
	fun `7장 발송기가 예외를 던져도 요청은 성공하고 알림 행은 남는다`() {
		val couple = newCouple()
		push.resultFor = { error("fcm down") }

		val id = createEvent(couple.a.accessToken, allDayEventJson())

		assertEquals(1, count("select count(*) from events where id = ?", id))
		assertEquals(1, notificationRows(couple.b.userId, "PARTNER_CREATED").size)
		assertEquals(1, count("select count(*) from devices where user_id = ?", couple.b.userId))
	}

	@Test
	fun `FR-NOTI-04 AC2 푸시는 받는 사람의 모든 기기에 간다`() {
		val couple = newCouple()
		registerDevice(couple.b, "fcm-token-b2-fake")

		createEvent(couple.a.accessToken, allDayEventJson())

		assertEquals(1, push.pushesTo(TOKEN_B).size)
		assertEquals(1, push.pushesTo("fcm-token-b2-fake").size)
		assertTrue(push.pushesTo(TOKEN_A).isEmpty())
	}

	@Test
	fun `R-16 푸시 시각의 세션 확인은 현재 시각 기준이다`() {
		val couple = newCouple()
		// 비비의 세션이 곧 만료된다: 지금은 유효하다
		jdbc.update("update auth_sessions set expires_at = ? where user_id = ?", ts(clock.instant().plus(Duration.ofSeconds(30))), couple.b.userId)

		createEvent(couple.a.accessToken, allDayEventJson())

		assertEquals(1, push.pushesTo(TOKEN_B).size)
	}
}
