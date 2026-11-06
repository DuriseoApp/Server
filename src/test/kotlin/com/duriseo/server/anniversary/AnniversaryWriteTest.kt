package com.duriseo.server.anniversary

import com.duriseo.server.TestcontainersConfig
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
import kotlin.test.assertTrue

/**
 * `POST`·`PATCH /anniversaries`의 값 규칙(FR-ANNIV-03, D-104, R-15), 권한(R-01·R-05), 검사 순서, 도메인 이벤트를 확인한다.
 * 시각은 `MutableClock`이 `2026-10-26T00:00:00Z`로 고정한다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class AnniversaryWriteTest : AnniversaryTestSupport() {

	// ── POST: FR-ANNIV-03 AC1 ───────────────────────────

	@Test
	fun `FR-ANNIV-03 AC1 POST는 201과 Anniversary를 돌려주고 repeatsYearly를 생략하면 true다`() {
		val couple = newCouple()

		val result = postAnniversaryRaw(couple.a.accessToken, anniversaryJson(name = "100일 여행", date = "2026-11-03"))
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.name").value("100일 여행"))
			.andExpect(jsonPath("$.date").value("2026-11-03"))
			.andExpect(jsonPath("$.repeatsYearly").value(true))
			.andExpect(jsonPath("$.authorId").value(couple.a.userId.toString()))
			.andExpect(jsonPath("$.createdAt").value("2026-10-26T00:00:00Z"))
			.andExpect(jsonPath("$.updatedAt").value("2026-10-26T00:00:00Z"))
			.andExpect(jsonPath("$.updatedBy").doesNotExist())

		val id = UUID.fromString(JsonPath.read<String>(bodyOf(result), "$.id"))
		val row = anniversaryRow(id)
		assertEquals(couple.spaceId, row["space_id"])
		assertEquals(couple.a.userId, row["author_id"])
		assertEquals(true, row["repeats_yearly"])
	}

	@Test
	fun `FR-ANNIV-03 AC1 개인 공간에서도 만들 수 있고 repeatsYearly false를 저장한다`() {
		val me = startMember("에이")

		val id = createAnniversary(me.accessToken, anniversaryJson(date = "2026-12-25", extra = ""","repeatsYearly":false"""))

		val row = anniversaryRow(id)
		assertEquals(activeSpaceIdOfUser(me.userId), row["space_id"])
		assertEquals(false, row["repeats_yearly"])
	}

	@Test
	fun `FR-ANNIV-03 AC1 만든 기념일은 upcoming에 보인다`() {
		val couple = newCouple()
		val id = createAnniversary(couple.a.accessToken, anniversaryJson(name = "여행", date = "2026-11-03"))

		getAnniversaryList(couple.b.accessToken, "upcoming").andExpect(status().isOk)
			.andExpect(jsonPath("$.items[0].anniversaryId").value(id.toString()))
			.andExpect(jsonPath("$.items[0].name").value("여행"))
	}

	@Test
	fun `R-01 POST는 요청자의 활성 공간에 만든다 해제 뒤에는 개인 공간이다`() {
		val couple = newCouple()
		val inCouple = createAnniversary(couple.a.accessToken, anniversaryJson())
		unlinkCouple(couple.a.accessToken).andExpect(status().isNoContent)

		val afterUnlink = createAnniversary(couple.a.accessToken, anniversaryJson(name = "해제 뒤"))

		assertEquals(couple.spaceId, anniversaryRow(inCouple)["space_id"])
		assertEquals(activeSpaceIdOfUser(couple.a.userId), anniversaryRow(afterUnlink)["space_id"])
		assertNotEquals(couple.spaceId, anniversaryRow(afterUnlink)["space_id"])
	}

	@Test
	fun `FR-ANNIV-03 AC1 POST의 모르는 필드는 무시한다 spaceId와 authorId도 효과가 없다`() {
		val couple = newCouple()
		val other = newCouple()

		val id = createAnniversary(
			couple.a.accessToken,
			anniversaryJson(extra = ""","spaceId":"${other.spaceId}","authorId":"${couple.b.userId}","memo":"x""""),
		)

		val row = anniversaryRow(id)
		assertEquals(couple.spaceId, row["space_id"])
		assertEquals(couple.a.userId, row["author_id"])
	}

	// ── POST: D-104 이름 ────────────────────────────────

	@Test
	fun `D-104 이름은 앞뒤 공백을 지운 값으로 저장한다`() {
		val me = startMember()

		postAnniversaryRaw(me.accessToken, anniversaryJson(name = "  우리 기념일 \\t"))
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.name").value("우리 기념일"))

		assertEquals(listOf("우리 기념일"), jdbc.queryForList("select name from anniversaries", String::class.java))
	}

	@Test
	fun `D-104 공백만 있는 이름은 이름이 없는 것이라 400 REQUIRED다`() {
		val me = startMember()

		val result = postAnniversaryRaw(me.accessToken, anniversaryJson(name = "   ")).expectError(400, "VALIDATION_FAILED")

		expectField(result, 0, "name", "REQUIRED")
		assertEquals(0, anniversaryCount())
	}

	@Test
	fun `D-104 이름 길이는 공백을 지운 뒤 코드 포인트로 센다 30자는 되고 31자는 400 TOO_LONG이다`() {
		val me = startMember()
		val thirty = "가".repeat(30)
		// 이모지는 UTF-16으로 2칸이지만 코드 포인트 1개다
		val thirtyEmoji = "😀".repeat(30)

		postAnniversaryRaw(me.accessToken, anniversaryJson(name = " $thirty ")).andExpect(status().isCreated)
		postAnniversaryRaw(me.accessToken, anniversaryJson(name = thirtyEmoji)).andExpect(status().isCreated)
		val tooLong = postAnniversaryRaw(me.accessToken, anniversaryJson(name = thirty + "나")).expectError(400, "VALIDATION_FAILED")

		expectField(tooLong, 0, "name", "TOO_LONG")
		postAnniversaryRaw(me.accessToken, anniversaryJson(name = "😀".repeat(31))).expectError(400, "VALIDATION_FAILED")
		assertEquals(2, anniversaryCount())
	}

	// ── POST: 필수·형식 ─────────────────────────────────

	@Test
	fun `FR-ANNIV-03 AC1 POST는 name과 date가 없으면 400 REQUIRED이고 틀린 필드를 모두 담는다`() {
		val me = startMember()

		val empty = postAnniversaryRaw(me.accessToken, "{}").expectError(400, "VALIDATION_FAILED")
		val noBody = postAnniversaryRaw(me.accessToken, null).expectError(400, "VALIDATION_FAILED")
		val nulls = postAnniversaryRaw(me.accessToken, """{"name":null,"date":null}""").expectError(400, "VALIDATION_FAILED")

		for (result in listOf(empty, noBody, nulls)) {
			expectField(result, 0, "name", "REQUIRED")
			expectField(result, 1, "date", "REQUIRED")
		}
		assertEquals(0, anniversaryCount())
	}

	@Test
	fun `FR-ANNIV-03 AC1 repeatsYearly가 null이면 REQUIRED이고 boolean이 아니면 INVALID다`() {
		val me = startMember()

		val nullValue = postAnniversaryRaw(me.accessToken, anniversaryJson(extra = ""","repeatsYearly":null""")).expectError(400, "VALIDATION_FAILED")
		val text = postAnniversaryRaw(me.accessToken, anniversaryJson(extra = ""","repeatsYearly":"true"""")).expectError(400, "VALIDATION_FAILED")

		expectField(nullValue, 0, "repeatsYearly", "REQUIRED")
		expectField(text, 0, "repeatsYearly", "INVALID")
		assertEquals(0, anniversaryCount())
	}

	@Test
	fun `FR-ANNIV-03 AC1 날짜가 YYYY-MM-DD가 아니면 400 INVALID다`() {
		val me = startMember()

		for (bad in listOf("2026-13-01", "2026-02-30", "20261101", "2026-1-1", "내일", "2026-11-03T00:00:00Z")) {
			val result = postAnniversaryRaw(me.accessToken, anniversaryJson(date = bad)).expectError(400, "VALIDATION_FAILED")
			expectField(result, 0, "date", "INVALID")
		}
		postAnniversaryRaw(me.accessToken, """{"name":"숫자","date":20261101}""").expectError(400, "VALIDATION_FAILED")
		assertEquals(0, anniversaryCount())
	}

	@Test
	fun `FR-ANNIV-03 AC1 틀린 필드가 여럿이면 details fields에 모두 담는다`() {
		val me = startMember()

		val result = postAnniversaryRaw(me.accessToken, """{"name":"  ","date":"x","repeatsYearly":1}""").expectError(400, "VALIDATION_FAILED")

		assertEquals(listOf("name", "date", "repeatsYearly"), fieldNamesOf(result))
	}

	// ── POST: R-15 ──────────────────────────────────────

	@Test
	fun `R-15 날짜가 1950-01-01 이전이거나 2050-12-31 이후이면 400 DATE_OUT_OF_RANGE다`() {
		val me = startMember()

		for (out in listOf("1949-12-31", "2051-01-01", "0001-01-01", "9999-12-31")) {
			val result = postAnniversaryRaw(me.accessToken, anniversaryJson(date = out)).expectError(400, "DATE_OUT_OF_RANGE")
			expectField(result, 0, "date", "OUT_OF_RANGE")
		}
		assertEquals(0, anniversaryCount())
	}

	@Test
	fun `R-15 경계 날짜 1950-01-01과 2050-12-31은 저장된다`() {
		val me = startMember()

		postAnniversaryRaw(me.accessToken, anniversaryJson(date = "1950-01-01")).andExpect(status().isCreated)
		postAnniversaryRaw(me.accessToken, anniversaryJson(date = "2050-12-31")).andExpect(status().isCreated)

		assertEquals(2, anniversaryCount())
	}

	@Test
	fun `R-15 VALIDATION_FAILED가 하나라도 있으면 그것만 응답하고 DATE_OUT_OF_RANGE는 그 뒤에 본다`() {
		val me = startMember()

		val both = postAnniversaryRaw(me.accessToken, anniversaryJson(name = " ", date = "2060-01-01")).expectError(400, "VALIDATION_FAILED")

		assertEquals(listOf("name"), fieldNamesOf(both))
	}

	// ── PATCH ───────────────────────────────────────────

	@Test
	fun `FR-ANNIV-03 PATCH는 보낸 필드만 바꾸고 200과 바뀐 Anniversary를 돌려준다`() {
		val couple = newCouple()
		val id = createAnniversary(couple.a.accessToken, anniversaryJson(name = "처음", date = "2026-11-03", extra = ""","repeatsYearly":false"""))
		clock.advance(Duration.ofMinutes(10))

		patchAnniversary(couple.a.accessToken, id, """{"name":"  바뀜 "}""").andExpect(status().isOk)
			.andExpect(jsonPath("$.id").value(id.toString()))
			.andExpect(jsonPath("$.name").value("바뀜"))
			.andExpect(jsonPath("$.date").value("2026-11-03"))
			.andExpect(jsonPath("$.repeatsYearly").value(false))
			.andExpect(jsonPath("$.authorId").value(couple.a.userId.toString()))
			.andExpect(jsonPath("$.createdAt").value("2026-10-26T00:00:00Z"))
			.andExpect(jsonPath("$.updatedAt").value("2026-10-26T00:10:00Z"))

		patchAnniversary(couple.a.accessToken, id, """{"date":"2027-01-02","repeatsYearly":true}""").andExpect(status().isOk)
			.andExpect(jsonPath("$.name").value("바뀜"))
			.andExpect(jsonPath("$.date").value("2027-01-02"))
			.andExpect(jsonPath("$.repeatsYearly").value(true))
		val row = anniversaryRow(id)
		assertEquals("2027-01-02", row["date"])
		assertEquals("바뀜", row["name"])
	}

	@Test
	fun `FR-ANNIV-03 PATCH의 모르는 필드는 무시한다`() {
		val couple = newCouple()
		val other = newCouple()
		val id = createAnniversary(couple.a.accessToken, anniversaryJson())

		patchAnniversary(couple.a.accessToken, id, """{"name":"새","spaceId":"${other.spaceId}","authorId":"${couple.b.userId}"}""")
			.andExpect(status().isOk)

		val row = anniversaryRow(id)
		assertEquals(couple.spaceId, row["space_id"])
		assertEquals(couple.a.userId, row["author_id"])
	}

	@Test
	fun `FR-ANNIV-03 PATCH는 어떤 필드든 null이면 400 REQUIRED이고 아무것도 바꾸지 않는다`() {
		val me = startMember()
		val id = createAnniversary(me.accessToken, anniversaryJson())
		val before = anniversarySnapshot()

		for (field in listOf("name", "date", "repeatsYearly")) {
			val result = patchAnniversary(me.accessToken, id, """{"$field":null}""").expectError(400, "VALIDATION_FAILED")
			expectField(result, 0, field, "REQUIRED")
		}
		assertEquals(before, anniversarySnapshot())
	}

	@Test
	fun `D-104 PATCH의 이름 규칙도 같다 공백만 있으면 REQUIRED 31자는 TOO_LONG`() {
		val me = startMember()
		val id = createAnniversary(me.accessToken, anniversaryJson())

		expectField(patchAnniversary(me.accessToken, id, """{"name":"  "}""").expectError(400, "VALIDATION_FAILED"), 0, "name", "REQUIRED")
		expectField(
			patchAnniversary(me.accessToken, id, """{"name":"${"가".repeat(31)}"}""").expectError(400, "VALIDATION_FAILED"),
			0, "name", "TOO_LONG",
		)
		patchAnniversary(me.accessToken, id, """{"name":"${"가".repeat(30)}"}""").andExpect(status().isOk)
	}

	@Test
	fun `R-15 PATCH의 날짜가 범위 밖이면 400 DATE_OUT_OF_RANGE이고 형식이 틀리면 VALIDATION_FAILED다`() {
		val me = startMember()
		val id = createAnniversary(me.accessToken, anniversaryJson(date = "2026-11-03"))
		val before = anniversarySnapshot()

		expectField(patchAnniversary(me.accessToken, id, """{"date":"2051-01-01"}""").expectError(400, "DATE_OUT_OF_RANGE"), 0, "date", "OUT_OF_RANGE")
		expectField(patchAnniversary(me.accessToken, id, """{"date":"1949-12-31"}""").expectError(400, "DATE_OUT_OF_RANGE"), 0, "date", "OUT_OF_RANGE")
		expectField(patchAnniversary(me.accessToken, id, """{"date":"2026/11/03"}""").expectError(400, "VALIDATION_FAILED"), 0, "date", "INVALID")
		// 값 검사가 먼저다: 이름이 틀리면 날짜 범위 오류는 나가지 않는다
		val both = patchAnniversary(me.accessToken, id, """{"name":" ","date":"2051-01-01"}""").expectError(400, "VALIDATION_FAILED")
		assertEquals(listOf("name"), fieldNamesOf(both))
		patchAnniversary(me.accessToken, id, """{"date":"2050-12-31"}""").andExpect(status().isOk)

		assertTrue(before != anniversarySnapshot())
		assertEquals("2050-12-31", anniversaryRow(id)["date"])
	}

	@Test
	fun `FR-ANNIV-03 PATCH로 저장된 값과 같은 값만 보내면 아무것도 바꾸지 않고 이벤트도 없다`() {
		val me = startMember()
		val id = createAnniversary(me.accessToken, anniversaryJson(name = "그대로", date = "2026-11-03"))
		anniversaryChanges.reset()
		clock.advance(Duration.ofMinutes(10))
		val before = anniversarySnapshot()

		patchAnniversary(me.accessToken, id, "{}").andExpect(status().isOk).andExpect(jsonPath("$.updatedAt").value("2026-10-26T00:00:00Z"))
		patchAnniversary(me.accessToken, id, """{"name":" 그대로 ","date":"2026-11-03","repeatsYearly":true}""").andExpect(status().isOk)

		assertEquals(before, anniversarySnapshot())
		assertTrue(anniversaryChanges.updated.isEmpty())
	}

	// ── PATCH: R-01·R-05와 검사 순서 ────────────────────

	@Test
	fun `R-05 작성자는 PATCH할 수 있고 상대가 만든 기념일을 PATCH하면 403 NOT_AUTHOR이며 아무것도 바뀌지 않는다`() {
		val couple = newCouple()
		val id = createAnniversary(couple.a.accessToken, anniversaryJson(name = "에이의 것"))
		val before = anniversarySnapshot()

		patchAnniversary(couple.b.accessToken, id, """{"name":"가로챔"}""").expectError(403, "NOT_AUTHOR")

		assertEquals(before, anniversarySnapshot())
		patchAnniversary(couple.a.accessToken, id, """{"name":"내가 고침"}""").andExpect(status().isOk)
		assertEquals("내가 고침", anniversaryRow(id)["name"])
	}

	@Test
	fun `R-01 다른 공간의 기념일과 없는 ID와 UUID 형식이 아닌 ID는 PATCH가 같은 404 NOT_FOUND다`() {
		val couple = newCouple()
		val other = newCouple()
		val foreign = insertAnniversary(other.spaceId, other.a.userId)
		val before = anniversarySnapshot()

		val shapes = listOf(foreign, UUID.randomUUID(), "not-a-uuid").map { id ->
			val body = bodyOf(patchAnniversary(couple.a.accessToken, id, """{"name":"x"}""").expectError(404, "NOT_FOUND"))
			listOf(JsonPath.read<String>(body, "$.code"), JsonPath.read<String>(body, "$.message"), JsonPath.read<Any?>(body, "$.details"))
		}

		assertEquals(shapes[0], shapes[1])
		assertEquals(shapes[0], shapes[2])
		assertEquals(before, anniversarySnapshot())
	}

	@Test
	fun `R-05 PATCH 검사 순서는 404 다음 403 다음 값 검사이다 권한 없는 사용자에게 필드 오류를 보여주지 않는다`() {
		val couple = newCouple()
		val other = newCouple()
		val mine = createAnniversary(couple.a.accessToken, anniversaryJson())
		val foreign = insertAnniversary(other.spaceId, other.a.userId)
		val invalid = """{"name":" ","date":"2060-01-01"}"""

		// 다른 공간이면 본문이 틀려도 404, 같은 공간의 남의 것이면 본문이 틀려도 403, 작성자만 400을 본다
		patchAnniversary(couple.b.accessToken, foreign, invalid).expectError(404, "NOT_FOUND")
		patchAnniversary(couple.b.accessToken, mine, invalid).expectError(403, "NOT_AUTHOR")
		patchAnniversary(couple.a.accessToken, mine, invalid).expectError(400, "VALIDATION_FAILED")
	}

	@Test
	fun `R-01 해제된 이전 커플 공간의 기념일은 PATCH가 404다`() {
		val couple = newCouple()
		val id = createAnniversary(couple.a.accessToken, anniversaryJson(name = "해제 전"))
		unlinkCouple(couple.a.accessToken).andExpect(status().isNoContent)

		patchAnniversary(couple.a.accessToken, id, """{"name":"해제 뒤"}""").expectError(404, "NOT_FOUND")

		assertEquals("해제 전", anniversaryRow(id)["name"])
	}

	// ── 도메인 이벤트: 커밋 뒤에만 전달 ───────────────────

	@Test
	fun `NFR-SYNC-01 기념일 생성 이벤트는 커밋된 뒤에만 리스너에 전달된다`() {
		val couple = newCouple()
		val rowsSeen = AtomicInteger(-1)
		anniversaryChanges.onCreated = { created ->
			rowsSeen.set(committedCountOf("select count(*) from anniversaries where id = ?", created.anniversaryId))
		}

		val id = createAnniversary(couple.a.accessToken, anniversaryJson())

		val created = anniversaryChanges.created.single()
		assertEquals(id, created.anniversaryId)
		assertEquals(couple.spaceId, created.spaceId)
		assertEquals(couple.a.userId, created.createdByUserId)
		assertEquals(1, rowsSeen.get())
	}

	@Test
	fun `NFR-SYNC-01 기념일 수정 이벤트는 커밋된 뒤 수정한 사람과 함께 전달된다`() {
		val couple = newCouple()
		val id = createAnniversary(couple.a.accessToken, anniversaryJson(name = "전"))
		val nameSeen = AtomicInteger(-1)
		anniversaryChanges.onUpdated = { updated ->
			nameSeen.set(committedCountOf("select count(*) from anniversaries where id = ? and name = '후'", updated.anniversaryId))
		}

		patchAnniversary(couple.a.accessToken, id, """{"name":"후"}""").andExpect(status().isOk)

		val updated = anniversaryChanges.updated.single()
		assertEquals(id, updated.anniversaryId)
		assertEquals(couple.spaceId, updated.spaceId)
		assertEquals(couple.a.userId, updated.changedByUserId)
		assertEquals(1, nameSeen.get())
	}

	@Test
	fun `NFR-SYNC-01 기념일 삭제 이벤트는 커밋된 뒤 전달되고 거부된 요청은 이벤트가 없다`() {
		val couple = newCouple()
		val id = createAnniversary(couple.a.accessToken, anniversaryJson())
		anniversaryChanges.reset()

		deleteAnniversary(couple.b.accessToken, id).expectError(403, "NOT_AUTHOR")
		postAnniversaryRaw(couple.a.accessToken, anniversaryJson(name = " ")).expectError(400, "VALIDATION_FAILED")
		patchAnniversary(couple.a.accessToken, id, """{"date":"2060-01-01"}""").expectError(400, "DATE_OUT_OF_RANGE")
		assertTrue(anniversaryChanges.created.isEmpty() && anniversaryChanges.updated.isEmpty() && anniversaryChanges.deleted.isEmpty())

		deleteAnniversary(couple.a.accessToken, id).andExpect(status().isNoContent)

		val deleted = anniversaryChanges.deleted.single()
		assertEquals(id, deleted.anniversaryId)
		assertEquals(couple.a.userId, deleted.deletedByUserId)
	}

	// ── 동시성 ──────────────────────────────────────────

	@Test
	fun `R-01 기념일을 만드는 요청과 커플 연결이 동시에 와도 기념일이 사라지거나 500이 나지 않는다`() {
		repeat(5) {
			val a = startMember("에이")
			val b = startMember("비비")
			val code = JsonPath.read<String>(bodyOf(postInviteCode(a.accessToken).andExpect(status().isCreated)), "$.code")
			val personalA = activeSpaceIdOfUser(a.userId)

			val results = inParallel(
				listOf(
					{ statusOf(postAnniversaryRaw(a.accessToken, anniversaryJson(name = "동시 1"))) },
					{ statusOf(joinCouple(b.accessToken, code)) },
					{ statusOf(postAnniversaryRaw(a.accessToken, anniversaryJson(name = "동시 2"))) },
				),
			)

			assertEquals(listOf(201, 200, 201), results)
			// 두 기념일 모두 남아 있고 존재하는 공간에 속한다: 연결이 앞서면 커플 공간에, 뒤서면 가져오기를 기다리는 개인 공간에 있다
			assertEquals(2, count("select count(*) from anniversaries where author_id = ?", a.userId))
			assertEquals(
				2,
				count("select count(*) from anniversaries n join spaces s on s.id = n.space_id where n.author_id = ?", a.userId),
			)
			val coupleSpace = activeSpaceIdOfUser(a.userId)
			assertEquals(
				2,
				count("select count(*) from anniversaries where author_id = ? and space_id in (?, ?)", a.userId, coupleSpace, personalA),
			)
		}
	}
}
