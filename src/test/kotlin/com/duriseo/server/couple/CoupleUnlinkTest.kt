package com.duriseo.server.couple

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.IntegrationTestSupport
import com.duriseo.server.support.TestClockConfig
import com.duriseo.server.support.Tokens
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 연결 해제 `DELETE /couple` (FR-COUPLE-05, API.md 4.5, ERD 4.2, R-18)와 해제 뒤 다시 연결(FR-COUPLE-06).
 * 탈퇴로 인한 해제는 `WithdrawalTest`가 본다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class CoupleUnlinkTest : IntegrationTestSupport() {

	/** 연결된 커플 한 쌍: A("에이", 코드를 만든 쪽)와 B("비비") */
	private class Couple(val a: Tokens, val b: Tokens, val spaceId: UUID)

	private fun newCouple(): Couple {
		val a = startMember("에이")
		val b = startMember("비비")
		return Couple(a, b, connect(a, b))
	}

	private fun notificationsOf(userId: UUID, type: String): List<Map<String, Any?>> =
		jdbc.queryForList("select * from notifications where user_id = ? and type = ? order by created_at, id", userId, type)

	// ── FR-COUPLE-05 AC1 ─────────────────────────────────

	@Test
	fun `FR-COUPLE-05 AC1 연결을 해제하면 커플 공간이 UNLINKED가 되고 unlinked_at과 30일 뒤 purge_at이 기록되며 두 소속에 left_at이 생긴다`() {
		val couple = newCouple()
		clock.advance(Duration.ofMinutes(10))

		unlinkCouple(couple.a.accessToken).andExpect(status().isNoContent).andExpect(content().string(""))

		val now = clock.instant()
		assertEquals(1, count("select count(*) from spaces where id = ? and type = 'COUPLE' and status = 'UNLINKED'", couple.spaceId))
		assertEquals(now, instantOf("select unlinked_at from spaces where id = ?", couple.spaceId))
		assertEquals(now.plus(Duration.ofDays(30)), instantOf("select purge_at from spaces where id = ?", couple.spaceId))
		assertEquals(2, count("select count(*) from space_members where space_id = ? and left_at = ?", couple.spaceId, ts(now)))
		assertEquals(0, count("select count(*) from space_members where space_id = ? and left_at is null", couple.spaceId))
		// 공간과 기록은 지워지지 않고 보관된다 (30일 안에 다시 연결하면 복구)
		assertEquals(1, count("select count(*) from space_members where space_id = ? and user_id = ?", couple.spaceId, couple.a.userId))
		assertEquals(1, count("select count(*) from space_members where space_id = ? and user_id = ?", couple.spaceId, couple.b.userId))
	}

	@Test
	fun `FR-COUPLE-05 AC1 어느 쪽이든 해제할 수 있다`() {
		val couple = newCouple()

		// 코드를 입력한 B가 해제해도 같다
		unlinkCouple(couple.b.accessToken).andExpect(status().isNoContent)

		assertEquals(1, count("select count(*) from spaces where id = ? and status = 'UNLINKED'", couple.spaceId))
		assertEquals("비비님이 연결을 해제했어요", notificationsOf(couple.a.userId, "COUPLE_UNLINKED").single()["title"])
	}

	@Test
	fun `FR-COUPLE-05 AC1 해제하면 두 사람 모두 새 빈 개인 공간(pick-orange)에서 다시 시작한다`() {
		val couple = newCouple()

		unlinkCouple(couple.a.accessToken).andExpect(status().isNoContent)

		val spaceOfA = activeSpaceIdOfUser(couple.a.userId)
		val spaceOfB = activeSpaceIdOfUser(couple.b.userId)
		assertNotEquals(couple.spaceId, spaceOfA)
		assertNotEquals(couple.spaceId, spaceOfB)
		assertNotEquals(spaceOfA, spaceOfB)
		for ((spaceId, userId) in listOf(spaceOfA to couple.a.userId, spaceOfB to couple.b.userId)) {
			assertEquals(1, count("select count(*) from spaces where id = ? and type = 'PERSONAL' and status = 'ACTIVE'", spaceId))
			assertEquals(1, count("select count(*) from space_members where space_id = ? and user_id = ? and color = 'pick-orange'", spaceId, userId))
			assertEquals(0, count("select count(*) from events where space_id = ?", spaceId))
			assertEquals(0, count("select count(*) from memos where space_id = ?", spaceId))
		}
		// 활성 소속은 사용자마다 하나다
		assertEquals(2, count("select count(*) from space_members where left_at is null"))
	}

	@Test
	fun `FR-COUPLE-05 AC1 해제하면 상대에게만 COUPLE_UNLINKED 알림 행이 space_id null로 생긴다`() {
		val couple = newCouple()

		unlinkCouple(couple.a.accessToken).andExpect(status().isNoContent)

		val notification = notificationsOf(couple.b.userId, "COUPLE_UNLINKED").single()
		assertEquals("에이님이 연결을 해제했어요", notification["title"])
		assertEquals("30일 안에 다시 연결하면 기록이 돌아와요", notification["body"])
		assertEquals(null, notification["space_id"])
		assertEquals("SETTINGS", notification["target_type"])
		assertEquals(null, notification["target_id"])
		assertEquals(null, notification["read_at"])
		// 해제한 사람에게는 만들지 않는다
		assertEquals(0, notificationsOf(couple.a.userId, "COUPLE_UNLINKED").size)
		assertEquals(1, count("select count(*) from notifications where type = 'COUPLE_UNLINKED'"))
	}

	@Test
	fun `FR-COUPLE-05 AC1 해제하면 그 공간의 event_reminders와 space_id가 그 공간인 알림이 두 사람 모두 지워지고 COUPLE_LINKED 알림은 남는다`() {
		val couple = newCouple()
		val eventOfA = insertEvent(couple.spaceId, couple.a.userId, "에이의 일정")
		val eventOfB = insertEvent(couple.spaceId, couple.b.userId, "비비의 일정")
		insertEventReminder(eventOfA, couple.a.userId)
		insertEventReminder(eventOfA, couple.b.userId)
		insertEventReminder(eventOfB, couple.a.userId)
		insertNotification(couple.a.userId, couple.spaceId)
		insertNotification(couple.b.userId, couple.spaceId)
		// 다른 사람의 공간 알림과 일정 알림은 건드리지 않는다
		val stranger = startMember()
		val strangerSpace = activeSpaceIdOfUser(stranger.userId)
		insertNotification(stranger.userId, strangerSpace)
		insertEventReminder(insertEvent(strangerSpace, stranger.userId), stranger.userId)

		unlinkCouple(couple.a.accessToken).andExpect(status().isNoContent)

		assertEquals(0, count("select count(*) from event_reminders where event_id in (?, ?)", eventOfA, eventOfB))
		assertEquals(0, count("select count(*) from notifications where space_id = ?", couple.spaceId))
		// 일정 자체와 공간 밖에 묶인 알림은 남는다
		assertEquals(2, count("select count(*) from events where space_id = ?", couple.spaceId))
		assertEquals(2, count("select count(*) from notifications where type = 'COUPLE_LINKED' and space_id is null"))
		assertEquals(1, count("select count(*) from notifications where type = 'COUPLE_UNLINKED' and space_id is null"))
		assertEquals(1, count("select count(*) from notifications where space_id = ?", strangerSpace))
		assertEquals(1, count("select count(*) from event_reminders"))
	}

	@Test
	fun `FR-COUPLE-05 AC1 둘이 동시에 해제해도 한 번만 처리되고 다른 요청은 409 NOT_COUPLE`() {
		repeat(3) {
			val couple = newCouple()

			val statuses = inParallel(
				listOf(
					{ statusOf(unlinkCouple(couple.a.accessToken)) },
					{ statusOf(unlinkCouple(couple.b.accessToken)) },
				),
			)

			assertEquals(listOf(204, 409), statuses.sorted())
			assertEquals(1, count("select count(*) from notifications where type = 'COUPLE_UNLINKED' and (user_id = ? or user_id = ?)", couple.a.userId, couple.b.userId))
			assertEquals(1, count("select count(*) from spaces where id = ? and status = 'UNLINKED'", couple.spaceId))
			// 두 사람은 각자 개인 공간을 하나씩만 가진다
			assertEquals(1, count("select count(*) from space_members where user_id = ? and left_at is null", couple.a.userId))
			assertEquals(1, count("select count(*) from space_members where user_id = ? and left_at is null", couple.b.userId))
		}
	}

	@Test
	fun `FR-COUPLE-05 AC1 해제 이벤트는 커밋된 뒤에만 리스너에 전달된다`() {
		val couple = newCouple()
		// 이벤트를 받는 순간 요청과 별개의 새 DB 연결에서 해제 결과가 보이면 이미 커밋된 뒤다
		val seenAtDelivery = mutableListOf<String>()
		coupleEvents.onUnlinked = {
			val unlinked = committedCountOf("select count(*) from spaces where id = ? and status = 'UNLINKED'", couple.spaceId)
			val notified = committedCountOf("select count(*) from notifications where user_id = ? and type = 'COUPLE_UNLINKED'", couple.b.userId)
			seenAtDelivery.add("unlinked=$unlinked notified=$notified")
		}

		unlinkCouple(couple.a.accessToken).andExpect(status().isNoContent)

		assertEquals(listOf("unlinked=1 notified=1"), seenAtDelivery)
		val event = coupleEvents.unlinked.single()
		assertEquals(couple.spaceId, event.spaceId)
		assertEquals(couple.a.userId, event.unlinkedByUserId)
		assertEquals(couple.b.userId, event.partnerUserId)
		assertEquals(false, event.byWithdrawal)
		assertEquals(notificationsOf(couple.b.userId, "COUPLE_UNLINKED").single()["id"], event.notificationId)
	}

	@Test
	fun `FR-COUPLE-05 AC1 해제 도중 실패하면 커플 공간·소속·알림이 그대로이고 이벤트도 가지 않는다`() {
		val couple = newCouple()
		insertNotification(couple.a.userId, couple.spaceId)
		val before = coupleSnapshot()

		// 마지막 단계인 상대 알림 INSERT가 실패한다. 앞 단계(해제, 알림 삭제, 새 개인 공간)까지 모두 되돌아가야 한다
		withFailingTrigger("notifications", "insert") {
			unlinkCouple(couple.a.accessToken).expectError(500, "INTERNAL_ERROR")
		}

		assertEquals(before, coupleSnapshot())
		assertTrue(coupleEvents.unlinked.isEmpty())
		getMe(couple.a.accessToken).andExpect(jsonPath("$.space.type").value("COUPLE"))
	}

	// ── FR-COUPLE-05 AC3 ─────────────────────────────────

	@Test
	fun `FR-COUPLE-05 AC3 해제 뒤 두 사람의 GET me는 PERSONAL과 새 공간 ID를 돌려준다`() {
		val couple = newCouple()

		unlinkCouple(couple.a.accessToken).andExpect(status().isNoContent)

		for (tokens in listOf(couple.a, couple.b)) {
			val body = bodyOf(getMe(tokens.accessToken).andExpect(status().isOk).andExpect(jsonPath("$.space.type").value("PERSONAL")))
			assertNotEquals(couple.spaceId.toString(), JsonPath.read<String>(body, "$.space.id"))
			assertEquals(activeSpaceIdOfUser(tokens.userId).toString(), JsonPath.read<String>(body, "$.space.id"))
			assertEquals(null, JsonPath.read<Any?>(body, "$.space.partner"))
			assertEquals("pick-orange", JsonPath.read<String>(body, "$.space.myColor"))
			assertEquals(false, JsonPath.read<Boolean>(body, "$.space.importPending"))
		}
		getSpace(couple.b.accessToken).andExpect(jsonPath("$.type").value("PERSONAL"))
	}

	@Test
	fun `FR-COUPLE-05 AC3 해제해도 닉네임·프로필 사진 키·알림 설정은 그대로다`() {
		val couple = newCouple()
		jdbc.update("update users set profile_image_key = 'fake/profile-a.jpg' where id = ?", couple.a.userId)
		jdbc.update("update notification_settings set event_reminder = false where user_id = ?", couple.a.userId)
		val usersBefore = jdbc.queryForList("select id, nickname, profile_image_key, time_zone, deleted_at from users order by id").toString()
		val settingsBefore = jdbc.queryForList("select * from notification_settings order by user_id").toString()

		unlinkCouple(couple.a.accessToken).andExpect(status().isNoContent)

		assertEquals(usersBefore, jdbc.queryForList("select id, nickname, profile_image_key, time_zone, deleted_at from users order by id").toString())
		assertEquals(settingsBefore, jdbc.queryForList("select * from notification_settings order by user_id").toString())
		getMe(couple.a.accessToken).andExpect(jsonPath("$.nickname").value("에이")).andExpect(jsonPath("$.hasProfileImage").value(true))
		// 로그인 세션도 그대로라 같은 access 토큰을 계속 쓴다
		getMe(couple.b.accessToken).andExpect(status().isOk)
	}

	// ── FR-COUPLE-05 AC4 ─────────────────────────────────

	@Test
	fun `FR-COUPLE-05 AC4 가져오기를 정하기 전에 해제하면 대기 중인 개인 공간이 다시 활성 공간이 되고 기록과 색이 그대로이며 importPending이 false다`() {
		val a = startMember("에이")
		val b = startMember("비비")
		val personalOfB = activeSpaceIdOfUser(b.userId)
		val memoOfB = insertMemo(personalOfB, b.userId, "비비의 메모")
		val eventOfB = insertEvent(personalOfB, b.userId, "비비의 일정")
		jdbc.update("update space_members set color = 'pick-teal' where space_id = ? and user_id = ?", personalOfB, b.userId)
		val coupleSpaceId = connect(a, b)
		getMe(b.accessToken).andExpect(jsonPath("$.space.importPending").value(true))

		unlinkCouple(a.accessToken).andExpect(status().isNoContent)

		// 새 개인 공간을 만들지 않고 원래 개인 공간으로 돌아간다
		assertEquals(personalOfB, activeSpaceIdOfUser(b.userId))
		assertEquals(1, count("select count(*) from space_members where user_id = ? and space_id = ?", b.userId, personalOfB))
		assertEquals(personalOfB, jdbc.queryForObject("select space_id from memos where id = ?", UUID::class.java, memoOfB))
		assertEquals(personalOfB, jdbc.queryForObject("select space_id from events where id = ?", UUID::class.java, eventOfB))
		assertEquals("pick-teal", jdbc.queryForObject("select color from space_members where space_id = ? and user_id = ?", String::class.java, personalOfB, b.userId))
		assertEquals(0, count("select count(*) from space_members where user_id = ? and left_at is not null and space_id <> ?", b.userId, coupleSpaceId))
		getMe(b.accessToken)
			.andExpect(jsonPath("$.space.type").value("PERSONAL"))
			.andExpect(jsonPath("$.space.id").value(personalOfB.toString()))
			.andExpect(jsonPath("$.space.myColor").value("pick-teal"))
			.andExpect(jsonPath("$.space.importPending").value(false))
	}

	@Test
	fun `FR-COUPLE-05 AC4 대기 공간이 없는 구성원에게만 새 개인 공간을 만든다`() {
		val a = startMember("에이")
		val b = startMember("비비")
		val personalOfA = activeSpaceIdOfUser(a.userId)
		val personalOfB = activeSpaceIdOfUser(b.userId)
		insertMemo(personalOfB, b.userId)
		val coupleSpaceId = connect(a, b)
		// 기록이 없던 A의 개인 공간은 연결 때 지워졌다
		assertEquals(0, count("select count(*) from spaces where id = ?", personalOfA))

		unlinkCouple(b.accessToken).andExpect(status().isNoContent)

		assertEquals(personalOfB, activeSpaceIdOfUser(b.userId))
		val newSpaceOfA = activeSpaceIdOfUser(a.userId)
		assertNotEquals(personalOfA, newSpaceOfA)
		assertNotEquals(coupleSpaceId, newSpaceOfA)
		// 개인 공간은 둘뿐이다 (B의 되살린 공간과 A의 새 공간)
		assertEquals(2, count("select count(*) from spaces where type = 'PERSONAL'"))
	}

	@Test
	fun `FR-COUPLE-05 AC4 해제 뒤 POST couple import는 409 NO_IMPORT_PENDING`() {
		val a = startMember()
		val b = startMember()
		insertMemo(activeSpaceIdOfUser(b.userId), b.userId)
		connect(a, b)
		unlinkCouple(a.accessToken).andExpect(status().isNoContent)
		val before = coupleSnapshot()

		importRecords(b.accessToken, true).expectError(409, "NO_IMPORT_PENDING")
		importRecords(a.accessToken, false).expectError(409, "NO_IMPORT_PENDING")

		assertEquals(before, coupleSnapshot())
	}

	// ── R-18 ─────────────────────────────────────────────

	@Test
	fun `R-18 개인 공간에서 DELETE couple은 409 NOT_COUPLE이고 아무것도 바뀌지 않는다`() {
		val guest = startGuest()
		val member = startMember()
		insertMemo(activeSpaceIdOfUser(member.userId), member.userId)
		val before = coupleSnapshot()

		unlinkCouple(guest.accessToken).expectError(409, "NOT_COUPLE")
		unlinkCouple(member.accessToken).expectError(409, "NOT_COUPLE")

		assertEquals(before, coupleSnapshot())
		assertEquals(0, count("select count(*) from notifications"))
		assertTrue(coupleEvents.unlinked.isEmpty())
	}

	@Test
	fun `R-18 이미 해제된 뒤의 두 번째 DELETE couple은 409 NOT_COUPLE이다`() {
		val couple = newCouple()
		unlinkCouple(couple.a.accessToken).andExpect(status().isNoContent)
		val before = coupleSnapshot()

		unlinkCouple(couple.a.accessToken).expectError(409, "NOT_COUPLE")
		unlinkCouple(couple.b.accessToken).expectError(409, "NOT_COUPLE")

		assertEquals(before, coupleSnapshot())
	}

	@Test
	fun `R-18 커플 구성원은 DELETE couple을 부를 수 있다`() {
		val couple = newCouple()

		unlinkCouple(couple.b.accessToken).andExpect(status().isNoContent)
	}

	@Test
	fun `DELETE couple은 access 토큰이 없으면 401 SESSION_INVALID다`() {
		unlinkCouple("not-a-token").expectError(401, "SESSION_INVALID")
	}

	// ── FR-COUPLE-06: 해제한 두 사람이 다시 연결 ───────────

	@Test
	fun `FR-COUPLE-06 AC1 DELETE couple로 해제한 두 사람이 30일 안에 다시 연결하면 같은 공간이 복구된다`() {
		val couple = newCouple()
		jdbc.update("update spaces set start_date = date '2024-05-01', together_color = 'pick-rose' where id = ?", couple.spaceId)
		jdbc.update("update space_members set color = 'pick-teal' where space_id = ? and user_id = ?", couple.spaceId, couple.a.userId)
		val memoId = insertMemo(couple.spaceId, couple.a.userId, "함께 쓴 메모")
		unlinkCouple(couple.a.accessToken).andExpect(status().isNoContent)
		clock.advance(Duration.ofMinutes(20))

		// 이번에는 B가 코드를 만들고 A가 입력해 코드를 만든 사람이 바뀌어도 복구된다
		val code = JsonPath.read<String>(bodyOf(postInviteCode(couple.b.accessToken).andExpect(status().isCreated)), "$.code")
		joinCouple(couple.a.accessToken, code)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.spaceId").value(couple.spaceId.toString()))
			.andExpect(jsonPath("$.restored").value(true))
			.andExpect(jsonPath("$.importPending").value(false))

		assertEquals(1, count("select count(*) from spaces where type = 'COUPLE'"))
		assertEquals(1, count("select count(*) from spaces where id = ? and status = 'ACTIVE' and unlinked_at is null and purge_at is null and together_color = 'pick-rose' and start_date = date '2024-05-01'", couple.spaceId))
		assertEquals("pick-teal", jdbc.queryForObject("select color from space_members where space_id = ? and user_id = ?", String::class.java, couple.spaceId, couple.a.userId))
		assertEquals(couple.spaceId, jdbc.queryForObject("select space_id from memos where id = ?", UUID::class.java, memoId))
		// 해제 뒤 만든 빈 개인 공간은 연결 때 지워졌다
		assertEquals(0, count("select count(*) from spaces where type = 'PERSONAL'"))
		assertEquals(couple.spaceId, activeSpaceIdOfUser(couple.a.userId))
		assertEquals(couple.spaceId, activeSpaceIdOfUser(couple.b.userId))
		assertEquals(true, coupleEvents.linked.last().restored)
	}

	@Test
	fun `FR-COUPLE-06 AC2 해제 뒤 혼자 쓴 기록은 다시 연결한 뒤 import true로 복구된 공간에 합쳐진다`() {
		val couple = newCouple()
		val oldMemo = insertMemo(couple.spaceId, couple.a.userId, "함께 쓴 메모")
		unlinkCouple(couple.a.accessToken).andExpect(status().isNoContent)
		// 해제 뒤 B가 새 개인 공간에 혼자 쓴 기록
		val newMemoOfB = insertMemo(activeSpaceIdOfUser(couple.b.userId), couple.b.userId, "혼자 쓴 메모")
		clock.advance(Duration.ofMinutes(20))
		val code = JsonPath.read<String>(bodyOf(postInviteCode(couple.a.accessToken).andExpect(status().isCreated)), "$.code")

		joinCouple(couple.b.accessToken, code)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.restored").value(true))
			.andExpect(jsonPath("$.importPending").value(true))
		importRecords(couple.b.accessToken, true).andExpect(status().isNoContent)

		assertEquals(couple.spaceId, jdbc.queryForObject("select space_id from memos where id = ?", UUID::class.java, newMemoOfB))
		assertEquals(couple.spaceId, jdbc.queryForObject("select space_id from memos where id = ?", UUID::class.java, oldMemo))
		getMe(couple.b.accessToken)
			.andExpect(jsonPath("$.space.id").value(couple.spaceId.toString()))
			.andExpect(jsonPath("$.space.importPending").value(false))
	}
}
