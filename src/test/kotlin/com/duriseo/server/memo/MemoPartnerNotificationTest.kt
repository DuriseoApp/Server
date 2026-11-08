package com.duriseo.server.memo

import com.duriseo.server.TestcontainersConfig
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
 * 상대 메모 추가 알림(`PARTNER_CREATED`, FR-NOTI-04 AC2·AC3)과 메모 고정 알림(`MEMO_PINNED`, D-135)의 알림 행, 그리고 메모 변경 도메인 이벤트
 * (`MemoCreated`·`MemoUpdated`·`MemoDeleted`, API.md 6장 연결점). API.md 7장 "상대 활동 알림", PRD 8장 알림 문구, D-131.
 * 푸시와 동기화 신호 발송은 단위 6이다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class MemoPartnerNotificationTest : MemoTestSupport() {

	private fun partnerCreatedCount(): Int = count("select count(*) from notifications where type = 'PARTNER_CREATED'")

	private fun pinnedCount(): Int = count("select count(*) from notifications where type = 'MEMO_PINNED'")

	// ── FR-NOTI-04 AC2 ───────────────────────────────────

	@Test
	fun `FR-NOTI-04 AC2 커플 공간에서 notifyPartner 켜고 메모를 만들면 상대에게 PARTNER_CREATED 행이 생긴다`() {
		val couple = newCouple()

		// 생략하면 true다
		val id = createMemo(couple.a.accessToken, """{"title":"장보기","body":"우유"}""")

		val row = notificationsOf(couple.b.userId, "PARTNER_CREATED").single()
		assertEquals("에이님이 메모를 남겼어요", row["title"])
		assertEquals("장보기", row["body"])
		assertEquals("MEMO", row["target_type"])
		assertEquals(id, row["target_id"])
		assertEquals(couple.spaceId, row["space_id"])
		assertNull(row["read_at"])
		// 작성자 본인에게는 만들지 않는다
		assertEquals(0, notificationsOf(couple.a.userId, "PARTNER_CREATED").size)
		assertEquals(1, partnerCreatedCount())
		// 도메인 이벤트에 상대와 알림 ID가 실린다 (푸시·신호는 단위 6)
		val created = memoChanges.created.single()
		assertEquals(id, created.memoId)
		assertEquals(couple.a.userId, created.createdByUserId)
		assertEquals(couple.b.userId, created.partnerUserId)
		assertEquals(row["id"], created.notificationId)
	}

	@Test
	fun `FR-NOTI-04 AC2 notifyPartner true를 명시해도 같고 상대가 만들면 에이가 받는다`() {
		val couple = newCouple()

		createMemo(couple.b.accessToken, """{"title":"데이트 계획","notifyPartner":true}""")

		val row = notificationsOf(couple.a.userId, "PARTNER_CREATED").single()
		assertEquals("비비님이 메모를 남겼어요", row["title"])
		assertEquals(0, notificationsOf(couple.b.userId, "PARTNER_CREATED").size)
	}

	@Test
	fun `FR-NOTI-04 AC2 제목이 없으면 알림 본문은 본문의 첫 비어 있지 않은 줄이다`() {
		val couple = newCouple()

		createMemo(couple.a.accessToken, """{"body":"\n\n   첫 줄 내용   \n둘째 줄"}""")

		assertEquals("첫 줄 내용", notificationsOf(couple.b.userId, "PARTNER_CREATED").single()["body"])
	}

	@Test
	fun `FR-NOTI-04 AC2 notifyPartner false나 개인 공간이면 알림 행이 없다`() {
		val couple = newCouple()
		val me = startMember("혼자")

		val off = createMemo(couple.a.accessToken, """{"title":"조용히","notifyPartner":false}""")
		createMemo(me.accessToken, """{"title":"혼자 쓰는 메모"}""")
		createMemo(me.accessToken, """{"title":"혼자 쓰는 메모 2","notifyPartner":true}""")

		assertEquals(0, partnerCreatedCount())
		val created = memoChanges.created.first { it.memoId == off }
		assertNull(created.partnerUserId)
		assertNull(created.notificationId)
		assertTrue(memoChanges.created.all { it.notificationId == null })
	}

	@Test
	fun `FR-NOTI-04 AC2 상대의 partner_created 설정이 꺼져 있어도 알림 행은 생긴다`() {
		val couple = newCouple()
		jdbc.update("update notification_settings set partner_created = false where user_id = ?", couple.b.userId)

		createMemo(couple.a.accessToken, """{"title":"메모"}""")

		// 설정은 푸시만 막는다 (API.md 7장 머리말, 푸시는 단위 6)
		assertEquals(1, notificationsOf(couple.b.userId, "PARTNER_CREATED").size)
	}

	@Test
	fun `FR-NOTI-04 AC2 값 검사에 걸린 POST는 알림 행도 메모도 남기지 않는다`() {
		val couple = newCouple()

		postMemoRaw(couple.a.accessToken, """{"memoDate":"2026-11-03"}""").andExpect(status().isBadRequest)
		postMemoRaw(couple.a.accessToken, """{"title":"제목","memoDate":"1900-01-01"}""").andExpect(status().isBadRequest)

		assertEquals(0, memoCount())
		assertEquals(0, partnerCreatedCount())
		assertEquals(0, memoChanges.created.size)
	}

	@Test
	fun `FR-NOTI-04 AC3 메모 수정은 알림 행을 만들지 않는다`() {
		val couple = newCouple()
		val id = createMemo(couple.a.accessToken, """{"title":"처음"}""")
		val notifications = count("select count(*) from notifications")

		patchMemo(couple.a.accessToken, id, """{"title":"수정","body":"본문","memoDate":"2026-11-03"}""").andExpect(status().isOk)
		deleteMemo(couple.a.accessToken, id).andExpect(status().isNoContent)

		assertEquals(notifications, count("select count(*) from notifications"))
		assertEquals(1, partnerCreatedCount())
		assertEquals(1, memoChanges.updated.size)
		assertEquals(1, memoChanges.deleted.size)
	}

	@Test
	fun `FR-NOTI-04 AC2 알림 문구는 만든 시점의 값으로 고정이라 닉네임을 바꿔도 그대로다`() {
		val couple = newCouple()
		createMemo(couple.a.accessToken, """{"title":"메모"}""")

		jdbc.update("update users set nickname = '바뀜' where id = ?", couple.a.userId)

		assertEquals("에이님이 메모를 남겼어요", notificationsOf(couple.b.userId, "PARTNER_CREATED").single()["title"])
	}

	// ── D-135 메모 고정 알림 ──────────────────────────────

	@Test
	fun `D-135 메모를 고정하면 고정하지 않은 쪽에 MEMO_PINNED 행이 생기고 고정 해제 고정을 반복하면 매번 생긴다`() {
		val couple = newCouple()
		val id = createMemo(couple.a.accessToken, """{"title":"공지","notifyPartner":false}""")

		// 상대(B)가 에이의 메모를 고정하면 고정하지 않은 쪽인 A가 받는다
		putPin(couple.b.accessToken, id).andExpect(status().isNoContent)
		val first = notificationsOf(couple.a.userId, "MEMO_PINNED").single()
		assertEquals("비비님이 메모를 고정했어요", first["title"])
		assertEquals("공지", first["body"])
		assertEquals("MEMO", first["target_type"])
		assertEquals(id, first["target_id"])
		assertEquals(couple.spaceId, first["space_id"])
		assertNull(first["read_at"])
		assertEquals(0, notificationsOf(couple.b.userId, "MEMO_PINNED").size)
		val pinEvent = memoChanges.updated.single()
		assertEquals(couple.b.userId, pinEvent.changedByUserId)
		assertEquals(couple.a.userId, pinEvent.partnerUserId)
		assertEquals(first["id"], pinEvent.notificationId)

		// 이미 고정인 메모의 PUT은 알림이 없다 (D-134)
		putPin(couple.b.accessToken, id).andExpect(status().isNoContent)
		putPin(couple.a.accessToken, id).andExpect(status().isNoContent)
		assertEquals(1, pinnedCount())

		// 해제는 알리지 않는다. 다시 고정하면 매번 보낸다
		clock.advance(Duration.ofMinutes(1))
		deletePin(couple.b.accessToken, id).andExpect(status().isNoContent)
		assertEquals(1, pinnedCount())
		putPin(couple.b.accessToken, id).andExpect(status().isNoContent)
		assertEquals(2, notificationsOf(couple.a.userId, "MEMO_PINNED").size)
		deletePin(couple.a.accessToken, id).andExpect(status().isNoContent)
		putPin(couple.a.accessToken, id).andExpect(status().isNoContent)
		// 이번에는 A가 고정했으니 B가 받는다
		assertEquals(1, notificationsOf(couple.b.userId, "MEMO_PINNED").size)
		assertEquals("에이님이 메모를 고정했어요", notificationsOf(couple.b.userId, "MEMO_PINNED").single()["title"])
		assertEquals(3, pinnedCount())
	}

	@Test
	fun `D-135 자기가 쓴 메모를 고정해도 상대에게 알림 행이 생긴다`() {
		val couple = newCouple()
		val id = createMemo(couple.a.accessToken, """{"title":"내 메모","notifyPartner":false}""")

		putPin(couple.a.accessToken, id).andExpect(status().isNoContent)

		val row = notificationsOf(couple.b.userId, "MEMO_PINNED").single()
		assertEquals("에이님이 메모를 고정했어요", row["title"])
		assertEquals(0, notificationsOf(couple.a.userId, "MEMO_PINNED").size)
	}

	@Test
	fun `D-135 제목이 없는 메모의 고정 알림 본문은 본문 첫 줄이고 50자를 넘으면 49자와 말줄임표다`() {
		val couple = newCouple()
		val long = "가".repeat(60)
		val id = createMemo(couple.a.accessToken, """{"body":"  \n\n$long\n둘째 줄","notifyPartner":false}""")

		putPin(couple.b.accessToken, id).andExpect(status().isNoContent)

		assertEquals("가".repeat(49) + "…", notificationsOf(couple.a.userId, "MEMO_PINNED").single()["body"])
	}

	@Test
	fun `D-135 상대의 memo_pinned 설정이 꺼져 있어도 알림 행은 생긴다`() {
		val couple = newCouple()
		jdbc.update("update notification_settings set memo_pinned = false where user_id = ?", couple.a.userId)
		val id = createMemo(couple.a.accessToken, """{"title":"메모","notifyPartner":false}""")

		putPin(couple.b.accessToken, id).andExpect(status().isNoContent)

		assertEquals(1, notificationsOf(couple.a.userId, "MEMO_PINNED").size)
	}

	@Test
	fun `D-135 개인 공간 고정과 해제는 알림 행이 없다`() {
		val me = startMember("혼자")
		val id = createMemo(me.accessToken, """{"title":"혼자 쓰는 메모"}""")

		putPin(me.accessToken, id).andExpect(status().isNoContent)
		deletePin(me.accessToken, id).andExpect(status().isNoContent)
		putPin(me.accessToken, id).andExpect(status().isNoContent)

		assertEquals(0, count("select count(*) from notifications"))
		assertEquals(3, memoChanges.updated.size)
		assertTrue(memoChanges.updated.all { it.partnerUserId == null && it.notificationId == null })
	}

	@Test
	fun `D-135 해제한 커플 공간의 알림은 해제 때 두 사람 모두 지워진다`() {
		val couple = newCouple()
		val id = createMemo(couple.a.accessToken, """{"title":"메모"}""")
		putPin(couple.a.accessToken, id).andExpect(status().isNoContent)
		assertEquals(2, count("select count(*) from notifications where type in ('PARTNER_CREATED', 'MEMO_PINNED')"))

		unlinkCouple(couple.a.accessToken).andExpect(status().isNoContent)

		// space_id가 있는 콘텐츠 알림이므로 연결 해제 정리(D-45)에 함께 지워진다
		assertEquals(0, count("select count(*) from notifications where type in ('PARTNER_CREATED', 'MEMO_PINNED')"))
	}
}
