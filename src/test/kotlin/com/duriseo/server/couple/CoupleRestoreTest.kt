package com.duriseo.server.couple

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.IntegrationTestSupport
import com.duriseo.server.support.Tokens
import com.duriseo.server.support.TestClockConfig
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Date
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 유예 기간 안의 이전 커플 공간 복구 (FR-COUPLE-06, API.md 4.4 4단계, ERD 4.2).
 * 해제된 공간은 해제 뒤 상태(UNLINKED, 두 소속에 left_at)를 DB에 직접 만들어 둔다. `DELETE /couple`로 해제한 뒤 다시 연결하는 흐름은
 * `CoupleUnlinkTest`가 본다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class CoupleRestoreTest : IntegrationTestSupport() {

	private fun codeOf(tokens: Tokens): String =
		JsonPath.read(bodyOf(postInviteCode(tokens.accessToken).andExpect(status().isCreated)), "$.code")

	/** 해제된 이전 커플 공간: UNLINKED, purge_at = 지금 + 30일, 처음 만난 날·함께 색·일정 하나, 두 소속은 떠난 상태 */
	private fun unlinkedCouple(a: UUID, aColor: String, b: UUID, bColor: String): UUID {
		val spaceId = insertSpace("COUPLE", unlinked = true)
		jdbc.update("update spaces set together_color = 'pick-rose', start_date = ? where id = ?", Date.valueOf(LocalDate.of(2024, 5, 1)), spaceId)
		insertMember(spaceId, a, aColor, left = true)
		insertMember(spaceId, b, bColor, left = true)
		insertEvent(spaceId, a)
		return spaceId
	}

	private fun spaceIdOf(body: String): UUID = UUID.fromString(JsonPath.read<String>(body, "$.spaceId"))

	@Test
	fun `FR-COUPLE-06 AC1 해제 후 30일 안에 같은 두 사람이 다시 연결하면 이전 공간이 ACTIVE로 복구되고 restored가 true다`() {
		val a = startMember()
		val b = startMember()
		val old = unlinkedCouple(a.userId, "pick-teal", b.userId, "pick-brown")
		// 해제 10일 뒤. access 토큰이 만료돼 새로 받는다 (refresh 토큰은 한 번 쓰면 바뀌므로 한 번씩만 쓴다)
		clock.advance(Duration.ofDays(10))
		val a2 = refreshed(a)
		val b2 = refreshed(b)
		val code = codeOf(a2)

		val body = bodyOf(
			joinCouple(b2.accessToken, code)
				.andExpect(status().isOk)
				.andExpect(jsonPath("$.restored").value(true))
				.andExpect(jsonPath("$.importPending").value(false)),
		)

		assertEquals(old, spaceIdOf(body))
		assertEquals(
			1,
			count(
				"select count(*) from spaces where id = ? and type = 'COUPLE' and status = 'ACTIVE' and unlinked_at is null and purge_at is null " +
					"and start_date = date '2024-05-01' and together_color = 'pick-rose'",
				old,
			),
		)
		assertEquals(2, count("select count(*) from space_members where space_id = ? and left_at is null", old))
		// 구성원 색과 콘텐츠는 해제 전 그대로, 새 커플 공간은 만들지 않는다
		assertEquals("pick-teal", jdbc.queryForObject("select color from space_members where space_id = ? and user_id = ?", String::class.java, old, a.userId))
		assertEquals("pick-brown", jdbc.queryForObject("select color from space_members where space_id = ? and user_id = ?", String::class.java, old, b.userId))
		assertEquals(1, count("select count(*) from events where space_id = ?", old))
		assertEquals(1, count("select count(*) from spaces where type = 'COUPLE'"))
		// 해제 뒤 새로 생긴 빈 개인 공간은 연결 때 지워진다
		assertEquals(0, count("select count(*) from spaces where type = 'PERSONAL'"))
		getMe(a2.accessToken)
			.andExpect(jsonPath("$.space.id").value(old.toString()))
			.andExpect(jsonPath("$.space.type").value("COUPLE"))
			.andExpect(jsonPath("$.space.myColor").value("pick-teal"))
			.andExpect(jsonPath("$.space.startDate").value("2024-05-01"))
	}

	@Test
	fun `FR-COUPLE-06 AC1 코드를 만든 사람과 입력한 사람이 이전과 바뀌어도 복구된다`() {
		val a = startMember()
		val b = startMember()
		val old = unlinkedCouple(a.userId, "pick-orange", b.userId, "pick-blue")
		// 이전에는 A가 코드를 만들었지만 이번에는 B가 만든다
		val code = codeOf(b)

		val body = bodyOf(joinCouple(a.accessToken, code).andExpect(status().isOk).andExpect(jsonPath("$.restored").value(true)))

		assertEquals(old, spaceIdOf(body))
		// 새 공간의 기본 색(만든 사람 주황, 입력한 사람 파랑)이 아니라 소속 행의 이전 색 그대로다
		assertEquals("pick-orange", jdbc.queryForObject("select color from space_members where space_id = ? and user_id = ?", String::class.java, old, a.userId))
		assertEquals("pick-blue", jdbc.queryForObject("select color from space_members where space_id = ? and user_id = ?", String::class.java, old, b.userId))
	}

	@Test
	fun `FR-COUPLE-06 AC1 purge_at이 지난 공간은 복구하지 않고 새 커플 공간을 만든다`() {
		val a = startMember()
		val b = startMember()
		val old = unlinkedCouple(a.userId, "pick-teal", b.userId, "pick-brown")
		// 정리 작업(단위 6)이 아직 지우지 않은, purge_at이 지난 상태
		jdbc.update("update spaces set purge_at = ? where id = ?", ts(clock.instant().minusSeconds(1)), old)
		val code = codeOf(a)

		val body = bodyOf(joinCouple(b.accessToken, code).andExpect(status().isOk).andExpect(jsonPath("$.restored").value(false)))

		val created = spaceIdOf(body)
		assertNotEquals(old, created)
		assertEquals(1, count("select count(*) from spaces where id = ? and status = 'UNLINKED'", old))
		assertEquals(2, count("select count(*) from space_members where space_id = ? and left_at is not null", old))
		assertEquals("pick-orange", jdbc.queryForObject("select color from space_members where space_id = ? and user_id = ?", String::class.java, created, a.userId))
		assertEquals("pick-blue", jdbc.queryForObject("select color from space_members where space_id = ? and user_id = ?", String::class.java, created, b.userId))
	}

	@Test
	fun `FR-COUPLE-06 AC1 다른 사람과 연결하면 이전 공간을 복구하지 않는다`() {
		val a = startMember()
		val b = startMember()
		val c = startMember()
		val old = unlinkedCouple(a.userId, "pick-teal", b.userId, "pick-brown")
		val code = codeOf(a)

		val body = bodyOf(joinCouple(c.accessToken, code).andExpect(status().isOk).andExpect(jsonPath("$.restored").value(false)))

		assertNotEquals(old, spaceIdOf(body))
		assertEquals(1, count("select count(*) from spaces where id = ? and status = 'UNLINKED' and purge_at is not null", old))
		assertEquals(2, count("select count(*) from space_members where space_id = ? and left_at is not null", old))
		assertEquals(2, count("select count(*) from spaces where type = 'COUPLE'"))
	}

	@Test
	fun `FR-COUPLE-06 AC1 복구돼도 두 사람에게 COUPLE_LINKED 알림 행이 생기고 연결 이벤트의 restored는 true다`() {
		val a = startMember("에이")
		val b = startMember("비비")
		unlinkedCouple(a.userId, "pick-teal", b.userId, "pick-brown")
		val code = codeOf(a)

		joinCouple(b.accessToken, code).andExpect(status().isOk)

		assertEquals("비비님과 연결됐어요", jdbc.queryForObject("select title from notifications where user_id = ? and type = 'COUPLE_LINKED'", String::class.java, a.userId))
		assertEquals("에이님과 연결됐어요", jdbc.queryForObject("select title from notifications where user_id = ? and type = 'COUPLE_LINKED'", String::class.java, b.userId))
		assertEquals(2, count("select count(*) from notifications where type = 'COUPLE_LINKED' and space_id is null"))
		assertEquals(true, coupleEvents.linked.single().restored)
	}

	@Test
	fun `FR-COUPLE-06 AC2 복구할 때도 혼자 쓴 기록이 있으면 importPending이 true다`() {
		val a = startMember()
		val b = startMember()
		unlinkedCouple(a.userId, "pick-teal", b.userId, "pick-brown")
		// 해제 뒤 각자 혼자 쓴 기록. B에게만 있다
		val personalOfB = jdbc.queryForObject("select space_id from space_members where user_id = ? and left_at is null", UUID::class.java, b.userId)!!
		insertMemo(personalOfB, b.userId)
		val code = codeOf(a)

		joinCouple(b.accessToken, code)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.restored").value(true))
			.andExpect(jsonPath("$.importPending").value(true))

		// 혼자 쓴 기록이 있는 B의 개인 공간은 가져오기를 정할 때까지 남는다
		assertEquals(1, count("select count(*) from spaces where id = ? and type = 'PERSONAL'", personalOfB))
		assertEquals(1, count("select count(*) from space_members where space_id = ? and left_at is not null", personalOfB))
		getMe(a.accessToken).andExpect(jsonPath("$.space.importPending").value(false))
		getMe(b.accessToken).andExpect(jsonPath("$.space.importPending").value(true))
	}
}
