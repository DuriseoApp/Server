package com.duriseo.server.space

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.space.application.SpaceUpdated
import com.duriseo.server.support.IntegrationTestSupport
import com.duriseo.server.support.TestClockConfig
import com.duriseo.server.support.Tokens
import com.jayway.jsonpath.JsonPath
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 공간 `GET /space`, 처음 만난 날 `PATCH /space`, 사람 색 `PATCH /space/colors`
 * (FR-SET-01, FR-COUPLE-04, FR-CAL-04, R-01, R-08, R-15, API.md 3.2 "공간 변경 규칙").
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
@RecordApplicationEvents
class SpaceTest : IntegrationTestSupport() {

	@Autowired
	private lateinit var events: ApplicationEvents

	private fun expectField(result: ResultActions, index: Int, field: String, reason: String): ResultActions =
		result
			.andExpect(jsonPath("$.details.fields[$index].field").value(field))
			.andExpect(jsonPath("$.details.fields[$index].reason").value(reason))

	/** 구성원을 `userId` → 구성원 객체로 읽는다 */
	private fun membersOf(body: String): Map<String, Map<String, Any?>> =
		JsonPath.read<List<Map<String, Any?>>>(body, "$.members").associateBy { it["userId"] as String }

	private fun startDateOf(spaceId: UUID): LocalDate? =
		jdbc.queryForObject("select start_date from spaces where id = ?", java.sql.Date::class.java, spaceId)?.toLocalDate()

	private fun colorOf(spaceId: UUID, userId: UUID): String =
		jdbc.queryForObject("select color from space_members where space_id = ? and user_id = ?", String::class.java, spaceId, userId)!!

	private fun togetherColorOf(spaceId: UUID): String? =
		jdbc.queryForObject("select together_color from spaces where id = ?", String::class.java, spaceId)

	private fun personalSpaceOf(userId: UUID): UUID =
		jdbc.queryForObject("select space_id from space_members where user_id = ? and left_at is null", UUID::class.java, userId)!!

	private fun updatedCount(): Int = events.stream(SpaceUpdated::class.java).count().toInt()

	/** 연결된 커플 한 쌍: A("에이", 주황)와 B("비비", 파랑) */
	private class Couple(val a: Tokens, val b: Tokens, val spaceId: UUID)

	private fun newCouple(): Couple {
		val a = startMember("에이")
		val b = startMember("비비")
		return Couple(a, b, connect(a, b))
	}

	// ── GET /space ──────────────────────────────────────

	@Test
	fun `R-01 GET space는 요청자의 활성 공간을 돌려준다`() {
		val guest = startGuest()
		val spaceId = personalSpaceOf(guest.userId)

		getSpace(guest.accessToken)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.id").value(spaceId.toString()))
			.andExpect(jsonPath("$.type").value("PERSONAL"))
	}

	@Test
	fun `FR-CAL-04 AC5 개인 공간의 GET space는 내 색 하나(pick-orange)이고 togetherColor는 null이다`() {
		val guest = startGuest()

		getSpace(guest.accessToken)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.members.length()").value(1))
			.andExpect(jsonPath("$.members[0].userId").value(guest.userId.toString()))
			.andExpect(jsonPath("$.members[0].nickname").value("게스트"))
			.andExpect(jsonPath("$.members[0].color").value("pick-orange"))
			.andExpect(jsonPath("$.members[0].hasProfileImage").value(false))
			// 값이 없는 필드는 빼지 않고 null로 보낸다 (1.1)
			.andExpect(content().string(containsString("\"togetherColor\":null")))
			.andExpect(content().string(containsString("\"startDate\":null")))
	}

	@Test
	fun `FR-SET-01 GET space는 구성원의 ID·닉네임·색·프로필 사진 여부와 처음 만난 날·함께 색을 돌려준다`() {
		val couple = newCouple()
		jdbc.update("update spaces set start_date = ? where id = ?", java.sql.Date.valueOf(LocalDate.of(2024, 5, 1)), couple.spaceId)
		jdbc.update("update users set profile_image_key = 'fake/b.jpg' where id = ?", couple.b.userId)

		val body = bodyOf(
			getSpace(couple.a.accessToken)
				.andExpect(status().isOk)
				.andExpect(jsonPath("$.id").value(couple.spaceId.toString()))
				.andExpect(jsonPath("$.type").value("COUPLE"))
				.andExpect(jsonPath("$.togetherColor").value("pick-purple"))
				.andExpect(jsonPath("$.startDate").value("2024-05-01"))
				.andExpect(jsonPath("$.members.length()").value(2)),
		)

		val members = membersOf(body)
		val memberA = members.getValue(couple.a.userId.toString())
		assertEquals("에이", memberA["nickname"])
		assertEquals("pick-orange", memberA["color"])
		assertEquals(false, memberA["hasProfileImage"])
		val memberB = members.getValue(couple.b.userId.toString())
		assertEquals("비비", memberB["nickname"])
		assertEquals("pick-blue", memberB["color"])
		assertEquals(true, memberB["hasProfileImage"])
	}

	@Test
	fun `FR-CAL-04 AC1 커플 공간의 GET space는 두 구성원의 색과 함께 색을 돌려준다`() {
		val couple = newCouple()

		// 두 사람이 같은 응답을 받는다 (구성원 순서는 뜻이 없어 userId로 찾는다)
		listOf(couple.a, couple.b).forEach { who ->
			val body = bodyOf(getSpace(who.accessToken).andExpect(status().isOk))
			val members = membersOf(body)
			assertEquals("pick-orange", members.getValue(couple.a.userId.toString())["color"])
			assertEquals("pick-blue", members.getValue(couple.b.userId.toString())["color"])
			assertEquals("pick-purple", JsonPath.read<String>(body, "$.togetherColor"))
		}
	}

	@Test
	fun `R-01 해제된 이전 커플 공간은 GET space에 나오지 않는다`() {
		val member = startMember()
		val partner = startMember()
		val old = insertSpace("COUPLE", unlinked = true)
		insertMember(old, member.userId, "pick-teal", left = true)
		insertMember(old, partner.userId, "pick-brown", left = true)
		val personal = personalSpaceOf(member.userId)

		val body = bodyOf(getSpace(member.accessToken).andExpect(status().isOk))

		assertEquals(personal.toString(), JsonPath.read<String>(body, "$.id"))
		assertEquals("PERSONAL", JsonPath.read<String>(body, "$.type"))
		assertEquals(listOf(member.userId.toString()), membersOf(body).keys.toList())
	}

	@Test
	fun `FR-AUTH-07 AC2 닉네임을 바꾸면 상대의 GET space 구성원 닉네임에 반영된다`() {
		val couple = newCouple()

		patchMe(couple.b.accessToken, """{"nickname": "새이름"}""").andExpect(status().isOk)

		val members = membersOf(bodyOf(getSpace(couple.a.accessToken).andExpect(status().isOk)))
		assertEquals("새이름", members.getValue(couple.b.userId.toString())["nickname"])
	}

	@Test
	fun `FR-SET-01 space 경로는 access 토큰이 필요하다`() {
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("$API/space"))
			.andExpect(status().isUnauthorized)
			.andExpect(jsonPath("$.code").value("SESSION_INVALID"))
	}

	// ── PATCH /space: 처음 만난 날 ───────────────────────

	@Test
	fun `FR-COUPLE-04 AC1 커플의 누구든 PATCH space로 처음 만난 날을 정하면 두 사람의 GET space와 GET me에 반영된다`() {
		val couple = newCouple()

		patchSpace(couple.a.accessToken, """{"startDate": "2024-05-01"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.id").value(couple.spaceId.toString()))
			.andExpect(jsonPath("$.type").value("COUPLE"))
			.andExpect(jsonPath("$.startDate").value("2024-05-01"))
			.andExpect(jsonPath("$.togetherColor").value("pick-purple"))
			.andExpect(jsonPath("$.members.length()").value(2))

		assertEquals(LocalDate.of(2024, 5, 1), startDateOf(couple.spaceId))
		listOf(couple.a, couple.b).forEach { who ->
			getSpace(who.accessToken).andExpect(jsonPath("$.startDate").value("2024-05-01"))
			getMe(who.accessToken).andExpect(jsonPath("$.space.startDate").value("2024-05-01"))
		}
		assertEquals(1, updatedCount())
	}

	@Test
	fun `FR-COUPLE-04 AC1 상대가 정한 처음 만난 날을 다른 쪽이 고칠 수 있다`() {
		val couple = newCouple()
		patchSpace(couple.a.accessToken, """{"startDate": "2024-05-01"}""").andExpect(status().isOk)

		patchSpace(couple.b.accessToken, """{"startDate": "2023-12-25"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.startDate").value("2023-12-25"))

		getSpace(couple.a.accessToken).andExpect(jsonPath("$.startDate").value("2023-12-25"))
	}

	@Test
	fun `FR-COUPLE-04 AC2 처음 만난 날을 정하지 않은 커플의 startDate는 null이다`() {
		val couple = newCouple()

		getSpace(couple.a.accessToken).andExpect(content().string(containsString("\"startDate\":null")))
		getMe(couple.a.accessToken).andExpect(content().string(containsString("\"startDate\":null")))
		assertEquals(null, startDateOf(couple.spaceId))
	}

	@Test
	fun `FR-COUPLE-04 AC3 요청자 시간대 기준 오늘보다 뒤의 날짜는 400 VALIDATION_FAILED (startDate INVALID)`() {
		val couple = newCouple()

		// 시계는 2026-10-26T00:00Z, 서울은 같은 날 오전 9시. 내일부터 미래다
		expectField(patchSpace(couple.a.accessToken, """{"startDate": "2026-10-27"}""").expectError(400, "VALIDATION_FAILED"), 0, "startDate", "INVALID")

		assertEquals(null, startDateOf(couple.spaceId))
		assertEquals(0, updatedCount())
	}

	@Test
	fun `FR-COUPLE-04 AC3 2050-12-31보다 뒤의 날짜도 VALIDATION_FAILED다`() {
		val couple = newCouple()

		// 오늘보다 뒤이면 범위와 관계없이 VALIDATION_FAILED이고 DATE_OUT_OF_RANGE가 아니다 (R-15 예외)
		listOf("2051-01-01", "9999-12-31").forEach { date ->
			expectField(patchSpace(couple.a.accessToken, """{"startDate": "$date"}""").expectError(400, "VALIDATION_FAILED"), 0, "startDate", "INVALID")
		}
		assertEquals(null, startDateOf(couple.spaceId))
	}

	@Test
	fun `FR-COUPLE-04 AC3 1950-01-01보다 앞의 날짜는 400 DATE_OUT_OF_RANGE (startDate OUT_OF_RANGE)`() {
		val couple = newCouple()

		expectField(patchSpace(couple.a.accessToken, """{"startDate": "1949-12-31"}""").expectError(400, "DATE_OUT_OF_RANGE"), 0, "startDate", "OUT_OF_RANGE")
		expectField(patchSpace(couple.a.accessToken, """{"startDate": "0001-01-01"}""").expectError(400, "DATE_OUT_OF_RANGE"), 0, "startDate", "OUT_OF_RANGE")

		assertEquals(null, startDateOf(couple.spaceId))
	}

	@Test
	fun `FR-COUPLE-04 AC3 1950-01-01과 오늘은 허용한다`() {
		val couple = newCouple()

		patchSpace(couple.a.accessToken, """{"startDate": "1950-01-01"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.startDate").value("1950-01-01"))
		patchSpace(couple.a.accessToken, """{"startDate": "2026-10-26"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.startDate").value("2026-10-26"))
	}

	@Test
	fun `FR-COUPLE-04 AC3 오늘은 서버 시간대가 아니라 요청자 users time_zone 기준이다`() {
		// UTC 2026-10-26 15:30 = 서울 2026-10-27 00:30. 토큰은 이 시계로 만든다
		clock.set(Instant.parse("2026-10-26T15:30:00Z"))
		val couple = newCouple()
		patchMe(couple.b.accessToken, """{"timeZone": "UTC"}""").andExpect(status().isOk)

		// 서울 시간대 사용자에게 오늘은 10월 27일이다
		patchSpace(couple.a.accessToken, """{"startDate": "2026-10-27"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.startDate").value("2026-10-27"))
		// UTC 시간대 사용자에게 10월 27일은 미래다
		expectField(patchSpace(couple.b.accessToken, """{"startDate": "2026-10-27"}""").expectError(400, "VALIDATION_FAILED"), 0, "startDate", "INVALID")
		patchSpace(couple.b.accessToken, """{"startDate": "2026-10-26"}""").andExpect(status().isOk)
		// 서울 시간대 사용자에게도 10월 28일은 미래다
		patchSpace(couple.a.accessToken, """{"startDate": "2026-10-28"}""").expectError(400, "VALIDATION_FAILED")
	}

	@Test
	fun `FR-COUPLE-04 AC3 startDate가 없거나 null이면 400 VALIDATION_FAILED (startDate REQUIRED)이고 정한 뒤에도 지울 수 없다`() {
		val couple = newCouple()
		patchSpace(couple.a.accessToken, """{"startDate": "2024-05-01"}""").andExpect(status().isOk)

		listOf("{}", """{"startDate": null}""", """{"other": 1}""").forEach { json ->
			expectField(patchSpace(couple.a.accessToken, json).expectError(400, "VALIDATION_FAILED"), 0, "startDate", "REQUIRED")
		}

		// 지우기는 없다: 처음 만난 날은 그대로
		assertEquals(LocalDate.of(2024, 5, 1), startDateOf(couple.spaceId))
	}

	@Test
	fun `FR-COUPLE-04 AC3 날짜 형식이 아니면 400 VALIDATION_FAILED (startDate INVALID)`() {
		val couple = newCouple()

		listOf("\"abc\"", "\"2026-02-30\"", "\"2026/10/01\"", "\"2024-5-1\"", "20240501", "true").forEach { value ->
			expectField(patchSpace(couple.a.accessToken, """{"startDate": $value}""").expectError(400, "VALIDATION_FAILED"), 0, "startDate", "INVALID")
		}
		assertEquals(null, startDateOf(couple.spaceId))
	}

	@Test
	fun `R-08 커플 구성원은 처음 만난 날을 바꿀 수 있다`() {
		val couple = newCouple()

		patchSpace(couple.b.accessToken, """{"startDate": "2024-05-01"}""").andExpect(status().isOk)
		patchSpace(couple.a.accessToken, """{"startDate": "2024-05-02"}""").andExpect(status().isOk)

		assertEquals(LocalDate.of(2024, 5, 2), startDateOf(couple.spaceId))
	}

	@Test
	fun `R-08 개인 공간에서 PATCH space는 409 NOT_COUPLE이고 아무것도 바뀌지 않는다`() {
		val member = startMember()
		val guest = startGuest()
		val before = coupleSnapshot()

		patchSpace(member.accessToken, """{"startDate": "2024-05-01"}""").expectError(409, "NOT_COUPLE")
		patchSpace(guest.accessToken, """{"startDate": "2024-05-01"}""").expectError(409, "NOT_COUPLE")
		// 본문이 틀려도 R-08이 먼저다
		patchSpace(member.accessToken, "{}").expectError(409, "NOT_COUPLE")
		patchSpace(member.accessToken, """{"startDate": "2999-01-01"}""").expectError(409, "NOT_COUPLE")

		assertEquals(before, coupleSnapshot())
		assertEquals(0, updatedCount())
	}

	@Test
	fun `R-08 연결이 해제돼 개인 공간으로 돌아온 사용자의 PATCH space는 409 NOT_COUPLE`() {
		val member = startMember()
		val partner = startMember()
		val old = insertSpace("COUPLE", unlinked = true)
		insertMember(old, member.userId, "pick-teal", left = true)
		insertMember(old, partner.userId, "pick-brown", left = true)

		patchSpace(member.accessToken, """{"startDate": "2024-05-01"}""").expectError(409, "NOT_COUPLE")

		assertEquals(null, startDateOf(old))
	}

	// ── PATCH /space/colors ─────────────────────────────

	@Test
	fun `FR-CAL-04 AC2 커플의 누구든 PATCH space colors로 두 사람 색과 함께 색을 바꾸면 두 사람의 GET space와 GET me에 반영된다`() {
		val couple = newCouple()
		val request = """
			{"members": [{"userId": "${couple.a.userId}", "color": "pick-rose"}, {"userId": "${couple.b.userId}", "color": "pick-green"}],
			 "together": "pick-ink"}
		""".trimIndent()

		val body = bodyOf(
			// 상대(B)가 둘의 색을 모두 바꾼다
			patchSpaceColors(couple.b.accessToken, request)
				.andExpect(status().isOk)
				.andExpect(jsonPath("$.id").value(couple.spaceId.toString()))
				.andExpect(jsonPath("$.togetherColor").value("pick-ink")),
		)

		assertEquals("pick-rose", membersOf(body).getValue(couple.a.userId.toString())["color"])
		assertEquals("pick-green", membersOf(body).getValue(couple.b.userId.toString())["color"])
		listOf(couple.a, couple.b).forEach { who ->
			val members = membersOf(bodyOf(getSpace(who.accessToken)))
			assertEquals("pick-rose", members.getValue(couple.a.userId.toString())["color"])
			assertEquals("pick-green", members.getValue(couple.b.userId.toString())["color"])
		}
		getMe(couple.a.accessToken)
			.andExpect(jsonPath("$.space.myColor").value("pick-rose"))
			.andExpect(jsonPath("$.space.partner.color").value("pick-green"))
			.andExpect(jsonPath("$.space.togetherColor").value("pick-ink"))
		getMe(couple.b.accessToken)
			.andExpect(jsonPath("$.space.myColor").value("pick-green"))
			.andExpect(jsonPath("$.space.partner.color").value("pick-rose"))
		assertEquals(1, updatedCount())
	}

	@Test
	fun `FR-CAL-04 AC2 일부 필드만 보내면 보낸 것만 바뀌고 나머지는 그대로다`() {
		val couple = newCouple()

		patchSpaceColors(couple.a.accessToken, """{"together": "pick-teal"}""").andExpect(status().isOk)
		assertEquals("pick-teal", togetherColorOf(couple.spaceId))
		assertEquals("pick-orange", colorOf(couple.spaceId, couple.a.userId))
		assertEquals("pick-blue", colorOf(couple.spaceId, couple.b.userId))

		patchSpaceColors(couple.a.accessToken, """{"members": [{"userId": "${couple.b.userId}", "color": "pick-mustard"}]}""")
			.andExpect(status().isOk)
		assertEquals("pick-teal", togetherColorOf(couple.spaceId))
		assertEquals("pick-orange", colorOf(couple.spaceId, couple.a.userId))
		assertEquals("pick-mustard", colorOf(couple.spaceId, couple.b.userId))

		// members가 빈 배열이어도 together만 있으면 된다
		patchSpaceColors(couple.a.accessToken, """{"members": [], "together": "pick-gray"}""").andExpect(status().isOk)
		assertEquals("pick-gray", togetherColorOf(couple.spaceId))
	}

	@Test
	fun `FR-CAL-04 AC2 12색 팔레트 키는 모두 허용한다`() {
		val couple = newCouple()
		val palette = listOf(
			"pick-orange", "pick-rose", "pick-amber", "pick-mustard", "pick-green", "pick-teal",
			"pick-blue", "pick-purple", "pick-magenta", "pick-brown", "pick-gray", "pick-ink",
		)

		palette.forEach { key ->
			patchSpaceColors(couple.a.accessToken, """{"members": [{"userId": "${couple.a.userId}", "color": "$key"}], "together": "$key"}""")
				.andExpect(status().isOk)
			assertEquals(key, colorOf(couple.spaceId, couple.a.userId))
			assertEquals(key, togetherColorOf(couple.spaceId))
		}
	}

	@Test
	fun `FR-CAL-04 AC2 12색 팔레트 밖의 값은 400 VALIDATION_FAILED (INVALID)이고 아무것도 바뀌지 않는다`() {
		val couple = newCouple()
		val before = coupleSnapshot()

		expectField(
			patchSpaceColors(couple.a.accessToken, """{"members": [{"userId": "${couple.a.userId}", "color": "pick-pink"}]}""")
				.expectError(400, "VALIDATION_FAILED"),
			0, "members[0].color", "INVALID",
		)
		expectField(patchSpaceColors(couple.a.accessToken, """{"together": "red"}""").expectError(400, "VALIDATION_FAILED"), 0, "together", "INVALID")
		// 대문자·공백은 다른 키다
		expectField(
			patchSpaceColors(couple.a.accessToken, """{"members": [{"userId": "${couple.a.userId}", "color": "Pick-Rose"}]}""")
				.expectError(400, "VALIDATION_FAILED"),
			0, "members[0].color", "INVALID",
		)
		// 하나라도 틀리면 올바른 값도 바뀌지 않는다. 틀린 필드는 모두 알려 준다
		val both = patchSpaceColors(
			couple.a.accessToken,
			"""{"members": [{"userId": "${couple.a.userId}", "color": "pick-rose"}, {"userId": "${couple.b.userId}", "color": "nope"}], "together": "pick-ink"}""",
		).expectError(400, "VALIDATION_FAILED")
		expectField(both, 0, "members[1].color", "INVALID")

		assertEquals(before, coupleSnapshot())
		assertEquals(0, updatedCount())
	}

	@Test
	fun `R-01 PATCH space colors에 다른 공간 사용자의 userId를 보내면 400 VALIDATION_FAILED이고 그 사용자의 색은 바뀌지 않는다`() {
		val couple = newCouple()
		val other = newCouple()
		val unknownUserId = UUID.randomUUID()

		// 다른 커플 사용자와 존재하지 않는 사용자는 같은 응답이다 (존재를 드러내지 않는다, DB-05)
		val foreign = patchSpaceColors(couple.a.accessToken, """{"members": [{"userId": "${other.a.userId}", "color": "pick-rose"}]}""")
			.expectError(400, "VALIDATION_FAILED")
		val missing = patchSpaceColors(couple.a.accessToken, """{"members": [{"userId": "$unknownUserId", "color": "pick-rose"}]}""")
			.expectError(400, "VALIDATION_FAILED")
		expectField(foreign, 0, "members[0].userId", "INVALID")
		expectField(missing, 0, "members[0].userId", "INVALID")
		assertEquals(
			JsonPath.read<Any>(bodyOf(foreign), "$.details"),
			JsonPath.read<Any>(bodyOf(missing), "$.details"),
		)
		// UUID 형식이 아닌 값도 같다
		expectField(
			patchSpaceColors(couple.a.accessToken, """{"members": [{"userId": "not-a-uuid", "color": "pick-rose"}]}""").expectError(400, "VALIDATION_FAILED"),
			0, "members[0].userId", "INVALID",
		)

		assertEquals("pick-orange", colorOf(other.spaceId, other.a.userId))
		assertEquals("pick-blue", colorOf(other.spaceId, other.b.userId))
	}

	@Test
	fun `FR-CAL-04 AC2 같은 userId가 두 번이면 두 번째 원소가 400 VALIDATION_FAILED (members 1 userId INVALID)`() {
		val couple = newCouple()
		val before = coupleSnapshot()

		expectField(
			patchSpaceColors(
				couple.a.accessToken,
				"""{"members": [{"userId": "${couple.a.userId}", "color": "pick-rose"}, {"userId": "${couple.a.userId}", "color": "pick-green"}]}""",
			).expectError(400, "VALIDATION_FAILED"),
			0, "members[1].userId", "INVALID",
		)

		assertEquals(before, coupleSnapshot())
	}

	@Test
	fun `FR-CAL-04 AC2 보낼 것이 없거나 null 필드가 있으면 400 VALIDATION_FAILED (REQUIRED)`() {
		val couple = newCouple()
		val before = coupleSnapshot()

		// members와 together가 둘 다 없거나, members가 빈 배열이고 together도 없음
		listOf("{}", """{"members": []}""").forEach { json ->
			expectField(patchSpaceColors(couple.a.accessToken, json).expectError(400, "VALIDATION_FAILED"), 0, "members", "REQUIRED")
		}
		expectField(patchSpaceColors(couple.a.accessToken, """{"members": null, "together": "pick-rose"}""").expectError(400, "VALIDATION_FAILED"), 0, "members", "REQUIRED")
		expectField(patchSpaceColors(couple.a.accessToken, """{"together": null}""").expectError(400, "VALIDATION_FAILED"), 0, "together", "REQUIRED")
		expectField(
			patchSpaceColors(couple.a.accessToken, """{"members": [{"userId": null, "color": "pick-rose"}]}""").expectError(400, "VALIDATION_FAILED"),
			0, "members[0].userId", "REQUIRED",
		)
		expectField(
			patchSpaceColors(couple.a.accessToken, """{"members": [{"userId": "${couple.a.userId}", "color": null}]}""").expectError(400, "VALIDATION_FAILED"),
			0, "members[0].color", "REQUIRED",
		)
		expectField(
			patchSpaceColors(couple.a.accessToken, """{"members": [{"color": "pick-rose"}]}""").expectError(400, "VALIDATION_FAILED"),
			0, "members[0].userId", "REQUIRED",
		)

		assertEquals(before, coupleSnapshot())
	}

	@Test
	fun `FR-CAL-04 AC4 두 대상이 같은 색이어도 저장된다`() {
		val couple = newCouple()

		patchSpaceColors(
			couple.a.accessToken,
			"""{"members": [{"userId": "${couple.a.userId}", "color": "pick-green"}, {"userId": "${couple.b.userId}", "color": "pick-green"}], "together": "pick-green"}""",
		).andExpect(status().isOk)

		assertEquals("pick-green", colorOf(couple.spaceId, couple.a.userId))
		assertEquals("pick-green", colorOf(couple.spaceId, couple.b.userId))
		assertEquals("pick-green", togetherColorOf(couple.spaceId))
	}

	@Test
	fun `R-08 커플 구성원은 상대 색과 함께 색도 바꿀 수 있다`() {
		val couple = newCouple()

		patchSpaceColors(
			couple.a.accessToken,
			"""{"members": [{"userId": "${couple.b.userId}", "color": "pick-magenta"}], "together": "pick-brown"}""",
		).andExpect(status().isOk)

		assertEquals("pick-magenta", colorOf(couple.spaceId, couple.b.userId))
		assertEquals("pick-brown", togetherColorOf(couple.spaceId))
	}

	@Test
	fun `FR-CAL-04 AC5 개인 공간에서도 내 색은 바꿀 수 있다`() {
		val guest = startGuest()
		val member = startMember()

		listOf(guest, member).forEach { who ->
			val body = bodyOf(
				patchSpaceColors(who.accessToken, """{"members": [{"userId": "${who.userId}", "color": "pick-teal"}]}""")
					.andExpect(status().isOk)
					.andExpect(jsonPath("$.type").value("PERSONAL"))
					.andExpect(content().string(containsString("\"togetherColor\":null"))),
			)
			assertEquals("pick-teal", membersOf(body).getValue(who.userId.toString())["color"])
			getMe(who.accessToken).andExpect(jsonPath("$.space.myColor").value("pick-teal"))
		}
	}

	@Test
	fun `R-08 개인 공간에서는 자기 색을 바꿀 수 있다`() {
		val member = startMember()

		patchSpaceColors(member.accessToken, """{"members": [{"userId": "${member.userId}", "color": "pick-rose"}]}""")
			.andExpect(status().isOk)

		assertEquals("pick-rose", colorOf(personalSpaceOf(member.userId), member.userId))
	}

	@Test
	fun `R-08 개인 공간에서 together 색을 보내면 409 NOT_COUPLE이고 아무것도 바뀌지 않는다`() {
		val member = startMember()
		val other = startMember()
		val before = coupleSnapshot()

		patchSpaceColors(member.accessToken, """{"together": "pick-rose"}""").expectError(409, "NOT_COUPLE")
		patchSpaceColors(member.accessToken, """{"members": [{"userId": "${member.userId}", "color": "pick-rose"}], "together": "pick-ink"}""")
			.expectError(409, "NOT_COUPLE")
		// 자기 밖의 userId도 같다 (다른 공간 사용자라도 400이 아니라 409다)
		patchSpaceColors(member.accessToken, """{"members": [{"userId": "${other.userId}", "color": "pick-rose"}]}""")
			.expectError(409, "NOT_COUPLE")
		patchSpaceColors(member.accessToken, """{"members": [{"userId": "${member.userId}", "color": "pick-rose"}, {"userId": "${other.userId}", "color": "pick-green"}]}""")
			.expectError(409, "NOT_COUPLE")

		assertEquals(before, coupleSnapshot())
		assertEquals(0, updatedCount())
	}

	@Test
	fun `R-08 개인 공간이어도 필드 누락은 NOT_COUPLE보다 먼저 400 VALIDATION_FAILED다`() {
		val member = startMember()

		// 검사 순서 1단계(필드 누락)가 2단계(R-08)보다 먼저다 (API.md 3.2)
		expectField(patchSpaceColors(member.accessToken, "{}").expectError(400, "VALIDATION_FAILED"), 0, "members", "REQUIRED")
	}

	@Test
	fun `R-08 개인 공간에서 members의 팔레트 밖 색은 400 VALIDATION_FAILED다`() {
		val member = startMember()

		expectField(
			patchSpaceColors(member.accessToken, """{"members": [{"userId": "${member.userId}", "color": "nope"}]}""").expectError(400, "VALIDATION_FAILED"),
			0, "members[0].color", "INVALID",
		)
		assertNotEquals("nope", colorOf(personalSpaceOf(member.userId), member.userId))
	}
}
