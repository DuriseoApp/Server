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
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 초대코드로 연결 `POST /couple/join` (FR-COUPLE-02, FR-COUPLE-03의 연결 쪽, FR-CAL-04.AC3, R-09, API.md 4.4 2~7단계).
 * 오입력 제한(AC4)은 `CoupleJoinRateLimitTest`, 이전 공간 복구(FR-COUPLE-06)는 `CoupleRestoreTest`가 확인한다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class CoupleJoinTest : IntegrationTestSupport() {

	private fun codeOf(tokens: Tokens): String =
		JsonPath.read(bodyOf(postInviteCode(tokens.accessToken).andExpect(status().isCreated)), "$.code")

	private fun personalSpaceOf(userId: UUID): UUID =
		jdbc.queryForObject("select space_id from space_members where user_id = ? and left_at is null", UUID::class.java, userId)!!

	private fun colorOf(spaceId: UUID, userId: UUID): String =
		jdbc.queryForObject("select color from space_members where space_id = ? and user_id = ?", String::class.java, spaceId, userId)!!

	private fun attemptCount(userId: UUID): Int = count("select count(*) from invite_code_attempts where user_id = ?", userId)

	private fun spaceIdOf(body: String): UUID = UUID.fromString(JsonPath.read<String>(body, "$.spaceId"))

	// ── FR-COUPLE-02 AC1 ────────────────────────────────

	@Test
	fun `FR-COUPLE-02 AC1 유효한 코드를 입력하면 커플 공간과 구성원 둘이 생기고 코드가 사용 처리된다`() {
		val a = startMember("에이")
		val b = startMember("비비")
		val code = codeOf(a)

		val body = bodyOf(
			joinCouple(b.accessToken, code)
				.andExpect(status().isOk)
				.andExpect(jsonPath("$.restored").value(false))
				.andExpect(jsonPath("$.importPending").value(false)),
		)
		val spaceId = spaceIdOf(body)

		assertEquals(
			1,
			count(
				"select count(*) from spaces where id = ? and type = 'COUPLE' and status = 'ACTIVE' and together_color = 'pick-purple' " +
					"and start_date is null and unlinked_at is null and purge_at is null",
				spaceId,
			),
		)
		assertEquals(1, count("select count(*) from spaces where type = 'COUPLE'"))
		assertEquals(2, count("select count(*) from space_members where space_id = ? and left_at is null", spaceId))
		assertEquals(clock.instant(), instantOf("select used_at from invite_codes where code = ?", code))
		assertEquals(b.userId, jdbc.queryForObject("select used_by_user_id from invite_codes where code = ?", UUID::class.java, code))
		// 성공한 시도는 같은 트랜잭션에서 succeeded = true로 남는다
		assertEquals(1, count("select count(*) from invite_code_attempts where user_id = ? and succeeded = true", b.userId))
	}

	@Test
	fun `FR-CAL-04 AC3 새 커플 공간의 기본 색은 코드를 만든 사람 pick-orange, 입력한 사람 pick-blue, 함께 pick-purple이다`() {
		val a = startMember()
		val b = startMember()

		val spaceId = connect(a, b)

		assertEquals("pick-orange", colorOf(spaceId, a.userId))
		assertEquals("pick-blue", colorOf(spaceId, b.userId))
		assertEquals("pick-purple", jdbc.queryForObject("select together_color from spaces where id = ?", String::class.java, spaceId))
	}

	@Test
	fun `FR-COUPLE-02 AC1 연결 뒤 두 사람의 GET me는 같은 COUPLE 공간과 서로를 상대로 돌려준다`() {
		val a = startMember("에이")
		val b = startMember("비비")
		val spaceId = connect(a, b)

		getMe(a.accessToken)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.space.id").value(spaceId.toString()))
			.andExpect(jsonPath("$.space.type").value("COUPLE"))
			.andExpect(jsonPath("$.space.myColor").value("pick-orange"))
			.andExpect(jsonPath("$.space.togetherColor").value("pick-purple"))
			.andExpect(jsonPath("$.space.partner.id").value(b.userId.toString()))
			.andExpect(jsonPath("$.space.partner.nickname").value("비비"))
			.andExpect(jsonPath("$.space.partner.color").value("pick-blue"))
		getMe(b.accessToken)
			.andExpect(jsonPath("$.space.id").value(spaceId.toString()))
			.andExpect(jsonPath("$.space.type").value("COUPLE"))
			.andExpect(jsonPath("$.space.myColor").value("pick-blue"))
			.andExpect(jsonPath("$.space.partner.id").value(a.userId.toString()))
			.andExpect(jsonPath("$.space.partner.nickname").value("에이"))
			.andExpect(jsonPath("$.space.partner.color").value("pick-orange"))
	}

	@Test
	fun `FR-COUPLE-02 AC5 상대가 연결하면 코드를 만든 사람의 GET me 공간 종류가 COUPLE이고 공간 ID가 바뀐다`() {
		val a = startMember()
		val b = startMember()
		val code = codeOf(a)
		val beforeSpaceId = bodyOf(getMe(a.accessToken)).let { JsonPath.read<String>(it, "$.space.id") }
		getMe(a.accessToken).andExpect(jsonPath("$.space.type").value("PERSONAL"))

		val spaceId = spaceIdOf(bodyOf(joinCouple(b.accessToken, code).andExpect(status().isOk)))

		val after = bodyOf(getMe(a.accessToken))
		assertEquals("COUPLE", JsonPath.read<String>(after, "$.space.type"))
		assertEquals(spaceId.toString(), JsonPath.read<String>(after, "$.space.id"))
		assertNotEquals(beforeSpaceId, JsonPath.read<String>(after, "$.space.id"))
	}

	@Test
	fun `FR-COUPLE-02 AC1 연결하면 두 사람 각자에게 COUPLE_LINKED 알림 행이 space_id null로 생긴다`() {
		val a = startMember("에이")
		val b = startMember("비비")
		connect(a, b)

		// 받는 사람 기준의 상대 닉네임이 제목에 들어간다 (PRD 8장). target_type은 SETTINGS, target_id는 null (D-85)
		val forA = jdbc.queryForMap("select * from notifications where user_id = ?", a.userId)
		assertEquals("COUPLE_LINKED", forA["type"])
		assertEquals("비비님과 연결됐어요", forA["title"])
		assertEquals("이제 캘린더와 메모를 함께 써요", forA["body"])
		assertEquals("SETTINGS", forA["target_type"])
		assertNull(forA["target_id"])
		assertNull(forA["space_id"])
		assertNull(forA["read_at"])

		val forB = jdbc.queryForMap("select * from notifications where user_id = ?", b.userId)
		assertEquals("COUPLE_LINKED", forB["type"])
		assertEquals("에이님과 연결됐어요", forB["title"])
		assertEquals("SETTINGS", forB["target_type"])
		assertNull(forB["space_id"])
		assertEquals(2, count("select count(*) from notifications"))
	}

	@Test
	fun `FR-COUPLE-02 AC1 연결 이벤트는 커밋된 뒤에만 리스너에 전달된다`() {
		val a = startMember()
		val b = startMember()
		val code = codeOf(a)
		// 전달되는 순간 다른 연결에서 커밋된 커플 공간이 보여야 한다
		val coupleSpacesSeenAtDelivery = AtomicInteger(-1)
		val linkedRowsSeenAtDelivery = AtomicInteger(-1)
		coupleEvents.onLinked = { event ->
			coupleSpacesSeenAtDelivery.set(committedCountOf("select count(*) from spaces where id = ? and type = 'COUPLE'", event.spaceId))
			linkedRowsSeenAtDelivery.set(committedCountOf("select count(*) from notifications where type = 'COUPLE_LINKED'"))
		}

		val spaceId = spaceIdOf(bodyOf(joinCouple(b.accessToken, code).andExpect(status().isOk)))

		assertEquals(1, coupleEvents.linked.size)
		val event = coupleEvents.linked.single()
		assertEquals(spaceId, event.spaceId)
		assertEquals(a.userId, event.creatorUserId)
		assertEquals(b.userId, event.joinerUserId)
		assertEquals(false, event.restored)
		assertEquals(setOf(a.userId, b.userId), event.notificationIds.keys)
		assertEquals(1, coupleSpacesSeenAtDelivery.get())
		assertEquals(2, linkedRowsSeenAtDelivery.get())
	}

	@Test
	fun `FR-COUPLE-02 AC1 연결 도중 실패하면 이벤트가 전달되지 않고 공간·소속·코드·알림이 그대로다`() {
		val a = startMember()
		val b = startMember()
		val code = codeOf(a)
		val before = coupleSnapshot()

		withFailingTrigger("notifications", "insert") {
			joinCouple(b.accessToken, code).expectError(500, "INTERNAL_ERROR")
		}

		assertTrue(coupleEvents.linked.isEmpty())
		assertEquals(before, coupleSnapshot())
		assertEquals(0, count("select count(*) from invite_code_attempts"))
		// 실패 뒤에도 같은 코드로 연결된다
		joinCouple(b.accessToken, code).andExpect(status().isOk)
	}

	// ── FR-COUPLE-01 AC2·AC3·AC4 (입력 쪽) ──────────────

	@Test
	fun `FR-COUPLE-01 AC4 소문자와 공백이 섞인 코드도 공백을 지우고 대문자로 바꿔 같은 코드로 연결한다`() {
		val a = startMember()
		val b = startMember()
		inviteCodeGenerator.enqueue("XK7M2P")
		postInviteCode(a.accessToken).andExpect(jsonPath("$.code").value("XK7M2P"))

		joinCouple(b.accessToken, " xk7 m2p ").andExpect(status().isOk)

		assertEquals(1, count("select count(*) from invite_codes where code = 'XK7M2P' and used_at is not null"))
	}

	@Test
	fun `FR-COUPLE-01 AC2 만든 지 10분이 지난 코드를 입력하면 409 INVITE_EXPIRED`() {
		val a = startMember()
		val b = startMember()
		val code = codeOf(a)
		clock.advance(Duration.ofMinutes(10))

		joinCouple(b.accessToken, code).expectError(409, "INVITE_EXPIRED")

		assertEquals(0, count("select count(*) from spaces where type = 'COUPLE'"))
	}

	@Test
	fun `FR-COUPLE-01 AC2 만료 1초 전의 코드로는 연결된다`() {
		val a = startMember()
		val b = startMember()
		val code = codeOf(a)
		clock.advance(Duration.ofSeconds(599))

		joinCouple(b.accessToken, code).andExpect(status().isOk)
	}

	@Test
	fun `FR-COUPLE-01 AC3 재요청으로 바뀐 이전 코드를 입력하면 409 INVITE_EXPIRED`() {
		val a = startMember()
		val b = startMember()
		inviteCodeGenerator.enqueue("AAAAAA", "BBBBBB")
		codeOf(a)
		clock.advance(Duration.ofSeconds(60))
		codeOf(a)

		joinCouple(b.accessToken, "AAAAAA").expectError(409, "INVITE_EXPIRED")
		joinCouple(b.accessToken, "BBBBBB").andExpect(status().isOk)
	}

	// ── FR-COUPLE-02 AC2: 코드마다 다른 오류 ─────────────

	@Test
	fun `FR-COUPLE-02 AC2 없는 코드는 404 INVITE_NOT_FOUND`() {
		val b = startMember()

		joinCouple(b.accessToken, "ZZZZZZ").expectError(404, "INVITE_NOT_FOUND")
	}

	@Test
	fun `FR-COUPLE-02 AC2 6자리가 아니거나 집합 밖 글자가 있는 코드는 404 INVITE_NOT_FOUND이고 오입력으로 센다`() {
		val b = startMember()
		val malformed = listOf("ABC12", "ABCDEFG", "ABCDE0", "ABCDEI", "ABCDEL", "ABCDEO", "ABCDE1", "ABC-EF")

		malformed.forEach { joinCouple(b.accessToken, it).expectError(404, "INVITE_NOT_FOUND") }

		assertEquals(malformed.size, count("select count(*) from invite_code_attempts where user_id = ? and succeeded = false", b.userId))
	}

	@Test
	fun `FR-COUPLE-02 AC2 만료된 코드는 409 INVITE_EXPIRED`() {
		val a = startMember()
		val b = startMember()
		insertInviteCodeRow(a.userId, "ABCDEF", createdAt = clock.instant().minusSeconds(660))

		joinCouple(b.accessToken, "ABCDEF").expectError(409, "INVITE_EXPIRED")
	}

	@Test
	fun `FR-COUPLE-02 AC2 무효(revoked_at)인 이전 코드는 409 INVITE_EXPIRED`() {
		val a = startMember()
		val b = startMember()
		// 아직 10분 안이지만 재요청으로 무효가 된 코드
		insertInviteCodeRow(a.userId, "ABCDEF", createdAt = clock.instant().minusSeconds(60), revokedAt = clock.instant().minusSeconds(1))

		joinCouple(b.accessToken, "ABCDEF").expectError(409, "INVITE_EXPIRED")
	}

	@Test
	fun `FR-COUPLE-02 AC2 이미 사용된 코드는 409 INVITE_USED`() {
		val a = startMember()
		val b = startMember()
		val c = startMember()
		insertInviteCodeRow(a.userId, "ABCDEF", usedAt = clock.instant(), usedBy = c.userId)

		joinCouple(b.accessToken, "ABCDEF").expectError(409, "INVITE_USED")
	}

	@Test
	fun `FR-COUPLE-02 AC2 자신이 만든 코드는 409 INVITE_SELF`() {
		val a = startMember()
		val code = codeOf(a)

		joinCouple(a.accessToken, code).expectError(409, "INVITE_SELF")

		assertEquals(0, count("select count(*) from spaces where type = 'COUPLE'"))
	}

	@Test
	fun `FR-COUPLE-02 AC2 탈퇴한 사람의 코드는 지워져 404 INVITE_NOT_FOUND`() {
		val a = startMember()
		val b = startMember()
		val code = codeOf(a)
		deleteMe(a.accessToken).andExpect(status().isNoContent)

		joinCouple(b.accessToken, code).expectError(404, "INVITE_NOT_FOUND")
	}

	@Test
	fun `FR-COUPLE-02 AC2 여러 거부 사유가 겹치면 API 4_4가 정한 순서로 하나만 돌려준다`() {
		// 자기 코드가 만료되기도 했으면 SELF (7번이 10번보다 먼저)
		val selfAndExpired = startMember()
		insertInviteCodeRow(selfAndExpired.userId, "AAAAAA", createdAt = clock.instant().minusSeconds(660))
		joinCouple(selfAndExpired.accessToken, "AAAAAA").expectError(409, "INVITE_SELF")

		// 사용된 코드가 만료되기도 했으면 USED (8번이 10번보다 먼저)
		val creator = startMember()
		val usedAndExpired = startMember()
		insertInviteCodeRow(creator.userId, "BBBBBB", createdAt = clock.instant().minusSeconds(660), usedAt = clock.instant().minusSeconds(100), usedBy = creator.userId)
		joinCouple(usedAndExpired.accessToken, "BBBBBB").expectError(409, "INVITE_USED")

		// 사용된 코드가 무효이기도 했으면 USED
		val usedAndRevoked = startMember()
		insertInviteCodeRow(creator.userId, "CCCCCC", usedAt = clock.instant(), usedBy = creator.userId, revokedAt = clock.instant())
		joinCouple(usedAndRevoked.accessToken, "CCCCCC").expectError(409, "INVITE_USED")

		// 자기 코드가 사용되기도 했으면 SELF (7번이 8번보다 먼저)
		val selfAndUsed = startMember()
		insertInviteCodeRow(selfAndUsed.userId, "DDDDDD", usedAt = clock.instant(), usedBy = creator.userId)
		joinCouple(selfAndUsed.accessToken, "DDDDDD").expectError(409, "INVITE_SELF")
	}

	@Test
	fun `FR-COUPLE-02 AC2 만든 사람이 지금 커플 공간에 있으면 무효나 만료보다 INVITE_UNAVAILABLE이 먼저다`() {
		val a = startMember()
		val d = startMember()
		val codeOfA = codeOf(a)
		joinCouple(a.accessToken, codeOf(d)).andExpect(status().isOk)
		// 연결로 A의 코드는 무효가 됐다(revoked_at). 그래도 10번(만료·무효)이 아니라 9번이다
		assertEquals(1, count("select count(*) from invite_codes where code = ? and revoked_at is not null and used_at is null", codeOfA))

		val c = startMember()
		joinCouple(c.accessToken, codeOfA).expectError(409, "INVITE_UNAVAILABLE")

		// 만료된 코드여도 같다
		val e = startMember()
		clock.advance(Duration.ofMinutes(11))
		joinCouple(e.accessToken, codeOfA).expectError(409, "INVITE_UNAVAILABLE")
	}

	@Test
	fun `FR-COUPLE-02 AC2 code가 없거나 비면 400 VALIDATION_FAILED (code REQUIRED)`() {
		val b = startMember()

		listOf("{}", """{"code": null}""", """{"code": "   "}""", """{"code": ""}""", null).forEach { json ->
			joinCoupleRaw(b.accessToken, json)
				.expectError(400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.details.fields[0].field").value("code"))
				.andExpect(jsonPath("$.details.fields[0].reason").value("REQUIRED"))
		}
		// 시도로 남지 않는다
		assertEquals(0, attemptCount(b.userId))
	}

	@Test
	fun `FR-COUPLE-02 AC2 code가 문자열이 아니면 400 VALIDATION_FAILED (code INVALID)이고 시도로 남지 않는다`() {
		val b = startMember()

		joinCoupleRaw(b.accessToken, """{"code": 123456}""")
			.expectError(400, "VALIDATION_FAILED")
			.andExpect(jsonPath("$.details.fields[0].field").value("code"))
			.andExpect(jsonPath("$.details.fields[0].reason").value("INVALID"))

		assertEquals(0, attemptCount(b.userId))
	}

	@Test
	fun `FR-COUPLE-02 AC2 거부된 입력은 공간·소속·코드를 바꾸지 않는다`() {
		val a = startMember()
		val b = startMember()
		val c = startMember()
		insertInviteCodeRow(a.userId, "ABCDEF", createdAt = clock.instant().minusSeconds(660))
		insertInviteCodeRow(c.userId, "GHJKMN", usedAt = clock.instant(), usedBy = a.userId)
		val own = codeOf(b)
		val before = coupleSnapshot()

		joinCouple(b.accessToken, "ZZZZZZ").expectError(404, "INVITE_NOT_FOUND")
		joinCouple(b.accessToken, "ABCDEF").expectError(409, "INVITE_EXPIRED")
		joinCouple(b.accessToken, "GHJKMN").expectError(409, "INVITE_USED")
		joinCouple(b.accessToken, own).expectError(409, "INVITE_SELF")
		joinCoupleRaw(b.accessToken, "{}").expectError(400, "VALIDATION_FAILED")

		assertEquals(before, coupleSnapshot())
	}

	// ── FR-COUPLE-02 AC3: 동시성과 사용할 수 없는 코드 ────

	@Test
	fun `FR-COUPLE-02 AC3 코드를 만든 사람이 이미 다른 사람과 연결됐으면 409 INVITE_UNAVAILABLE`() {
		val a = startMember()
		val d = startMember()
		val c = startMember()
		val codeOfA = codeOf(a)
		joinCouple(a.accessToken, codeOf(d)).andExpect(status().isOk)

		joinCouple(c.accessToken, codeOfA).expectError(409, "INVITE_UNAVAILABLE")

		assertEquals(1, count("select count(*) from spaces where type = 'COUPLE'"))
		assertEquals(0, count("select count(*) from space_members where user_id = ? and left_at is not null", c.userId))
	}

	@Test
	fun `FR-COUPLE-02 AC2 같은 코드를 두 사람이 동시에 입력하면 한 사람만 연결되고 다른 사람은 409 INVITE_USED`() {
		val a = startMember()
		val b1 = startMember()
		val b2 = startMember()
		val code = codeOf(a)

		val results = inParallel(
			listOf(b1, b2).map { joiner ->
				{
					val response = joinCouple(joiner.accessToken, code)
					statusOf(response) to bodyOf(response)
				}
			},
		)

		val statuses = results.map { it.first }.sorted()
		assertEquals(listOf(200, 409), statuses, "results=$results")
		val loser = results.single { it.first == 409 }
		assertEquals("INVITE_USED", JsonPath.read<String>(loser.second, "$.code"))
		assertEquals(1, count("select count(*) from spaces where type = 'COUPLE'"))
		assertEquals(1, count("select count(*) from invite_codes where used_at is not null"))
		// 연결된 사람 한 명만 커플 공간에 있다
		assertEquals(1, count("select count(*) from space_members sm join spaces s on s.id = sm.space_id where s.type = 'COUPLE' and sm.user_id in (?, ?)", b1.userId, b2.userId))
	}

	@Test
	fun `FR-COUPLE-02 AC3 A가 B의 코드를 입력하는 동안 C가 A의 코드를 입력해도 A는 한 커플에만 들어간다`() {
		val a = startMember()
		val b = startMember()
		val c = startMember()
		val codeOfA = codeOf(a)
		val codeOfB = codeOf(b)

		val results = inParallel(
			listOf(
				{
					val response = joinCouple(a.accessToken, codeOfB)
					statusOf(response) to bodyOf(response)
				},
				{
					val response = joinCouple(c.accessToken, codeOfA)
					statusOf(response) to bodyOf(response)
				},
			),
		)

		assertEquals(1, results.count { it.first == 200 }, "results=$results")
		val loser = results.single { it.first != 200 }
		assertEquals(409, loser.first)
		// A가 먼저 연결되면 C는 사용할 수 없는 코드, C가 먼저 연결되면 A는 이미 연결됨
		assertTrue(
			JsonPath.read<String>(loser.second, "$.code") in setOf("INVITE_UNAVAILABLE", "ALREADY_COUPLED"),
			"loser=$loser",
		)
		assertEquals(1, count("select count(*) from space_members where user_id = ? and left_at is null", a.userId))
		assertEquals(1, count("select count(*) from spaces where type = 'COUPLE'"))
		assertEquals(2, count("select count(*) from space_members sm join spaces s on s.id = sm.space_id where s.type = 'COUPLE'"))
	}

	@Test
	fun `FR-COUPLE-02 AC3 서로의 코드를 동시에 입력해도 한 번만 연결된다`() {
		val a = startMember()
		val b = startMember()
		val codeOfA = codeOf(a)
		val codeOfB = codeOf(b)

		val results = inParallel(
			listOf(
				{
					val response = joinCouple(a.accessToken, codeOfB)
					statusOf(response) to bodyOf(response)
				},
				{
					val response = joinCouple(b.accessToken, codeOfA)
					statusOf(response) to bodyOf(response)
				},
			),
		)

		assertEquals(1, results.count { it.first == 200 }, "results=$results")
		val loser = results.single { it.first != 200 }
		assertEquals(409, loser.first)
		assertEquals("ALREADY_COUPLED", JsonPath.read<String>(loser.second, "$.code"))
		assertEquals(1, count("select count(*) from spaces where type = 'COUPLE'"))
		assertEquals(2, count("select count(*) from space_members where left_at is null"))
	}

	// ── DB-16: 연결 때 다른 유효한 코드 무효화 ────────────

	@Test
	fun `FR-COUPLE-02 AC3 연결하면 두 사람의 다른 유효한 코드에 revoked_at이 기록된다`() {
		val a = startMember()
		val b = startMember()
		// B가 만들어 둔 코드: 10분이 지나 만료됐지만 revoked_at이 없는 채로 남아 있다
		inviteCodeGenerator.enqueue("BBBBBB", "AAAAAA")
		codeOf(b)
		clock.advance(Duration.ofMinutes(11))
		codeOf(a)

		joinCouple(b.accessToken, "AAAAAA").andExpect(status().isOk)

		assertEquals(clock.instant(), instantOf("select revoked_at from invite_codes where code = 'BBBBBB'"))
		// 입력한 코드는 사용 처리만 되고 무효화되지 않는다
		assertEquals(1, count("select count(*) from invite_codes where code = 'AAAAAA' and used_at is not null and revoked_at is null"))
		assertEquals(0, count("select count(*) from invite_codes where used_at is null and revoked_at is null"))
	}

	// ── FR-COUPLE-03: 연결 때 개인 공간 처리 (ERD 4.1) ────

	@Test
	fun `FR-COUPLE-03 AC1 기록이 있는 사용자는 연결 뒤 개인 공간이 지워지지 않고 소속에 left_at이 생기며 importPending이 true다`() {
		val a = startMember()
		val b = startMember()
		val personalOfB = personalSpaceOf(b.userId)
		insertMemo(personalOfB, b.userId)
		val code = codeOf(a)

		joinCouple(b.accessToken, code)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.importPending").value(true))

		assertEquals(1, count("select count(*) from spaces where id = ? and type = 'PERSONAL'", personalOfB))
		assertEquals(1, count("select count(*) from memos where space_id = ?", personalOfB))
		assertEquals(clock.instant(), instantOf("select left_at from space_members where space_id = ? and user_id = ?", personalOfB, b.userId))
		getMe(b.accessToken)
			.andExpect(jsonPath("$.space.type").value("COUPLE"))
			.andExpect(jsonPath("$.space.importPending").value(true))
	}

	@Test
	fun `FR-COUPLE-03 AC1 연결 응답의 importPending은 요청자 기준이다`() {
		val a = startMember()
		val b = startMember()
		insertEvent(personalSpaceOf(a.userId), a.userId)
		val code = codeOf(a)

		// 기록이 있는 쪽은 코드를 만든 A다. 입력한 B의 응답은 false
		joinCouple(b.accessToken, code)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.importPending").value(false))

		getMe(a.accessToken).andExpect(jsonPath("$.space.importPending").value(true))
		getMe(b.accessToken).andExpect(jsonPath("$.space.importPending").value(false))
		// B는 기록이 없어 개인 공간이 지워졌고, A의 개인 공간은 남았다
		assertEquals(1, count("select count(*) from spaces where type = 'PERSONAL'"))
	}

	@Test
	fun `FR-COUPLE-03 AC4 기록이 없는 사용자는 연결 때 개인 공간이 바로 지워지고 importPending이 false다`() {
		val a = startMember()
		val b = startMember()
		val personalOfA = personalSpaceOf(a.userId)
		val personalOfB = personalSpaceOf(b.userId)

		connect(a, b)

		assertEquals(0, count("select count(*) from spaces where id in (?, ?)", personalOfA, personalOfB))
		assertEquals(0, count("select count(*) from spaces where type = 'PERSONAL'"))
		getMe(a.accessToken).andExpect(jsonPath("$.space.importPending").value(false))
		getMe(b.accessToken).andExpect(jsonPath("$.space.importPending").value(false))
	}

	@Test
	fun `FR-COUPLE-03 AC4 결정하기 전에는 GET me의 importPending이 계속 true다`() {
		val a = startMember()
		val b = startMember()
		insertMemo(personalSpaceOf(b.userId), b.userId)
		connect(a, b)

		getMe(b.accessToken).andExpect(jsonPath("$.space.importPending").value(true))
		clock.advance(Duration.ofMinutes(30))
		getMe(b.accessToken).andExpect(jsonPath("$.space.importPending").value(true))
		// 다시 실행해 활성 공간을 다시 읽어도 같다
		getSpace(b.accessToken).andExpect(jsonPath("$.type").value("COUPLE"))
		getMe(b.accessToken).andExpect(jsonPath("$.space.importPending").value(true))
	}

	@Test
	fun `ERD 4_5 연결 때 지워지는 빈 개인 공간의 알림도 함께 지워지고 COUPLE_LINKED 알림만 남는다`() {
		val a = startMember()
		val b = startMember()
		insertNotification(a.userId, personalSpaceOf(a.userId))
		insertNotification(b.userId, personalSpaceOf(b.userId))

		connect(a, b)

		assertEquals(0, count("select count(*) from notifications where type = 'PARTNER_CREATED'"))
		assertEquals(2, count("select count(*) from notifications where type = 'COUPLE_LINKED' and space_id is null"))
	}

	// ── R-09 ────────────────────────────────────────────

	@Test
	fun `R-09 게스트가 POST couple join을 부르면 403 GUEST_NOT_ALLOWED이고 시도 기록도 없다`() {
		val a = startMember()
		val guest = startGuest()
		val code = codeOf(a)

		joinCouple(guest.accessToken, code).expectError(403, "GUEST_NOT_ALLOWED")
		// code 검사보다 R-09가 먼저다
		joinCoupleRaw(guest.accessToken, "{}").expectError(403, "GUEST_NOT_ALLOWED")
		joinCouple(guest.accessToken, "ZZZZZZ").expectError(403, "GUEST_NOT_ALLOWED")

		assertEquals(0, count("select count(*) from invite_code_attempts"))
		assertEquals(0, count("select count(*) from spaces where type = 'COUPLE'"))
		assertEquals(0, count("select count(*) from invite_codes where used_at is not null"))
	}

	@Test
	fun `R-09 커플 공간 회원이 POST couple join을 부르면 409 ALREADY_COUPLED이고 코드는 사용되지 않는다`() {
		val a = startMember()
		val b = startMember()
		val c = startMember()
		connect(a, b)
		val code = codeOf(c)
		val before = coupleSnapshot()

		joinCouple(a.accessToken, code).expectError(409, "ALREADY_COUPLED")
		joinCoupleRaw(a.accessToken, "{}").expectError(409, "ALREADY_COUPLED")
		joinCouple(a.accessToken, "ZZZZZZ").expectError(409, "ALREADY_COUPLED")

		assertEquals(before, coupleSnapshot())
		assertEquals(1, count("select count(*) from invite_codes where code = ? and used_at is null", code))
		assertEquals(0, attemptCount(a.userId))
	}
}
