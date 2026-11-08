package com.duriseo.server.memo

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.TestClockConfig
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `GET /memos` (FR-MEMO-01, API.md 3.4, 3.3 "커서 정렬 키", 1.1 "커서·`size` 공통 규칙", D-130). 고정되지 않은 메모만 `view`별로 준다.
 * 새 사용자의 시간대는 `Asia/Seoul`이고 시각은 `MutableClock`(2026-10-26T00:00:00Z)에 맞춘다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class MemoListTest : MemoTestSupport() {

	private val t0: Instant = Instant.parse("2026-10-20T00:00:00Z")

	/** PostgreSQL `uuid` 비교(부호 없는 바이트 순)와 같은 순서 */
	private val pgOrder = Comparator<UUID> { a, b ->
		val high = java.lang.Long.compareUnsigned(a.mostSignificantBits, b.mostSignificantBits)
		if (high != 0) high else java.lang.Long.compareUnsigned(a.leastSignificantBits, b.leastSignificantBits)
	}

	/** [view]의 모든 페이지를 [size]씩 따라가며 ID를 이어 붙인다. 마지막 페이지의 `nextCursor`는 null이어야 한다 */
	private fun allPages(token: String, view: String, size: Int): List<UUID> {
		val ids = mutableListOf<UUID>()
		var cursor: String? = null
		var guard = 0
		do {
			val body = bodyOf(getMemos(token, view = view, cursor = cursor, size = size.toString()).andExpect(status().isOk))
			ids += JsonPath.read<List<String>>(body, "$.items[*].id").map { UUID.fromString(it) }
			cursor = JsonPath.read<String?>(body, "$.nextCursor")
			check(++guard < 100) { "cursor does not end" }
		} while (cursor != null)
		return ids
	}

	// ── FR-MEMO-01 AC1·AC2 ───────────────────────────────

	@Test
	fun `FR-MEMO-01 AC1 GET memos는 고정되지 않은 메모를 created_at 최신순으로 준다`() {
		val couple = newCouple()
		val old = insertMemoRow(couple.spaceId, couple.a.userId, "가장 오래된", createdAt = t0)
		val newest = insertMemoRow(couple.spaceId, couple.b.userId, "가장 최신", createdAt = t0.plus(Duration.ofDays(2)))
		val middle = insertMemoRow(couple.spaceId, couple.a.userId, "중간", createdAt = t0.plus(Duration.ofDays(1)))
		insertMemoRow(couple.spaceId, couple.a.userId, "고정", createdAt = t0.plus(Duration.ofDays(3)), pinnedAt = t0, pinnedBy = couple.a.userId)

		// view를 생략하면 newest다
		assertEquals(listOf(newest, middle, old), idsOf(getMemos(couple.a.accessToken).andExpect(status().isOk)))
		assertEquals(listOf(newest, middle, old), idsOf(getMemos(couple.b.accessToken, view = "newest")))
		getMemos(couple.a.accessToken).andExpect(jsonPath("$.nextCursor").value(null as String?))
	}

	@Test
	fun `FR-MEMO-01 AC2 view oldest는 created_at 오래된순이다`() {
		val couple = newCouple()
		val old = insertMemoRow(couple.spaceId, couple.a.userId, "오래된", createdAt = t0)
		val newest = insertMemoRow(couple.spaceId, couple.b.userId, "최신", createdAt = t0.plus(Duration.ofDays(2)))
		val middle = insertMemoRow(couple.spaceId, couple.a.userId, "중간", createdAt = t0.plus(Duration.ofDays(1)))

		assertEquals(listOf(old, middle, newest), idsOf(getMemos(couple.a.accessToken, view = "oldest").andExpect(status().isOk)))
	}

	@Test
	fun `FR-MEMO-01 AC2 view favorites는 내가 즐겨찾기한 고정되지 않은 메모만 최신순이다`() {
		val couple = newCouple()
		val first = insertMemoRow(couple.spaceId, couple.a.userId, "첫째", createdAt = t0)
		val second = insertMemoRow(couple.spaceId, couple.b.userId, "둘째", createdAt = t0.plus(Duration.ofDays(1)))
		val third = insertMemoRow(couple.spaceId, couple.a.userId, "셋째", createdAt = t0.plus(Duration.ofDays(2)))
		val pinned = insertMemoRow(couple.spaceId, couple.a.userId, "고정", createdAt = t0.plus(Duration.ofDays(3)), pinnedAt = t0, pinnedBy = couple.a.userId)
		insertMemoFavorite(first, couple.a.userId)
		insertMemoFavorite(third, couple.a.userId)
		insertMemoFavorite(pinned, couple.a.userId)
		insertMemoFavorite(second, couple.b.userId)

		// 즐겨찾기한 시각이 아니라 메모의 작성 시각 순이다
		assertEquals(listOf(third, first), idsOf(getMemos(couple.a.accessToken, view = "favorites").andExpect(status().isOk)))
		assertEquals(listOf(second), idsOf(getMemos(couple.b.accessToken, view = "favorites")))
	}

	@Test
	fun `FR-MEMO-01 AC2 고정된 메모는 newest oldest favorites 어디에도 나오지 않는다`() {
		val couple = newCouple()
		val normal = insertMemoRow(couple.spaceId, couple.a.userId, "일반", createdAt = t0)
		val pinned = insertMemoRow(couple.spaceId, couple.a.userId, "고정", createdAt = t0.plus(Duration.ofDays(1)), pinnedAt = t0, pinnedBy = couple.b.userId)
		insertMemoFavorite(normal, couple.a.userId)
		insertMemoFavorite(pinned, couple.a.userId)

		listOf("newest", "oldest", "favorites").forEach { view ->
			assertEquals(listOf(normal), idsOf(getMemos(couple.a.accessToken, view = view).andExpect(status().isOk)), view)
		}
		// 고정은 /memos/pinned에서만 보인다
		assertEquals(listOf(pinned), idsOf(getPinnedMemos(couple.a.accessToken)))
	}

	@Test
	fun `FR-MEMO-01 AC2 totalCount는 view별 고정 제외 개수이고 favoriteCount는 고정 제외 내 즐겨찾기 개수다`() {
		val couple = newCouple()
		val u1 = insertMemoRow(couple.spaceId, couple.a.userId, "일반1", createdAt = t0)
		val u2 = insertMemoRow(couple.spaceId, couple.a.userId, "일반2", createdAt = t0.plusSeconds(1))
		val u3 = insertMemoRow(couple.spaceId, couple.b.userId, "일반3", createdAt = t0.plusSeconds(2))
		val p1 = insertMemoRow(couple.spaceId, couple.a.userId, "고정1", pinnedAt = t0, pinnedBy = couple.a.userId)
		insertMemoRow(couple.spaceId, couple.a.userId, "고정2", pinnedAt = t0, pinnedBy = couple.a.userId)
		insertMemoFavorite(u1, couple.a.userId)
		insertMemoFavorite(p1, couple.a.userId)
		insertMemoFavorite(u2, couple.b.userId)
		insertMemoFavorite(u3, couple.b.userId)
		// 다른 공간의 메모는 세지 않는다
		val other = newCouple()
		insertMemoRow(other.spaceId, other.a.userId, "다른 공간")

		// A: 일반 3개, 즐겨찾기 1개(고정은 제외)
		listOf("newest", "oldest").forEach { view ->
			getMemos(couple.a.accessToken, view = view, size = "1").andExpect(status().isOk)
				.andExpect(jsonPath("$.totalCount").value(3))
				.andExpect(jsonPath("$.favoriteCount").value(1))
		}
		getMemos(couple.a.accessToken, view = "favorites").andExpect(status().isOk)
			.andExpect(jsonPath("$.totalCount").value(1))
			.andExpect(jsonPath("$.favoriteCount").value(1))
		// B는 자기 즐겨찾기만 센다
		getMemos(couple.b.accessToken).andExpect(jsonPath("$.totalCount").value(3)).andExpect(jsonPath("$.favoriteCount").value(2))
		getMemos(couple.b.accessToken, view = "favorites").andExpect(jsonPath("$.totalCount").value(2))
		// 빈 공간
		val empty = startMember()
		getMemos(empty.accessToken).andExpect(jsonPath("$.items").isEmpty)
			.andExpect(jsonPath("$.totalCount").value(0)).andExpect(jsonPath("$.favoriteCount").value(0))
	}

	@Test
	fun `FR-MEMO-01 AC2 같은 created_at 메모가 페이지 경계에 걸려도 빠지거나 겹치지 않는다 newest`() {
		assertSameCreatedAtPaging("newest", reversed = true)
	}

	@Test
	fun `FR-MEMO-01 AC2 같은 created_at 메모가 페이지 경계에 걸려도 빠지거나 겹치지 않는다 oldest`() {
		assertSameCreatedAtPaging("oldest", reversed = false)
	}

	@Test
	fun `FR-MEMO-01 AC2 같은 created_at 메모가 페이지 경계에 걸려도 빠지거나 겹치지 않는다 favorites`() {
		assertSameCreatedAtPaging("favorites", reversed = true)
	}

	/** 같은 `created_at` 일곱 개와 앞뒤 하나씩을 [view]로 2·3개씩 나눠 읽어도 정렬 키(`created_at`, `id`) 순서 그대로 한 번씩 나온다 */
	private fun assertSameCreatedAtPaging(view: String, reversed: Boolean) {
		val couple = newCouple()
		val before = insertMemoRow(couple.spaceId, couple.a.userId, "앞", createdAt = t0.minusSeconds(10))
		val same = (1..7).map { insertMemoRow(couple.spaceId, couple.a.userId, "같은 시각 $it", createdAt = t0) }
		val after = insertMemoRow(couple.spaceId, couple.a.userId, "뒤", createdAt = t0.plusSeconds(10))
		(listOf(before, after) + same).forEach { insertMemoFavorite(it, couple.a.userId) }
		val ascending = listOf(before) + same.sortedWith(pgOrder) + listOf(after)
		val expected = if (reversed) ascending.reversed() else ascending

		listOf(1, 2, 3, 4, 9).forEach { size ->
			assertEquals(expected, allPages(couple.a.accessToken, view, size), "view=$view size=$size")
		}
	}

	@Test
	fun `FR-MEMO-01 AC2 size 기본값은 20이고 100까지 받으며 다음 페이지가 있으면 nextCursor를 준다`() {
		val me = startMember()
		val space = activeSpaceIdOfUser(me.userId)
		(1..21).forEach { insertMemoRow(space, me.userId, "메모 $it", createdAt = t0.plusSeconds(it.toLong())) }

		val first = bodyOf(getMemos(me.accessToken).andExpect(status().isOk))
		assertEquals(20, JsonPath.read<List<Any>>(first, "$.items").size)
		assertNotNull(JsonPath.read<String?>(first, "$.nextCursor"))
		assertEquals(21, JsonPath.read<Int>(first, "$.totalCount"))
		val all = bodyOf(getMemos(me.accessToken, size = "100").andExpect(status().isOk))
		assertEquals(21, JsonPath.read<List<Any>>(all, "$.items").size)
		assertNull(JsonPath.read<String?>(all, "$.nextCursor"))
		// 정확히 size개만 남은 마지막 페이지는 nextCursor가 null이다
		val exact = bodyOf(getMemos(me.accessToken, size = "21").andExpect(status().isOk))
		assertNull(JsonPath.read<String?>(exact, "$.nextCursor"))
	}

	@Test
	fun `FR-MEMO-01 AC2 size가 범위 밖이거나 cursor를 읽지 못하면 400 VALIDATION_FAILED`() {
		val me = startMember()
		insertMemoRow(activeSpaceIdOfUser(me.userId), me.userId)

		listOf("0", "101", "-1", "abc", "1.5", "").forEach { size ->
			val result = getMemos(me.accessToken, size = size).andExpect(status().isBadRequest)
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			assertEquals(listOf("size" to "INVALID"), fieldsOf(result), "size=$size")
		}
		listOf("garbage", "!!!", "bm90LWEtY3Vyc29y").forEach { cursor ->
			val result = getMemos(me.accessToken, cursor = cursor).andExpect(status().isBadRequest)
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			assertEquals(listOf("cursor" to "INVALID"), fieldsOf(result), "cursor=$cursor")
		}
		// 틀린 필드는 모두 담는다
		val both = getMemos(me.accessToken, cursor = "garbage", size = "0").andExpect(status().isBadRequest)
		assertEquals(listOf("size" to "INVALID", "cursor" to "INVALID"), fieldsOf(both))
	}

	@Test
	fun `FR-MEMO-01 AC2 view가 모르는 값이면 400 VALIDATION_FAILED (view INVALID)`() {
		val me = startMember()
		val space = activeSpaceIdOfUser(me.userId)
		insertMemoRow(space, me.userId, createdAt = t0)
		insertMemoRow(space, me.userId, createdAt = t0.plusSeconds(1))

		listOf("latest", "NEWEST", "pinned", "").forEach { view ->
			val result = getMemos(me.accessToken, view = view).andExpect(status().isBadRequest)
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			assertEquals(listOf("view" to "INVALID"), fieldsOf(result), "view=$view")
		}
		// 다른 view의 커서는 읽지 못한 것으로 본다
		val cursor = JsonPath.read<String>(bodyOf(getMemos(me.accessToken, view = "newest", size = "1")), "$.nextCursor")
		val mismatch = getMemos(me.accessToken, view = "oldest", cursor = cursor).andExpect(status().isBadRequest)
		assertEquals(listOf("cursor" to "INVALID"), fieldsOf(mismatch))
		getMemos(me.accessToken, view = "favorites", cursor = cursor).andExpect(status().isBadRequest)
		getMemos(me.accessToken, view = "newest", cursor = cursor).andExpect(status().isOk)
	}

	// ── FR-MEMO-01 AC3 ───────────────────────────────────

	@Test
	fun `FR-MEMO-01 AC3 Memo 응답은 id title body memoDate authorId isPinned pinnedAt isFavorite createdAt updatedAt을 null 포함해 준다`() {
		val couple = newCouple()
		val withAll = insertMemoRow(couple.spaceId, couple.b.userId, "제목", "본문", "2026-11-03", createdAt = t0)
		val bare = insertMemoRow(couple.spaceId, couple.a.userId, null, "본문만", null, createdAt = t0.plusSeconds(1))
		insertMemoFavorite(withAll, couple.a.userId)

		val body = bodyOf(getMemos(couple.a.accessToken).andExpect(status().isOk))

		val items = JsonPath.read<List<Map<String, Any?>>>(body, "$.items")
		assertEquals(2, items.size)
		val bareItem = items[0]
		assertEquals(bare.toString(), bareItem["id"])
		assertEquals(
			setOf("id", "title", "body", "memoDate", "authorId", "isPinned", "pinnedAt", "isFavorite", "createdAt", "updatedAt"),
			bareItem.keys,
		)
		assertNull(bareItem["title"])
		assertEquals("본문만", bareItem["body"])
		assertNull(bareItem["memoDate"])
		assertEquals(couple.a.userId.toString(), bareItem["authorId"])
		assertEquals(false, bareItem["isPinned"])
		assertNull(bareItem["pinnedAt"])
		assertEquals(false, bareItem["isFavorite"])
		assertEquals("2026-10-20T00:00:01Z", bareItem["createdAt"])
		val fullItem = items[1]
		assertEquals("제목", fullItem["title"])
		assertEquals("2026-11-03", fullItem["memoDate"])
		assertEquals(couple.b.userId.toString(), fullItem["authorId"])
		assertEquals(true, fullItem["isFavorite"])
		assertEquals("2026-10-20T00:00:00Z", fullItem["updatedAt"])
	}
}
