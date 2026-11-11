package com.duriseo.server.notification

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.job.AnniversaryReminderJob
import com.duriseo.server.support.NotificationTestSupport
import com.duriseo.server.support.TestClockConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 기념일 알림 작업 (API.md 7장 "기념일 알림", D-32·D-55·D-105·D-106·D-138, ERD DB-18, 단위 6 확인 필요 5). 시계는 `2026-10-26T00:00:00Z`(= 서울 오전 9시)로 시작한다.
 * 스케줄러는 꺼져 있고 작업 메서드를 직접 부른다. 새 사용자의 시간대는 `Asia/Seoul`이다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class AnniversaryJobTest : NotificationTestSupport() {

	@Autowired
	private lateinit var job: AnniversaryReminderJob

	private fun at(iso: String) = Instant.parse(iso)

	private fun anniversaryRows(userId: UUID) = notificationRows(userId, "ANNIVERSARY")

	private fun titles(userId: UUID) = anniversaryRows(userId).map { it["title"] as String }.toSet()

	private fun setStartDate(spaceId: UUID, date: String) {
		jdbc.update("update spaces set start_date = cast(? as date) where id = ?", date, spaceId)
	}

	private fun activeSpaceOf(userId: UUID): UUID =
		jdbc.queryForObject("select space_id from space_members where user_id = ? and left_at is null", UUID::class.java, userId)!!

	@Test
	fun `D-138 현지 오전 9시인 사용자에게 오늘 기념일마다 알림 하나를 만든다`() {
		val couple = newCouple()
		// 처음 만난 날 2026-07-19 → 2026-10-26이 100일째(D+100)
		setStartDate(couple.spaceId, "2026-07-19")
		val once = insertAnniversary(couple.spaceId, couple.a.userId, name = "첫 여행", date = "2026-10-26", repeatsYearly = false)
		val yearly = insertAnniversary(couple.spaceId, couple.a.userId, name = "생일", date = "2024-10-26", repeatsYearly = true)
		insertAnniversary(couple.spaceId, couple.a.userId, name = "내일", date = "2026-10-27", repeatsYearly = true)
		push.reset()

		// 서울 오전 9시(00:00Z). 확인 필요 5: 직접 기념일을 만든 A만이 아니라 공간의 구성원 둘 다 받는다
		assertEquals(6, job.run())

		listOf(couple.a to TOKEN_A, couple.b to TOKEN_B).forEach { (member, token) ->
			val rows = anniversaryRows(member.userId).associateBy { it["title"] as String }
			assertEquals(setOf("오늘은 100일이에요", "오늘은 첫 여행이에요", "오늘은 생일이에요"), rows.keys)

			val auto = rows.getValue("오늘은 100일이에요")
			assertEquals("함께한 지 D+100", auto["body"])
			assertEquals("ANNIVERSARY", auto["target_type"])
			assertNull(auto["target_id"])
			assertEquals(couple.spaceId, auto["space_id"])

			val first = rows.getValue("오늘은 첫 여행이에요")
			assertEquals("오늘이에요", first["body"])
			assertEquals("ANNIVERSARY", first["target_type"])
			assertEquals(once, first["target_id"])

			val birthday = rows.getValue("오늘은 생일이에요")
			assertEquals("3번째 맞는 날이에요", birthday["body"])
			assertEquals(yearly, birthday["target_id"])

			// 기념일마다 푸시 하나 + NOTIFICATION 신호 하나
			assertEquals(3, push.pushesTo(token).size)
			assertEquals(setOf("ANNIVERSARY"), push.pushesTo(token).map { it.data["kind"] }.toSet())
			assertEquals(3, push.syncsTo(token, "NOTIFICATION").size)
		}
	}

	@Test
	fun `D-138 현지 시가 9시대가 아닌 사용자는 받지 않고 30분 단위 시간대는 9시 30분에 받는다`() {
		val couple = newCouple()
		setStartDate(couple.spaceId, "2026-07-19")
		// 인도(UTC+5:30)는 00:00Z에 05:30, 04:00Z에 09:30이다. 서울(A)은 04:00Z에 13:00이다
		jdbc.update("update users set time_zone = 'Asia/Kolkata' where id = ?", couple.b.userId)

		assertEquals(1, job.run())
		assertEquals(1, anniversaryRows(couple.a.userId).size)
		assertEquals(0, anniversaryRows(couple.b.userId).size)

		clock.set(at("2026-10-26T04:00:00Z"))
		assertEquals(1, job.run())
		assertEquals(1, anniversaryRows(couple.a.userId).size)
		assertEquals(1, anniversaryRows(couple.b.userId).size)
		assertEquals("오늘은 100일이에요", anniversaryRows(couple.b.userId).single()["title"])
	}

	@Test
	fun `D-138 같은 날 다시 돌아도 같은 제목 알림을 만들지 않는다`() {
		val couple = newCouple()
		setStartDate(couple.spaceId, "2026-07-19")
		insertAnniversary(couple.spaceId, couple.a.userId, name = "첫 여행", date = "2026-10-26", repeatsYearly = false)

		assertEquals(4, job.run())
		// 같은 시각에 다시, 서버가 재시작돼 9시대에 다시 돌아도 (현지 9시대는 한 시간이다)
		assertEquals(0, job.run())
		clock.set(at("2026-10-26T00:30:00Z"))
		assertEquals(0, job.run())

		assertEquals(2, anniversaryRows(couple.a.userId).size)
		assertEquals(2, anniversaryRows(couple.b.userId).size)
		assertEquals(2, push.pushesTo(TOKEN_A).size)

		// 다음 날은 새 기념일이 없다
		clock.set(at("2026-10-27T00:00:00Z"))
		assertEquals(0, job.run())
	}

	@Test
	fun `D-138 같은 제목은 사용자 현지 날짜가 바뀌면 다시 만든다`() {
		val couple = newCouple()
		insertAnniversary(couple.spaceId, couple.a.userId, name = "만남", date = "2025-10-26", repeatsYearly = true)
		assertEquals(2, job.run())
		// 1년 뒤 같은 제목, 다른 날: 어제 구간의 행은 중복으로 치지 않는다
		clock.set(at("2027-10-26T00:00:00Z"))
		assertEquals(2, job.run())
		assertEquals(2, anniversaryRows(couple.a.userId).size)
		assertEquals(setOf("2번째 맞는 날이에요", "3번째 맞는 날이에요"), anniversaryRows(couple.a.userId).map { it["body"] as String }.toSet())
	}

	@Test
	fun `D-106 처음 만난 날이 2월 29일이면 평년 주년 알림은 2월 28일이다`() {
		val couple = newCouple()
		setStartDate(couple.spaceId, "2024-02-29")

		// 2026년은 평년: 2주년은 2월 28일 오전 9시(서울)이고 3월 1일에는 없다
		clock.set(at("2026-02-28T00:00:00Z"))
		assertEquals(2, job.run())
		val row = anniversaryRows(couple.a.userId).single()
		assertEquals("오늘은 2주년이에요", row["title"])
		assertEquals("함께한 지 D+731", row["body"])
		assertNull(row["target_id"])

		clock.set(at("2026-03-01T00:00:00Z"))
		assertEquals(0, job.run())
		assertEquals(1, anniversaryRows(couple.a.userId).size)

		// 윤년(2028)에는 2월 29일이다. 2월 28일에는 없다
		clock.set(at("2028-02-28T00:00:00Z"))
		assertEquals(0, job.run())
		clock.set(at("2028-02-29T00:00:00Z"))
		assertEquals(2, job.run())
		assertEquals(setOf("오늘은 2주년이에요", "오늘은 4주년이에요"), titles(couple.a.userId))
	}

	@Test
	fun `D-55 직접 반복 기념일 본문은 N번째 맞는 날이에요`() {
		val couple = newCouple()
		// 2027-02-28(평년): 반복 기념일 2020-02-29는 2월 28일에 온다 → 2027 − 2020 + 1 = 8번째
		insertAnniversary(couple.spaceId, couple.a.userId, name = "윤일", date = "2020-02-29", repeatsYearly = true)
		// 등록한 해에는 "오늘이에요", 반복이 아니면 해가 지나도 그날 하루뿐이다
		insertAnniversary(couple.spaceId, couple.a.userId, name = "올해 등록", date = "2027-02-28", repeatsYearly = true)
		insertAnniversary(couple.spaceId, couple.a.userId, name = "한 번만", date = "2027-02-28", repeatsYearly = false)
		insertAnniversary(couple.spaceId, couple.a.userId, name = "작년 한 번", date = "2026-02-28", repeatsYearly = false)

		clock.set(at("2027-02-28T00:00:00Z"))
		assertEquals(6, job.run())

		val bodies = anniversaryRows(couple.b.userId).associate { it["title"] as String to it["body"] as String }
		assertEquals(
			mapOf(
				"오늘은 윤일이에요" to "8번째 맞는 날이에요",
				"오늘은 올해 등록이에요" to "오늘이에요",
				"오늘은 한 번만이에요" to "오늘이에요",
			),
			bodies,
		)
	}

	@Test
	fun `FR-ANNIV-04 AC4 처음 만난 날이 없는 커플과 개인 공간에는 자동 기념일 알림이 없다`() {
		val couple = newCouple()
		val solo = startMember("혼자")
		registerDevice(solo, "fcm-token-solo-fake")
		val personalSpace = activeSpaceOf(solo.userId)
		insertAnniversary(personalSpace, solo.userId, name = "내 기념일", date = "2026-10-26", repeatsYearly = false)

		// 처음 만난 날이 없는 커플은 아무것도 받지 않고, 개인 공간은 직접 기념일만 받는다
		assertEquals(1, job.run())
		assertEquals(0, anniversaryRows(couple.a.userId).size)
		assertEquals(0, anniversaryRows(couple.b.userId).size)
		val row = anniversaryRows(solo.userId).single()
		assertEquals("오늘은 내 기념일이에요", row["title"])
		assertEquals(personalSpace, row["space_id"])
	}

	@Test
	fun `D-138 푸시를 끄거나 유효 세션이 없어도 알림 행과 NOTIFICATION 신호는 남는다`() {
		val couple = newCouple()
		setStartDate(couple.spaceId, "2026-07-19")
		jdbc.update(
			"insert into notification_settings (user_id, anniversary, updated_at) values (?, false, ?) on conflict (user_id) do update set anniversary = false",
			couple.a.userId, ts(clock.instant()),
		)
		revokeSessions(couple.b.userId)

		assertEquals(2, job.run())

		assertEquals(0, push.pushesTo(TOKEN_A).size)
		assertEquals(0, push.pushesTo(TOKEN_B).size)
		assertEquals(1, push.syncsTo(TOKEN_A, "NOTIFICATION").size)
		assertEquals(1, push.syncsTo(TOKEN_B, "NOTIFICATION").size)
	}

	@Test
	fun `D-138 연결을 해제했거나 탈퇴한 사용자는 받지 않는다`() {
		val couple = newCouple()
		setStartDate(couple.spaceId, "2026-07-19")
		unlinkCouple(couple.a.accessToken)
		jdbc.update("update users set deleted_at = ? where id = ?", ts(clock.instant()), couple.b.userId)

		// 해제된 공간(UNLINKED, 구성원 모두 left_at)과 탈퇴한 사용자는 대상이 아니다. A의 개인 공간에는 기념일이 없다
		assertEquals(0, job.run())
	}

	@Test
	fun `D-138 동시에 두 번 돌아도 같은 알림은 하나다`() {
		val couple = newCouple()
		setStartDate(couple.spaceId, "2026-07-19")

		inParallel(List(3) { { job.run() } })

		assertEquals(1, committedCountOf("select count(*) from notifications where user_id = ? and type = 'ANNIVERSARY'", couple.a.userId))
		assertEquals(1, committedCountOf("select count(*) from notifications where user_id = ? and type = 'ANNIVERSARY'", couple.b.userId))
		assertEquals(1, push.pushesTo(TOKEN_A).size)
	}
}
