package com.duriseo.server.memo

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.TestClockConfig
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import kotlin.test.assertEquals

/**
 * 메모 권한 규칙 R-01·R-03·R-04를 모든 경로에서 확인한다 (NFR-SEC-02, API.md 2장). 규칙마다 허용·거부 테스트를 둔다.
 * R-06은 `MemoFavoriteTest`, R-15는 `MemoDateRangeTest`가 확인한다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class MemoPermissionTest : MemoTestSupport() {

	private val t0: Instant = Instant.parse("2026-10-20T00:00:00Z")

	/** 응답 본문에서 오류 코드·메시지·details만 뽑는다 (`traceId`는 요청마다 다르다) */
	private fun errorShape(body: String): List<Any?> = listOf(
		JsonPath.read<String>(body, "$.code"),
		JsonPath.read<String>(body, "$.message"),
		JsonPath.read<Any?>(body, "$.details"),
	)

	// ── R-01 ────────────────────────────────────────────

	@Test
	fun `R-01 같은 공간 메모는 상세 수정 삭제 고정 즐겨찾기를 할 수 있다`() {
		val couple = newCouple()
		val id = createMemo(couple.a.accessToken, """{"title":"에이의 메모"}""")

		getMemo(couple.b.accessToken, id).andExpect(status().isOk)
		getMemo(couple.a.accessToken, id).andExpect(status().isOk)
		putPin(couple.b.accessToken, id).andExpect(status().isNoContent)
		deletePin(couple.b.accessToken, id).andExpect(status().isNoContent)
		putFavorite(couple.b.accessToken, id).andExpect(status().isNoContent)
		deleteFavorite(couple.b.accessToken, id).andExpect(status().isNoContent)
		patchMemo(couple.a.accessToken, id, """{"title":"수정"}""").andExpect(status().isOk)
		deleteMemo(couple.a.accessToken, id).andExpect(status().isNoContent)

		assertEquals(0, memoCount())
	}

	@Test
	fun `R-01 개인 공간의 메모도 그 공간의 사용자가 모두 다룰 수 있다`() {
		val me = startMember()
		val id = createMemo(me.accessToken, """{"title":"혼자 쓰는 메모"}""")

		getMemo(me.accessToken, id).andExpect(status().isOk)
		patchMemo(me.accessToken, id, """{"body":"본문"}""").andExpect(status().isOk)
		putPin(me.accessToken, id).andExpect(status().isNoContent)
		putFavorite(me.accessToken, id).andExpect(status().isNoContent)
		deleteMemo(me.accessToken, id).andExpect(status().isNoContent)
	}

	@Test
	fun `R-01 다른 공간 메모는 GET PATCH DELETE pin favorite 모두 404이고 바뀌지 않는다`() {
		val couple = newCouple()
		val other = newCouple()
		val id = insertMemoRow(other.spaceId, other.a.userId, "다른 커플의 메모", "본문", pinnedAt = t0, pinnedBy = other.a.userId)
		insertMemoFavorite(id, other.a.userId)
		val missing = java.util.UUID.randomUUID()
		val before = memoSnapshot()
		val token = couple.a.accessToken

		val shapes = mutableListOf<List<Any?>>()
		fun denied(result: ResultActions) {
			result.andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("NOT_FOUND"))
			shapes += errorShape(bodyOf(result))
		}
		denied(getMemo(token, id))
		denied(patchMemo(token, id, """{"title":"가로채기"}"""))
		denied(patchMemo(token, id, """{"title":null,"body":null}"""))
		denied(deleteMemo(token, id))
		denied(putPin(token, id))
		denied(deletePin(token, id))
		denied(putFavorite(token, id))
		denied(deleteFavorite(token, id))

		assertEquals(before, memoSnapshot())
		assertEquals(0, memoChanges.updated.size)
		assertEquals(0, memoChanges.deleted.size)
		assertEquals(0, count("select count(*) from notifications where type = 'MEMO_PINNED'"))
		// 없는 ID와 응답이 같아 다른 공간의 메모가 있다는 사실을 알 수 없다 (DB-05)
		val missingShape = errorShape(bodyOf(getMemo(token, missing).andExpect(status().isNotFound)))
		assertEquals(listOf(missingShape), shapes.distinct())
		denied(patchMemo(token, missing, """{"title":"없음"}"""))
		denied(deleteMemo(token, missing))
		denied(putPin(token, missing))
		denied(putFavorite(token, missing))
		assertEquals(listOf(missingShape), shapes.distinct())
	}

	@Test
	fun `R-01 목록 pinned dated는 다른 공간 메모를 넣지 않는다`() {
		val couple = newCouple()
		val other = newCouple()
		val mine = insertMemoRow(couple.spaceId, couple.a.userId, "내 메모", memoDate = "2026-11-03", createdAt = t0)
		val minePinned = insertMemoRow(couple.spaceId, couple.a.userId, "내 고정", memoDate = "2026-11-03", pinnedAt = t0, pinnedBy = couple.a.userId)
		val foreign = insertMemoRow(other.spaceId, other.a.userId, "남의 메모", memoDate = "2026-11-03", createdAt = t0)
		val foreignPinned = insertMemoRow(other.spaceId, other.a.userId, "남의 고정", memoDate = "2026-11-03", pinnedAt = t0, pinnedBy = other.a.userId)
		insertMemoFavorite(foreign, couple.a.userId)

		listOf("newest", "oldest", "favorites").forEach { view ->
			val ids = idsOf(getMemos(couple.a.accessToken, view = view).andExpect(status().isOk))
			assertEquals(if (view == "favorites") emptyList<java.util.UUID>() else listOf(mine), ids, view)
		}
		getMemos(couple.a.accessToken).andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.favoriteCount").value(0))
		assertEquals(listOf(minePinned), idsOf(getPinnedMemos(couple.a.accessToken)))
		assertEquals(setOf(mine, minePinned), idsOf(getDatedMemos(couple.a.accessToken, "2026-11-01", "2026-11-30")).toSet())
		assertEquals(setOf(foreign, foreignPinned), idsOf(getDatedMemos(other.b.accessToken, "2026-11-01", "2026-11-30")).toSet())
	}

	@Test
	fun `R-01 해제한 공간의 메모는 404다`() {
		val couple = newCouple()
		val id = createMemo(couple.a.accessToken, """{"title":"해제 전 메모"}""")
		putFavorite(couple.b.accessToken, id).andExpect(status().isNoContent)
		unlinkCouple(couple.a.accessToken).andExpect(status().isNoContent)
		val before = memoSnapshot()

		// 둘 다 새 개인 공간에 있다: 옛 커플 공간의 메모는 존재하지만 보이지 않는다
		listOf(couple.a.accessToken, couple.b.accessToken).forEach { token ->
			getMemo(token, id).andExpect(status().isNotFound)
			patchMemo(token, id, """{"title":"수정"}""").andExpect(status().isNotFound)
			deleteMemo(token, id).andExpect(status().isNotFound)
			putPin(token, id).andExpect(status().isNotFound)
			deletePin(token, id).andExpect(status().isNotFound)
			putFavorite(token, id).andExpect(status().isNotFound)
			deleteFavorite(token, id).andExpect(status().isNotFound)
			assertEquals(emptyList(), idsOf(getMemos(token).andExpect(status().isOk)))
		}
		assertEquals(before, memoSnapshot())
	}

	// ── R-03 ────────────────────────────────────────────

	@Test
	fun `R-03 작성자는 자기 메모를 PATCH하고 DELETE할 수 있다`() {
		val couple = newCouple()
		val id = createMemo(couple.b.accessToken, """{"title":"비비의 메모"}""")

		patchMemo(couple.b.accessToken, id, """{"title":"수정"}""").andExpect(status().isOk)
		deleteMemo(couple.b.accessToken, id).andExpect(status().isNoContent)

		assertEquals(0, memoCount())
	}

	@Test
	fun `R-03 상대 메모를 PATCH하거나 DELETE하면 403 NOT_AUTHOR이고 바뀌지 않는다`() {
		val couple = newCouple()
		val id = createMemo(couple.a.accessToken, """{"title":"에이의 메모","body":"본문"}""")
		putFavorite(couple.b.accessToken, id).andExpect(status().isNoContent)
		val before = memoSnapshot()

		patchMemo(couple.b.accessToken, id, """{"title":"가로채기"}""").andExpect(status().isForbidden)
			.andExpect(jsonPath("$.code").value("NOT_AUTHOR"))
		// 같은 값만 보내는 요청도, 비어 있는 요청도 작성자가 아니면 거부다
		patchMemo(couple.b.accessToken, id, """{"title":"에이의 메모"}""").andExpect(status().isForbidden)
		patchMemo(couple.b.accessToken, id, """{}""").andExpect(status().isForbidden)
		patchMemo(couple.b.accessToken, id, """{"memoDate":"2026-11-03"}""").andExpect(status().isForbidden)
		deleteMemo(couple.b.accessToken, id).andExpect(status().isForbidden)
			.andExpect(jsonPath("$.code").value("NOT_AUTHOR"))

		assertEquals(before, memoSnapshot())
		assertEquals(0, memoChanges.updated.size)
		assertEquals(0, memoChanges.deleted.size)
	}

	@Test
	fun `FR-MEMO-03 AC1 PATCH 검사 순서는 NOT_FOUND NOT_AUTHOR VALIDATION_FAILED DATE_OUT_OF_RANGE다`() {
		val couple = newCouple()
		val other = newCouple()
		val mine = createMemo(couple.a.accessToken, """{"title":"에이의 메모"}""")
		val foreign = insertMemoRow(other.spaceId, other.a.userId, "남의 메모")
		val tooLongTitle = "가".repeat(51)
		val before = memoSnapshot()

		// 1. R-01: 다른 공간이면 값이 틀려도 404
		patchMemo(couple.a.accessToken, foreign, """{"title":"$tooLongTitle","memoDate":"1900-01-01"}""").andExpect(status().isNotFound)
		// 2. R-03: 작성자가 아니면 값이 틀려도 403
		patchMemo(couple.b.accessToken, mine, """{"title":"$tooLongTitle","memoDate":"1900-01-01"}""").andExpect(status().isForbidden)
			.andExpect(jsonPath("$.code").value("NOT_AUTHOR"))
		// 3. 값 검사: 값이 틀리면 날짜 범위 밖이어도 VALIDATION_FAILED만
		val validation = patchMemo(couple.a.accessToken, mine, """{"title":"$tooLongTitle","memoDate":"1900-01-01"}""")
			.andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
		assertEquals(listOf("title" to "TOO_LONG"), fieldsOf(validation))
		// 4. 날짜 범위
		patchMemo(couple.a.accessToken, mine, """{"memoDate":"1900-01-01"}""").andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("DATE_OUT_OF_RANGE"))

		assertEquals(before, memoSnapshot())
	}

	// ── R-04 ────────────────────────────────────────────

	@Test
	fun `R-04 구성원이면 작성자가 아니어도 고정과 해제를 할 수 있다`() {
		val couple = newCouple()
		val id = createMemo(couple.a.accessToken, """{"title":"에이의 메모"}""")

		putPin(couple.b.accessToken, id).andExpect(status().isNoContent)
		assertEquals(couple.b.userId, memoRow(id)["pinned_by"])
		deletePin(couple.b.accessToken, id).andExpect(status().isNoContent)
		assertEquals(null, memoRow(id)["pinned_at"])
	}

	@Test
	fun `R-04 다른 공간 사용자는 고정과 해제를 할 수 없다 (R-01)`() {
		val couple = newCouple()
		val other = newCouple()
		val id = createMemo(couple.a.accessToken, """{"title":"에이의 메모"}""")
		val before = memoSnapshot()

		putPin(other.a.accessToken, id).andExpect(status().isNotFound)
		deletePin(other.b.accessToken, id).andExpect(status().isNotFound)

		assertEquals(before, memoSnapshot())
		assertEquals(0, memoChanges.updated.size)
	}

	@Test
	fun `NFR-SEC-02 토큰 없이는 모든 메모 경로가 401이다`() {
		val id = java.util.UUID.randomUUID()
		val mvc = mockMvc
		listOf(
			get("$API/memos"),
			get("$API/memos/pinned"),
			get("$API/memos/dated?from=2026-11-01&to=2026-11-30"),
			post("$API/memos"),
			get("$API/memos/$id"),
			patch("$API/memos/$id"),
			delete("$API/memos/$id"),
			put("$API/memos/$id/pin"),
			delete("$API/memos/$id/pin"),
			put("$API/memos/$id/favorite"),
			delete("$API/memos/$id/favorite"),
		).forEach { request -> mvc.perform(request).andExpect(status().isUnauthorized) }
	}
}
