package com.duriseo.server.memo

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.TestClockConfig
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `POST`·`PATCH`·`DELETE /memos`와 `GET /memos/{id}`의 값 규칙 (FR-MEMO-02·03, API.md 3.4 "메모 쓰기 규칙", D-132, D-123).
 * 권한(R-01·R-03)은 `MemoPermissionTest`, 날짜 범위(R-15)는 `MemoDateRangeTest`, 알림 행은 `MemoPartnerNotificationTest`가 확인한다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class MemoWriteTest : MemoTestSupport() {

	// ── FR-MEMO-02 AC1 ───────────────────────────────────

	@Test
	fun `FR-MEMO-02 AC1 제목만 있어도 본문만 있어도 201이고 공간·작성자가 요청자 기준이다`() {
		val couple = newCouple()

		val titleOnly = createMemo(couple.a.accessToken, """{"title":"장보기"}""")
		val bodyOnly = createMemo(couple.b.accessToken, """{"body":"우유 사기"}""")

		val first = memoRow(titleOnly)
		assertEquals("장보기", first["title"])
		assertNull(first["body"])
		assertEquals(couple.a.userId, first["author_id"])
		assertEquals(couple.spaceId, first["space_id"])
		val second = memoRow(bodyOnly)
		assertNull(second["title"])
		assertEquals("우유 사기", second["body"])
		assertEquals(couple.b.userId, second["author_id"])
		assertEquals(couple.spaceId, second["space_id"])
		assertEquals(1, memoChanges.created.count { it.memoId == titleOnly })
	}

	@Test
	fun `FR-MEMO-02 AC1 개인 공간에도 만들 수 있고 응답은 고정 즐겨찾기 없이 null 필드를 포함한다`() {
		val me = startMember("에이")

		postMemoRaw(me.accessToken, """{"title":"혼자 쓰는 메모"}""")
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.title").value("혼자 쓰는 메모"))
			.andExpect(jsonPath("$.body").value(null as String?))
			.andExpect(jsonPath("$.memoDate").value(null as String?))
			.andExpect(jsonPath("$.authorId").value(me.userId.toString()))
			.andExpect(jsonPath("$.isPinned").value(false))
			.andExpect(jsonPath("$.pinnedAt").value(null as String?))
			.andExpect(jsonPath("$.isFavorite").value(false))
			.andExpect(jsonPath("$.createdAt").value("2026-10-26T00:00:00Z"))
			.andExpect(jsonPath("$.updatedAt").value("2026-10-26T00:00:00Z"))
		assertEquals(activeSpaceIdOfUser(me.userId), jdbc.queryForObject("select space_id from memos", UUID::class.java))
	}

	// ── FR-MEMO-02 AC2 ───────────────────────────────────

	@Test
	fun `FR-MEMO-02 AC2 제목과 본문이 모두 비거나 공백뿐이면 400 VALIDATION_FAILED (title body REQUIRED)`() {
		val me = startMember()
		val bodies = listOf(
			"""{}""",
			"""{"title":null,"body":null}""",
			"""{"title":"","body":""}""",
			"""{"title":"   ","body":" \n\t "}""",
			"""{"memoDate":"2026-11-03"}""",
		)

		bodies.forEach { json ->
			val result = postMemoRaw(me.accessToken, json).andExpect(status().isBadRequest)
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			assertEquals(listOf("title" to "REQUIRED", "body" to "REQUIRED"), fieldsOf(result), json)
		}
		postMemoRaw(me.accessToken, null).andExpect(status().isBadRequest)
		assertEquals(0, memoCount())
	}

	@Test
	fun `FR-MEMO-02 AC2 제목은 앞뒤 공백을 지워 저장하고 본문은 공백과 줄바꿈을 그대로 저장한다`() {
		val me = startMember()

		val id = createMemo(me.accessToken, """{"title":"  장보기  ","body":"\n  우유\n\n  달걀  \n"}""")

		val row = memoRow(id)
		assertEquals("장보기", row["title"])
		assertEquals("\n  우유\n\n  달걀  \n", row["body"])
		getMemo(me.accessToken, id).andExpect(status().isOk)
			.andExpect(jsonPath("$.title").value("장보기"))
			.andExpect(jsonPath("$.body").value("\n  우유\n\n  달걀  \n"))
	}

	@Test
	fun `FR-MEMO-02 AC2 공백뿐인 본문은 null로 저장한다`() {
		val me = startMember()

		val id = createMemo(me.accessToken, """{"title":"제목","body":"  \n \t "}""")

		assertNull(memoRow(id)["body"])
		getMemo(me.accessToken, id).andExpect(jsonPath("$.body").value(null as String?))
	}

	@Test
	fun `FR-MEMO-02 AC2 제목 51자나 본문 5001자는 TOO_LONG이고 코드 포인트로 센다`() {
		val me = startMember()
		// 이모지는 UTF-16으로 두 칸이지만 코드 포인트 하나다
		val title50 = "😀".repeat(50)
		val body5000 = "😀".repeat(5000)

		createMemo(me.accessToken, """{"title":"$title50","body":"$body5000"}""")
		createMemo(me.accessToken, """{"title":"  ${"가".repeat(50)}  "}""")

		val tooLongTitle = postMemoRaw(me.accessToken, """{"title":"${"😀".repeat(51)}"}""").andExpect(status().isBadRequest)
		assertEquals(listOf("title" to "TOO_LONG"), fieldsOf(tooLongTitle))
		val tooLongBody = postMemoRaw(me.accessToken, """{"body":"${"😀".repeat(5001)}"}""").andExpect(status().isBadRequest)
		assertEquals(listOf("body" to "TOO_LONG"), fieldsOf(tooLongBody))
		val both = postMemoRaw(me.accessToken, """{"title":"${"가".repeat(51)}","body":"${"a".repeat(5001)}"}""")
			.andExpect(status().isBadRequest)
		assertEquals(listOf("title" to "TOO_LONG", "body" to "TOO_LONG"), fieldsOf(both))
		assertEquals(2, memoCount())
	}

	@Test
	fun `FR-MEMO-02 AC2 공백뿐인 본문은 5000자를 넘어도 빈 값으로 보고 TOO_LONG을 내지 않는다`() {
		val me = startMember()

		val id = createMemo(me.accessToken, """{"title":"제목","body":"${" ".repeat(6000)}"}""")

		assertNull(memoRow(id)["body"])
	}

	@Test
	fun `FR-MEMO-02 AC2 필드 타입이 틀리면 INVALID이고 notifyPartner null은 REQUIRED다`() {
		val me = startMember()

		val types = postMemoRaw(me.accessToken, """{"title":123,"body":true,"memoDate":20261103,"notifyPartner":"yes"}""")
			.andExpect(status().isBadRequest)
		assertEquals(
			listOf("title" to "INVALID", "body" to "INVALID", "memoDate" to "INVALID", "notifyPartner" to "INVALID"),
			fieldsOf(types),
		)
		val nullNotify = postMemoRaw(me.accessToken, """{"title":"제목","notifyPartner":null}""").andExpect(status().isBadRequest)
		assertEquals(listOf("notifyPartner" to "REQUIRED"), fieldsOf(nullNotify))
		assertEquals(0, memoCount())
		// 모르는 필드와 대상 사용자 필드는 무시한다
		createMemo(me.accessToken, """{"title":"제목","authorId":"${UUID.randomUUID()}","spaceId":"${UUID.randomUUID()}","extra":1}""")
		assertEquals(me.userId, jdbc.queryForObject("select author_id from memos", UUID::class.java))
	}

	@Test
	fun `FR-MEMO-02 AC2 body가 null이면 생략과 같다`() {
		val me = startMember()

		val id = createMemo(me.accessToken, """{"title":"제목","body":null,"memoDate":null}""")

		assertNull(memoRow(id)["body"])
		assertNull(memoRow(id)["memo_date"])
	}

	// ── FR-MEMO-02 AC3 ───────────────────────────────────

	@Test
	fun `FR-MEMO-02 AC3 memoDate를 생략하면 null이고 YYYY-MM-DD가 아니면 400 (memoDate INVALID)`() {
		val me = startMember()

		val omitted = createMemo(me.accessToken, """{"title":"날짜 없음"}""")
		val dated = createMemo(me.accessToken, """{"title":"날짜 있음","memoDate":"2026-11-03"}""")

		assertNull(memoRow(omitted)["memo_date"])
		assertEquals("2026-11-03", memoRow(dated)["memo_date"])
		listOf("2026-11-3", "2026/11/03", "2026-02-30", "20261103", "2026-11-03T00:00:00Z", "").forEach { bad ->
			val result = postMemoRaw(me.accessToken, """{"title":"제목","memoDate":"$bad"}""").andExpect(status().isBadRequest)
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			assertEquals(listOf("memoDate" to "INVALID"), fieldsOf(result), bad)
		}
		assertEquals(2, memoCount())
	}

	// ── FR-MEMO-03 AC1 ───────────────────────────────────

	@Test
	fun `FR-MEMO-03 AC1 작성자는 PATCH로 제목 본문 memoDate를 바꾸고 null로 지울 수 있다`() {
		val couple = newCouple()
		val id = createMemo(couple.a.accessToken, """{"title":"처음","body":"본문","memoDate":"2026-11-03"}""")
		val notificationsBefore = count("select count(*) from notifications")
		clock.advance(Duration.ofMinutes(5))

		patchMemo(couple.a.accessToken, id, """{"title":"  바꿈  ","body":" 새 본문\n","memoDate":"2026-11-04"}""")
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.id").value(id.toString()))
			.andExpect(jsonPath("$.title").value("바꿈"))
			.andExpect(jsonPath("$.body").value(" 새 본문\n"))
			.andExpect(jsonPath("$.memoDate").value("2026-11-04"))
			.andExpect(jsonPath("$.createdAt").value("2026-10-26T00:00:00Z"))
			.andExpect(jsonPath("$.updatedAt").value("2026-10-26T00:05:00Z"))
		// 생략한 필드는 그대로
		patchMemo(couple.a.accessToken, id, """{"title":"또 바꿈"}""").andExpect(status().isOk)
			.andExpect(jsonPath("$.body").value(" 새 본문\n"))
			.andExpect(jsonPath("$.memoDate").value("2026-11-04"))
		// null은 지운다
		patchMemo(couple.a.accessToken, id, """{"title":null,"memoDate":null}""").andExpect(status().isOk)
			.andExpect(jsonPath("$.title").value(null as String?))
			.andExpect(jsonPath("$.body").value(" 새 본문\n"))
			.andExpect(jsonPath("$.memoDate").value(null as String?))
		// 공백뿐인 본문은 지우는 것이다
		patchMemo(couple.a.accessToken, id, """{"title":"제목 복구","body":"   "}""").andExpect(status().isOk)
			.andExpect(jsonPath("$.body").value(null as String?))
		assertEquals(4, memoChanges.updated.size)
		assertTrue(memoChanges.updated.all { it.memoId == id && it.changedByUserId == couple.a.userId })
		// 수정은 알림 행을 만들지 않는다
		assertEquals(notificationsBefore, count("select count(*) from notifications"))
	}

	@Test
	fun `FR-MEMO-03 AC1 PATCH 결과가 제목 본문 둘 다 비면 400 VALIDATION_FAILED이고 바뀌지 않는다`() {
		val me = startMember()
		val titleOnly = createMemo(me.accessToken, """{"title":"제목만"}""")
		val both = createMemo(me.accessToken, """{"title":"제목","body":"본문"}""")
		val before = memoSnapshot()

		// 제목만 있는 메모의 제목을 지우면 둘 다 빈다
		val cleared = patchMemo(me.accessToken, titleOnly, """{"title":null}""").andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
		assertEquals(listOf("title" to "REQUIRED", "body" to "REQUIRED"), fieldsOf(cleared))
		patchMemo(me.accessToken, titleOnly, """{"title":"   "}""").andExpect(status().isBadRequest)
		// 둘 다 지워도 마찬가지
		patchMemo(me.accessToken, both, """{"title":"","body":" \n "}""").andExpect(status().isBadRequest)
		assertEquals(before, memoSnapshot())
		assertEquals(0, memoChanges.updated.size)
		// 하나가 남으면 된다
		patchMemo(me.accessToken, both, """{"title":null}""").andExpect(status().isOk)
			.andExpect(jsonPath("$.title").value(null as String?))
			.andExpect(jsonPath("$.body").value("본문"))
	}

	@Test
	fun `FR-MEMO-03 AC1 PATCH의 필드 타입 TOO_LONG INVALID는 틀린 필드를 모두 담는다`() {
		val me = startMember()
		val id = createMemo(me.accessToken, """{"title":"제목","body":"본문"}""")
		val before = memoSnapshot()

		val result = patchMemo(me.accessToken, id, """{"title":"${"가".repeat(51)}","body":5,"memoDate":"내일"}""")
			.andExpect(status().isBadRequest)

		assertEquals(listOf("title" to "TOO_LONG", "body" to "INVALID", "memoDate" to "INVALID"), fieldsOf(result))
		assertEquals(before, memoSnapshot())
	}

	@Test
	fun `FR-MEMO-03 AC1 PATCH는 notifyPartner를 무시한다`() {
		val couple = newCouple()
		val id = createMemo(couple.a.accessToken, """{"title":"제목","notifyPartner":false}""")

		patchMemo(couple.a.accessToken, id, """{"title":"바꿈","notifyPartner":null}""").andExpect(status().isOk)
			.andExpect(jsonPath("$.title").value("바꿈"))
	}

	@Test
	fun `FR-MEMO-03 AC1 작성자는 메모를 삭제할 수 있고 즐겨찾기 행도 함께 지워진다 (204)`() {
		val couple = newCouple()
		val id = createMemo(couple.a.accessToken, """{"title":"지울 메모"}""")
		val other = createMemo(couple.a.accessToken, """{"title":"남길 메모"}""")
		putFavorite(couple.a.accessToken, id).andExpect(status().isNoContent)
		putFavorite(couple.b.accessToken, id).andExpect(status().isNoContent)
		putFavorite(couple.b.accessToken, other).andExpect(status().isNoContent)

		deleteMemo(couple.a.accessToken, id).andExpect(status().isNoContent).andExpect(content().string(""))

		assertEquals(0, count("select count(*) from memos where id = ?", id))
		assertEquals(0, count("select count(*) from memo_favorites where memo_id = ?", id))
		assertEquals(1, count("select count(*) from memos where id = ?", other))
		assertEquals(1, count("select count(*) from memo_favorites where memo_id = ?", other))
		assertEquals(listOf(id), memoChanges.deleted.map { it.memoId })
		getMemo(couple.a.accessToken, id).andExpect(status().isNotFound)
	}

	@Test
	fun `FR-MEMO-03 AC1 GET memos id는 요청자 기준 isFavorite을 준다`() {
		val couple = newCouple()
		val id = createMemo(couple.a.accessToken, """{"title":"제목","body":"본문","memoDate":"2026-11-03"}""")
		putFavorite(couple.a.accessToken, id).andExpect(status().isNoContent)

		getMemo(couple.a.accessToken, id).andExpect(status().isOk)
			.andExpect(jsonPath("$.id").value(id.toString()))
			.andExpect(jsonPath("$.title").value("제목"))
			.andExpect(jsonPath("$.body").value("본문"))
			.andExpect(jsonPath("$.memoDate").value("2026-11-03"))
			.andExpect(jsonPath("$.authorId").value(couple.a.userId.toString()))
			.andExpect(jsonPath("$.isPinned").value(false))
			.andExpect(jsonPath("$.isFavorite").value(true))
		getMemo(couple.b.accessToken, id).andExpect(status().isOk)
			.andExpect(jsonPath("$.isFavorite").value(false))
	}

	@Test
	fun `FR-MEMO-03 AC1 UUID가 아닌 ID는 없는 메모와 같은 404다`() {
		val me = startMember()

		getMemo(me.accessToken, "not-a-uuid").andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("NOT_FOUND"))
		deleteMemo(me.accessToken, "not-a-uuid").andExpect(status().isNotFound)
		putPin(me.accessToken, "x").andExpect(status().isNotFound)
		putFavorite(me.accessToken, "x").andExpect(status().isNotFound)
	}

	// ── FR-MEMO-03 AC4 (D-123) ───────────────────────────

	@Test
	fun `FR-MEMO-03 AC4 저장값과 같은 값만 보내면 updated_at이 그대로이고 MemoUpdated가 없다`() {
		val me = startMember()
		val id = createMemo(me.accessToken, """{"title":"제목","body":" 본문 ","memoDate":"2026-11-03"}""")
		val updatedAt = memoRow(id)["updated_at"]
		clock.advance(Duration.ofHours(1))

		// 빈 본문, 같은 값(제목은 trim 뒤 같음), null 아닌 날짜가 같음
		listOf(
			"""{}""",
			"""{"title":"  제목  ","body":" 본문 ","memoDate":"2026-11-03"}""",
			"""{"memoDate":"2026-11-03"}""",
			"""{"notifyPartner":false}""",
		).forEach { json ->
			patchMemo(me.accessToken, id, json).andExpect(status().isOk)
				.andExpect(jsonPath("$.title").value("제목"))
				.andExpect(jsonPath("$.body").value(" 본문 "))
				.andExpect(jsonPath("$.updatedAt").value("2026-10-26T00:00:00Z"))
		}

		assertEquals(updatedAt, memoRow(id)["updated_at"])
		assertEquals(0, memoChanges.updated.size)
		// 하나라도 다르면 변경이다
		patchMemo(me.accessToken, id, """{"title":"제목","body":" 본문 ","memoDate":"2026-11-04"}""").andExpect(status().isOk)
			.andExpect(jsonPath("$.updatedAt").value("2026-10-26T01:00:00Z"))
		assertNotEquals(updatedAt, memoRow(id)["updated_at"])
		assertEquals(1, memoChanges.updated.size)
	}

	@Test
	fun `FR-MEMO-03 AC4 이미 null인 필드에 null을 보내는 것도 같은 값이다`() {
		val me = startMember()
		val id = createMemo(me.accessToken, """{"title":"제목"}""")
		clock.advance(Duration.ofHours(1))

		patchMemo(me.accessToken, id, """{"body":null,"memoDate":null}""").andExpect(status().isOk)

		assertEquals(0, memoChanges.updated.size)
	}
}
