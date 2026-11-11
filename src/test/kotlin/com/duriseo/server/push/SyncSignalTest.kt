package com.duriseo.server.push

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.NotificationTestSupport
import com.duriseo.server.support.TestClockConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 동기화 신호(SYNC)와 도메인 이벤트별 푸시 (API.md 6장·7장, NFR-SYNC-01, D-123, D-134, D-135).
 * 신호는 커밋 뒤 공간의 **다른** 구성원 기기에만 간다. 기기 토큰은 `TOKEN_A`(에이), `TOKEN_B`(비비)다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class SyncSignalTest : NotificationTestSupport() {

	private fun syncData(resource: String, id: Any?, action: String): Map<String, String> =
		buildMap {
			put("type", "SYNC")
			put("resource", resource)
			if (id != null) put("id", id.toString())
			put("action", action)
		}

	// ── EVENT ───────────────────────────────────────────

	@Test
	fun `FR-NOTI-04 AC2 일정을 notifyPartner로 만들면 상대에게 EVENT 신호 PARTNER_CREATED 푸시 NOTIFICATION 신호가 간다`() {
		val couple = newCouple()

		val eventId = createEvent(couple.a.accessToken, allDayEventJson())

		val row = notificationRows(couple.b.userId, "PARTNER_CREATED").single()
		assertEquals(listOf(syncData("EVENT", eventId, "CREATED")), push.syncsTo(TOKEN_B, "EVENT").map { it.data })
		assertEquals(listOf(syncData("NOTIFICATION", row["id"], "CREATED")), push.syncsTo(TOKEN_B, "NOTIFICATION").map { it.data })
		assertEquals(listOf("PARTNER_CREATED"), push.pushesTo(TOKEN_B).map { it.data["kind"] })
		// 만든 사람 본인 기기에는 아무것도 가지 않는다
		assertTrue(push.to(TOKEN_A).isEmpty())
	}

	@Test
	fun `FR-NOTI-04 AC2 notifyPartner를 끄고 만들면 EVENT 신호만 가고 푸시와 NOTIFICATION 신호는 없다`() {
		val couple = newCouple()

		val eventId = createEvent(couple.a.accessToken, allDayEventJson(extra = ""","notifyPartner":false"""))

		assertEquals(listOf(syncData("EVENT", eventId, "CREATED")), push.to(TOKEN_B).map { it.data })
	}

	@Test
	fun `6장 일정을 고치고 지우면 상대에게 EVENT UPDATED와 DELETED 신호가 간다`() {
		val couple = newCouple()
		val eventId = createEvent(couple.a.accessToken, allDayEventJson(extra = ""","isTogether":true,"notifyPartner":false"""))
		push.reset()

		patchEvent(couple.a.accessToken, eventId, """{"title":"바뀜"}""").andExpect(status().isOk)
		deleteEvent(couple.b.accessToken, eventId).andExpect(status().isNoContent)

		assertEquals(listOf(syncData("EVENT", eventId, "UPDATED")), push.to(TOKEN_B).map { it.data })
		assertEquals(listOf(syncData("EVENT", eventId, "DELETED")), push.to(TOKEN_A).map { it.data })
	}

	@Test
	fun `6장 개인 공간의 쓰기는 신호를 보내지 않는다`() {
		val guest = startGuest()
		registerDevice(guest, TOKEN_A)
		push.reset()

		createEvent(guest.accessToken, allDayEventJson())

		assertTrue(push.sent.isEmpty())
	}

	// ── ANNIVERSARY ─────────────────────────────────────

	@Test
	fun `6장 직접 기념일을 바꾸면 상대에게 ANNIVERSARY 신호가 간다`() {
		val couple = newCouple()

		val id = createAnniversary(couple.a.accessToken, """{"name":"여행","date":"2026-11-03"}""")
		patchAnniversary(couple.a.accessToken, id, """{"name":"가는 여행"}""").andExpect(status().isOk)
		deleteAnniversary(couple.a.accessToken, id).andExpect(status().isNoContent)

		assertEquals(
			listOf(
				syncData("ANNIVERSARY", id, "CREATED"),
				syncData("ANNIVERSARY", id, "UPDATED"),
				syncData("ANNIVERSARY", id, "DELETED"),
			),
			push.to(TOKEN_B).map { it.data },
		)
		assertTrue(push.to(TOKEN_A).isEmpty())
	}

	@Test
	fun `D-123 같은 값 PATCH는 신호가 없다`() {
		val couple = newCouple()
		val anniversary = createAnniversary(couple.a.accessToken, """{"name":"여행","date":"2026-11-03"}""")
		val memo = postMemo(couple.a.accessToken, """{"title":"장보기","notifyPartner":false}""")
		push.reset()

		patchAnniversary(couple.a.accessToken, anniversary, """{"name":"여행","date":"2026-11-03"}""").andExpect(status().isOk)
		patchMemoJson(couple.a.accessToken, memo, """{"title":"장보기"}""").andExpect(status().isOk)

		assertTrue(push.sent.isEmpty())
	}

	// ── MEMO ────────────────────────────────────────────

	@Test
	fun `D-135 메모를 고정하면 상대에게 MEMO 신호 MEMO_PINNED 푸시 NOTIFICATION 신호가 간다`() {
		val couple = newCouple()
		val memo = postMemo(couple.a.accessToken, """{"title":"장보기","notifyPartner":false}""")
		push.reset()

		pinMemo(couple.a.accessToken, memo).andExpect(status().isNoContent)

		val row = notificationRows(couple.b.userId, "MEMO_PINNED").single()
		assertEquals(listOf(syncData("MEMO", memo, "UPDATED")), push.syncsTo(TOKEN_B, "MEMO").map { it.data })
		assertEquals(listOf(syncData("NOTIFICATION", row["id"], "CREATED")), push.syncsTo(TOKEN_B, "NOTIFICATION").map { it.data })
		val pushed = push.pushesTo(TOKEN_B).single().data
		assertEquals("MEMO_PINNED", pushed["kind"])
		assertEquals("에이님이 메모를 고정했어요", pushed["title"])
		assertEquals("장보기", pushed["body"])
		assertEquals("MEMO", pushed["targetType"])
		assertEquals(memo.toString(), pushed["targetId"])
		assertTrue(push.to(TOKEN_A).isEmpty())
	}

	@Test
	fun `D-135 이미 고정된 메모의 고정과 해제는 푸시 없이 해제만 MEMO 신호를 보낸다`() {
		val couple = newCouple()
		val memo = postMemo(couple.a.accessToken, """{"title":"장보기","notifyPartner":false}""")
		pinMemo(couple.a.accessToken, memo)
		push.reset()

		pinMemo(couple.a.accessToken, memo).andExpect(status().isNoContent) // 멱등: 이벤트 없음
		unpinMemo(couple.a.accessToken, memo).andExpect(status().isNoContent)

		assertEquals(listOf(syncData("MEMO", memo, "UPDATED")), push.to(TOKEN_B).map { it.data })
	}

	@Test
	fun `6장 메모를 만들고 지우면 상대에게 MEMO 신호가 가고 만들 때 PARTNER_CREATED 푸시가 간다`() {
		val couple = newCouple()

		val memo = postMemo(couple.a.accessToken, """{"title":"장보기"}""")
		deleteMemoOf(couple.a.accessToken, memo).andExpect(status().isNoContent)

		assertEquals(
			listOf(syncData("MEMO", memo, "CREATED"), syncData("MEMO", memo, "DELETED")),
			push.syncsTo(TOKEN_B, "MEMO").map { it.data },
		)
		val pushed = push.pushesTo(TOKEN_B).single().data
		assertEquals("PARTNER_CREATED", pushed["kind"])
		assertEquals("에이님이 메모를 남겼어요", pushed["title"])
		assertEquals(1, push.syncsTo(TOKEN_B, "NOTIFICATION").size)
	}

	@Test
	fun `D-134 즐겨찾기는 신호가 없다`() {
		val couple = newCouple()
		val memo = postMemo(couple.a.accessToken, """{"title":"장보기","notifyPartner":false}""")
		push.reset()

		favoriteMemo(couple.a.accessToken, memo).andExpect(status().isNoContent)
		favoriteMemo(couple.b.accessToken, memo).andExpect(status().isNoContent)

		assertTrue(push.sent.isEmpty())
	}

	// ── SPACE ───────────────────────────────────────────

	@Test
	fun `6장 처음 만난 날을 바꾸면 SPACE UPDATED만 가고 ANNIVERSARY 신호는 없다`() {
		val couple = newCouple()

		patchSpace(couple.a.accessToken, """{"startDate":"2026-01-01"}""").andExpect(status().isOk)

		assertEquals(listOf(syncData("SPACE", couple.spaceId, "UPDATED")), push.to(TOKEN_B).map { it.data })
		assertTrue(push.syncsTo(TOKEN_B, "ANNIVERSARY").isEmpty())
		assertTrue(push.to(TOKEN_A).isEmpty())
	}

	@Test
	fun `6장 사람 색을 바꾸면 상대에게 SPACE UPDATED가 간다`() {
		val couple = newCouple()

		patchSpaceColors(couple.a.accessToken, """{"together":"pick-teal"}""").andExpect(status().isOk)

		assertEquals(listOf(syncData("SPACE", couple.spaceId, "UPDATED")), push.to(TOKEN_B).map { it.data })
	}

	@Test
	fun `6장 커플 공간에서 닉네임을 바꾸면 상대에게 SPACE UPDATED가 간다`() {
		val couple = newCouple()

		patchMe(couple.a.accessToken, """{"nickname":"새이름"}""").andExpect(status().isOk)

		assertEquals(listOf(syncData("SPACE", couple.spaceId, "UPDATED")), push.to(TOKEN_B).map { it.data })
		assertTrue(push.to(TOKEN_A).isEmpty())
	}

	@Test
	fun `6장 닉네임이 같은 값이거나 시간대만 바꾸면 신호가 없다`() {
		val couple = newCouple()

		patchMe(couple.a.accessToken, """{"nickname":"에이"}""").andExpect(status().isOk)
		patchMe(couple.a.accessToken, """{"nickname":"  에이  "}""").andExpect(status().isOk) // 공백을 지우면 같은 값
		patchMe(couple.a.accessToken, """{"timeZone":"Asia/Tokyo"}""").andExpect(status().isOk)

		assertTrue(push.sent.isEmpty())
	}

	@Test
	fun `6장 개인 공간에서 닉네임을 바꾸면 신호가 없다`() {
		val guest = startGuest()
		registerDevice(guest, TOKEN_A)
		push.reset()

		patchMe(guest.accessToken, """{"nickname":"새이름"}""").andExpect(status().isOk)

		assertTrue(push.sent.isEmpty())
	}

	// ── LINKED / UNLINKED ───────────────────────────────

	@Test
	fun `FR-COUPLE-02 연결되면 두 사람 모두에게 LINKED 신호와 COUPLE_LINKED 푸시가 간다`() {
		val a = startMember("에이")
		val b = startMember("비비")
		registerDevice(a, TOKEN_A)
		registerDevice(b, TOKEN_B)
		// 연결 알림은 설정을 끈 사람에게도 간다 (7장)
		patchSettings(a.accessToken, """{"eventReminder":false,"partnerCreated":false,"memoPinned":false,"anniversary":false}""")
		push.reset()

		connect(a, b)

		listOf(TOKEN_A to "비비", TOKEN_B to "에이").forEach { (token, partnerName) ->
			val linked = push.syncsTo(token, "SPACE").single()
			// LINKED는 id가 없다 (6장)
			assertEquals(syncData("SPACE", null, "LINKED"), linked.data)
			assertFalse(linked.data.containsKey("id"))
			val pushed = push.pushesTo(token).single().data
			assertEquals("COUPLE_LINKED", pushed["kind"])
			assertEquals("${partnerName}님과 연결됐어요", pushed["title"])
			assertEquals("SETTINGS", pushed["targetType"])
			assertEquals(1, push.syncsTo(token, "NOTIFICATION").size)
		}
		val rowOfA = notificationRows(a.userId, "COUPLE_LINKED").single()
		assertEquals(rowOfA["id"].toString(), push.syncsTo(TOKEN_A, "NOTIFICATION").single().data["id"])
	}

	@Test
	fun `FR-COUPLE-05 해제하면 상대에게 UNLINKED 신호와 COUPLE_UNLINKED 푸시가 한 번 간다`() {
		val couple = newCouple()

		unlinkCouple(couple.a.accessToken).andExpect(status().isNoContent)

		assertEquals(listOf(syncData("SPACE", couple.spaceId, "UNLINKED")), push.syncsTo(TOKEN_B, "SPACE").map { it.data })
		val pushed = push.pushesTo(TOKEN_B).single().data
		assertEquals("COUPLE_UNLINKED", pushed["kind"])
		assertEquals("에이님이 연결을 해제했어요", pushed["title"])
		assertEquals("30일 안에 다시 연결하면 기록이 돌아와요", pushed["body"])
		assertEquals(1, push.syncsTo(TOKEN_B, "NOTIFICATION").size)
		// 해제한 사람에게는 가지 않는다
		assertTrue(push.to(TOKEN_A).isEmpty())
	}

	@Test
	fun `FR-AUTH-06 AC2 탈퇴로 해제되면 상대에게 탈퇴 문구 푸시가 한 번만 간다`() {
		val couple = newCouple()

		deleteMe(couple.a.accessToken).andExpect(status().isNoContent)

		val pushes = push.pushesTo(TOKEN_B)
		assertEquals(1, pushes.size)
		assertEquals("에이님이 탈퇴해 연결이 해제됐어요", pushes.single().data["title"])
		assertEquals("함께 쓴 기록은 더 이상 볼 수 없어요", pushes.single().data["body"])
		assertEquals("COUPLE_UNLINKED", pushes.single().data["kind"])
		assertEquals(1, push.syncsTo(TOKEN_B, "SPACE").size)
		assertEquals("UNLINKED", push.syncsTo(TOKEN_B, "SPACE").single().data["action"])
		assertEquals(1, push.syncsTo(TOKEN_B, "NOTIFICATION").size)
		// 탈퇴한 사람의 기기는 이미 지워져 신호가 가지 않는다
		assertTrue(push.to(TOKEN_A).isEmpty())
	}

	@Test
	fun `FR-AUTH-06 AC2 커플이 아닌 사용자의 탈퇴는 신호를 보내지 않는다`() {
		val guest = startGuest()
		registerDevice(guest, TOKEN_A)
		push.reset()

		deleteMe(guest.accessToken).andExpect(status().isNoContent)

		assertTrue(push.sent.isEmpty())
	}

	// ── 롤백 ────────────────────────────────────────────

	@Test
	fun `NFR-SYNC-01 롤백된 트랜잭션은 신호를 보내지 않는다`() {
		val couple = newCouple()

		withFailingTrigger("notifications", "insert") {
			postEventRaw(couple.a.accessToken, allDayEventJson()).expectError(500, "INTERNAL_ERROR")
		}

		assertEquals(0, count("select count(*) from events"))
		assertTrue(push.sent.isEmpty())
	}

	@Test
	fun `NFR-SYNC-01 신호는 커밋이 끝난 뒤에 간다`() {
		val couple = newCouple()
		var seenAtSend: Int? = null
		push.resultFor = { message ->
			if (message.data["resource"] == "EVENT") {
				// 발송 시점에 다른 연결에서 커밋된 일정이 보여야 한다
				seenAtSend = committedCountOf("select count(*) from events")
			}
			com.duriseo.server.push.application.PushResult.SENT
		}

		createEvent(couple.a.accessToken, allDayEventJson())

		assertEquals(1, seenAtSend)
	}

	@Test
	fun `NFR-SYNC-01 상대 기기가 없으면 신호를 보낼 곳이 없어도 요청은 성공한다`() {
		val a = startMember("에이")
		val b = startMember("비비")
		registerDevice(a, TOKEN_A)
		connect(a, b)
		push.reset()

		createEvent(a.accessToken, allDayEventJson())

		assertEquals(1, count("select count(*) from notifications where user_id = ? and type = 'PARTNER_CREATED'", b.userId))
		assertTrue(push.sent.isEmpty())
	}
}
