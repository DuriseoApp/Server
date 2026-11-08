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
import kotlin.test.assertEquals

/**
 * 즐겨찾기 (FR-MEMO-06, API.md 3.4, R-06, D-24, D-134). 본인의 행만 만들고 지우며, 멱등이고, 이벤트·동기화 신호가 없다.
 * 탈퇴 정리(ERD 4.3)도 여기서 확인한다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class MemoFavoriteTest : MemoTestSupport() {

	@Test
	fun `FR-MEMO-06 AC1 A가 즐겨찾기한 메모는 B의 응답에서 isFavorite false이고 B의 favorites 보기에 없다`() {
		val couple = newCouple()
		val id = createMemo(couple.a.accessToken, """{"title":"에이가 즐겨찾기"}""")

		putFavorite(couple.a.accessToken, id).andExpect(status().isNoContent).andExpect(content().string(""))

		// R-06: 요청자 본인의 행만 만든다
		assertEquals(1, count("select count(*) from memo_favorites"))
		assertEquals(1, count("select count(*) from memo_favorites where memo_id = ? and user_id = ?", id, couple.a.userId))
		getMemo(couple.a.accessToken, id).andExpect(jsonPath("$.isFavorite").value(true))
		getMemo(couple.b.accessToken, id).andExpect(jsonPath("$.isFavorite").value(false))
		getMemos(couple.a.accessToken).andExpect(jsonPath("$.items[0].isFavorite").value(true)).andExpect(jsonPath("$.favoriteCount").value(1))
		getMemos(couple.b.accessToken).andExpect(jsonPath("$.items[0].isFavorite").value(false)).andExpect(jsonPath("$.favoriteCount").value(0))
		assertEquals(listOf(id), idsOf(getMemos(couple.a.accessToken, view = "favorites")))
		assertEquals(emptyList(), idsOf(getMemos(couple.b.accessToken, view = "favorites")))
		getMemos(couple.b.accessToken, view = "favorites").andExpect(jsonPath("$.totalCount").value(0))
		// 고정 영역·캘린더 응답에도 남의 즐겨찾기는 섞이지 않는다
		putPin(couple.a.accessToken, id).andExpect(status().isNoContent)
		getPinnedMemos(couple.b.accessToken).andExpect(jsonPath("$.items[0].isFavorite").value(false))
		getPinnedMemos(couple.a.accessToken).andExpect(jsonPath("$.items[0].isFavorite").value(true))
	}

	@Test
	fun `R-06 DELETE favorite는 요청자 본인의 행만 지운다`() {
		val couple = newCouple()
		val id = createMemo(couple.a.accessToken, """{"title":"둘 다 즐겨찾기"}""")
		putFavorite(couple.a.accessToken, id).andExpect(status().isNoContent)
		putFavorite(couple.b.accessToken, id).andExpect(status().isNoContent)
		assertEquals(2, count("select count(*) from memo_favorites where memo_id = ?", id))

		deleteFavorite(couple.b.accessToken, id).andExpect(status().isNoContent)

		assertEquals(1, count("select count(*) from memo_favorites where memo_id = ?", id))
		assertEquals(1, count("select count(*) from memo_favorites where memo_id = ? and user_id = ?", id, couple.a.userId))
		getMemo(couple.a.accessToken, id).andExpect(jsonPath("$.isFavorite").value(true))
		getMemo(couple.b.accessToken, id).andExpect(jsonPath("$.isFavorite").value(false))
	}

	@Test
	fun `FR-MEMO-06 AC1 상대가 만든 메모도 즐겨찾기할 수 있다`() {
		val couple = newCouple()
		val id = createMemo(couple.a.accessToken, """{"title":"에이의 메모"}""")

		putFavorite(couple.b.accessToken, id).andExpect(status().isNoContent)

		getMemos(couple.b.accessToken, view = "favorites").andExpect(jsonPath("$.items[0].id").value(id.toString()))
	}

	@Test
	fun `FR-MEMO-06 AC2 이미 즐겨찾기한 메모를 다시 즐겨찾기하거나 없는 것을 해제해도 204이고 이벤트가 없다`() {
		val couple = newCouple()
		val id = createMemo(couple.a.accessToken, """{"title":"메모","notifyPartner":false}""")
		val updatedAt = memoRow(id)["updated_at"]
		val created = memoChanges.created.size
		clock.advance(Duration.ofHours(1))

		// 없는 것을 해제해도 204
		deleteFavorite(couple.a.accessToken, id).andExpect(status().isNoContent)
		assertEquals(0, count("select count(*) from memo_favorites"))
		// 추가, 다시 추가
		putFavorite(couple.a.accessToken, id).andExpect(status().isNoContent)
		val favoriteRow = jdbc.queryForMap("select created_at from memo_favorites where memo_id = ?", id)
		clock.advance(Duration.ofHours(1))
		putFavorite(couple.a.accessToken, id).andExpect(status().isNoContent)
		assertEquals(1, count("select count(*) from memo_favorites"))
		assertEquals(favoriteRow["created_at"], jdbc.queryForMap("select created_at from memo_favorites where memo_id = ?", id)["created_at"])
		// 해제, 다시 해제
		deleteFavorite(couple.a.accessToken, id).andExpect(status().isNoContent)
		deleteFavorite(couple.a.accessToken, id).andExpect(status().isNoContent)
		assertEquals(0, count("select count(*) from memo_favorites"))

		// 즐겨찾기는 이벤트를 만들지 않고 메모를 바꾸지 않는다 (D-134)
		assertEquals(0, memoChanges.updated.size)
		assertEquals(0, memoChanges.deleted.size)
		assertEquals(created, memoChanges.created.size)
		assertEquals(updatedAt, memoRow(id)["updated_at"])
		assertEquals(0, count("select count(*) from notifications where type = 'MEMO_PINNED'"))
	}

	@Test
	fun `FR-MEMO-06 AC2 즐겨찾기와 해제는 고정된 메모에도 된다`() {
		val me = startMember()
		val id = createMemo(me.accessToken, """{"title":"메모"}""")
		putPin(me.accessToken, id).andExpect(status().isNoContent)

		putFavorite(me.accessToken, id).andExpect(status().isNoContent)

		// 고정 메모는 favoriteCount와 favorites 보기에 들어가지 않는다 (D-130)
		getMemos(me.accessToken).andExpect(jsonPath("$.favoriteCount").value(0))
		assertEquals(emptyList(), idsOf(getMemos(me.accessToken, view = "favorites")))
		// 고정을 해제하면 다시 보인다
		deletePin(me.accessToken, id).andExpect(status().isNoContent)
		getMemos(me.accessToken).andExpect(jsonPath("$.favoriteCount").value(1))
		assertEquals(listOf(id), idsOf(getMemos(me.accessToken, view = "favorites")))
	}

	@Test
	fun `ERD 4_3 탈퇴하면 그 사용자의 즐겨찾기가 지워지고 메모는 남는다`() {
		val couple = newCouple()
		val mine = createMemo(couple.b.accessToken, """{"title":"비비의 메모"}""")
		val partners = createMemo(couple.a.accessToken, """{"title":"에이의 메모"}""")
		putFavorite(couple.b.accessToken, mine).andExpect(status().isNoContent)
		putFavorite(couple.b.accessToken, partners).andExpect(status().isNoContent)
		putFavorite(couple.a.accessToken, partners).andExpect(status().isNoContent)

		deleteMe(couple.b.accessToken).andExpect(status().isNoContent)

		assertEquals(0, count("select count(*) from memo_favorites where user_id = ?", couple.b.userId))
		// 상대의 즐겨찾기와 두 메모(해제된 공간에 보관)는 남는다
		assertEquals(1, count("select count(*) from memo_favorites where user_id = ?", couple.a.userId))
		assertEquals(2, count("select count(*) from memos where id in (?, ?)", mine, partners))
	}
}
