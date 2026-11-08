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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 고정 핀 (FR-MEMO-05, API.md 3.4, R-04, D-18, D-21, D-134). 고정·해제는 구성원이면 누구나, 멱등, `updated_at` 유지, 실제 전환 때만 `MemoUpdated`.
 * 고정 알림 행(D-135)은 `MemoPartnerNotificationTest`가 확인한다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class MemoPinTest : MemoTestSupport() {

	private val t0: Instant = Instant.parse("2026-10-20T00:00:00Z")

	@Test
	fun `FR-MEMO-05 AC1 상대가 만든 메모도 고정하고 해제할 수 있다`() {
		val couple = newCouple()
		val id = createMemo(couple.a.accessToken, """{"title":"에이의 메모"}""")

		putPin(couple.b.accessToken, id).andExpect(status().isNoContent).andExpect(content().string(""))

		var row = memoRow(id)
		assertEquals(Instant.parse("2026-10-26T00:00:00Z"), (row["pinned_at"] as java.sql.Timestamp).toInstant())
		assertEquals(couple.b.userId, row["pinned_by"])
		getMemo(couple.a.accessToken, id).andExpect(jsonPath("$.isPinned").value(true))
			.andExpect(jsonPath("$.pinnedAt").value("2026-10-26T00:00:00Z"))
		// 작성자도, 상대도 해제할 수 있다
		deletePin(couple.a.accessToken, id).andExpect(status().isNoContent).andExpect(content().string(""))
		row = memoRow(id)
		assertNull(row["pinned_at"])
		assertNull(row["pinned_by"])
		getMemo(couple.a.accessToken, id).andExpect(jsonPath("$.isPinned").value(false))
			.andExpect(jsonPath("$.pinnedAt").value(null as String?))
		putPin(couple.a.accessToken, id).andExpect(status().isNoContent)
		deletePin(couple.b.accessToken, id).andExpect(status().isNoContent)
		assertNull(memoRow(id)["pinned_at"])
	}

	@Test
	fun `FR-MEMO-05 AC1 개인 공간에서도 고정하고 해제할 수 있고 개수 제한이 없다`() {
		val me = startMember()
		val ids = (1..6).map { createMemo(me.accessToken, """{"title":"메모 $it"}""") }

		ids.forEach { putPin(me.accessToken, it).andExpect(status().isNoContent) }

		assertEquals(6, count("select count(*) from memos where pinned_at is not null"))
		assertEquals(6, idsOf(getPinnedMemos(me.accessToken)).size)
	}

	@Test
	fun `FR-MEMO-05 AC3 GET memos pinned는 고정 메모 전체를 최근 고정순으로 준다`() {
		val couple = newCouple()
		val first = insertMemoRow(couple.spaceId, couple.a.userId, "먼저 고정", createdAt = t0.plusSeconds(100), pinnedAt = t0, pinnedBy = couple.a.userId)
		val last = insertMemoRow(couple.spaceId, couple.a.userId, "나중에 고정", createdAt = t0, pinnedAt = t0.plusSeconds(60), pinnedBy = couple.b.userId)
		val middle = insertMemoRow(couple.spaceId, couple.b.userId, "중간", createdAt = t0.plusSeconds(50), pinnedAt = t0.plusSeconds(30), pinnedBy = couple.a.userId)
		insertMemoRow(couple.spaceId, couple.a.userId, "고정 안 함", createdAt = t0.plusSeconds(200))
		// 4개 이상이어도 전부 준다 (앱이 3개만 보여준다)
		val sameTime = (1..3).map { insertMemoRow(couple.spaceId, couple.a.userId, "같은 시각 $it", pinnedAt = t0.minusSeconds(10), pinnedBy = couple.a.userId) }

		val ids = idsOf(getPinnedMemos(couple.b.accessToken).andExpect(status().isOk))

		// pinned_at 내림차순 → id 내림차순
		assertEquals(listOf(last, middle, first), ids.take(3))
		assertEquals(sameTime.sortedWith(pgOrder).reversed(), ids.drop(3))
		getPinnedMemos(couple.a.accessToken).andExpect(jsonPath("$.items[0].isPinned").value(true))
			.andExpect(jsonPath("$.items[0].pinnedAt").value("2026-10-20T00:01:00Z"))
			.andExpect(jsonPath("$.nextCursor").doesNotExist())
	}

	@Test
	fun `FR-MEMO-05 AC3 고정한 메모가 없으면 빈 items를 준다`() {
		val me = startMember()
		createMemo(me.accessToken, """{"title":"고정 안 함"}""")

		getPinnedMemos(me.accessToken).andExpect(status().isOk).andExpect(jsonPath("$.items").isEmpty)
	}

	@Test
	fun `FR-MEMO-05 AC5 이미 고정된 메모를 다시 고정하면 204이고 pinned_at pinned_by가 그대로이며 알림이 없다`() {
		val couple = newCouple()
		val id = createMemo(couple.a.accessToken, """{"title":"메모","notifyPartner":false}""")
		putPin(couple.a.accessToken, id).andExpect(status().isNoContent)
		val pinnedRow = memoRow(id)
		val notifications = count("select count(*) from notifications")
		val updates = memoChanges.updated.size
		clock.advance(Duration.ofHours(3))

		// 같은 사람이 다시, 상대가 다시
		putPin(couple.a.accessToken, id).andExpect(status().isNoContent)
		putPin(couple.b.accessToken, id).andExpect(status().isNoContent)

		val after = memoRow(id)
		assertEquals(pinnedRow["pinned_at"], after["pinned_at"])
		assertEquals(couple.a.userId, after["pinned_by"])
		assertEquals(notifications, count("select count(*) from notifications"))
		assertEquals(updates, memoChanges.updated.size)
	}

	@Test
	fun `FR-MEMO-05 AC5 고정되지 않은 메모를 해제해도 204이고 아무것도 바뀌지 않는다`() {
		val couple = newCouple()
		val id = createMemo(couple.a.accessToken, """{"title":"메모"}""")
		val before = memoSnapshot()
		val notifications = count("select count(*) from notifications")

		deletePin(couple.a.accessToken, id).andExpect(status().isNoContent).andExpect(content().string(""))
		deletePin(couple.b.accessToken, id).andExpect(status().isNoContent)

		assertEquals(before, memoSnapshot())
		assertEquals(notifications, count("select count(*) from notifications"))
		assertEquals(0, memoChanges.updated.size)
	}

	@Test
	fun `FR-MEMO-05 AC5 해제 뒤 다시 고정하면 pinned_at이 새 시각이다`() {
		val couple = newCouple()
		val id = createMemo(couple.a.accessToken, """{"title":"메모"}""")
		putPin(couple.a.accessToken, id).andExpect(status().isNoContent)
		val firstPinnedAt = memoRow(id)["pinned_at"]
		clock.advance(Duration.ofMinutes(10))
		deletePin(couple.a.accessToken, id).andExpect(status().isNoContent)
		clock.advance(Duration.ofMinutes(10))

		putPin(couple.b.accessToken, id).andExpect(status().isNoContent)

		val row = memoRow(id)
		assertNotEquals(firstPinnedAt, row["pinned_at"])
		assertEquals(Instant.parse("2026-10-26T00:20:00Z"), (row["pinned_at"] as java.sql.Timestamp).toInstant())
		assertEquals(couple.b.userId, row["pinned_by"])
		// 다시 고정한 메모가 최근 고정순 맨 앞이다
		val other = insertMemoRow(couple.spaceId, couple.a.userId, "다른 고정", pinnedAt = Instant.parse("2026-10-26T00:10:00Z"), pinnedBy = couple.a.userId)
		assertEquals(listOf(id, other), idsOf(getPinnedMemos(couple.a.accessToken)))
	}

	@Test
	fun `FR-MEMO-05 AC1 고정과 해제는 updated_at을 바꾸지 않는다`() {
		val couple = newCouple()
		val id = createMemo(couple.a.accessToken, """{"title":"메모"}""")
		val updatedAt = memoRow(id)["updated_at"]
		clock.advance(Duration.ofHours(1))

		putPin(couple.b.accessToken, id).andExpect(status().isNoContent)
		assertEquals(updatedAt, memoRow(id)["updated_at"])
		clock.advance(Duration.ofHours(1))
		deletePin(couple.a.accessToken, id).andExpect(status().isNoContent)
		assertEquals(updatedAt, memoRow(id)["updated_at"])
		getMemo(couple.a.accessToken, id).andExpect(jsonPath("$.updatedAt").value("2026-10-26T00:00:00Z"))
	}

	@Test
	fun `FR-MEMO-05 AC5 MemoUpdated는 실제로 전환될 때만 발행한다`() {
		val couple = newCouple()
		val id = createMemo(couple.a.accessToken, """{"title":"메모"}""")

		putPin(couple.b.accessToken, id) // 전환 1
		putPin(couple.b.accessToken, id) // 멱등
		deletePin(couple.a.accessToken, id) // 전환 2
		deletePin(couple.a.accessToken, id) // 멱등
		putFavorite(couple.a.accessToken, id) // 즐겨찾기는 이벤트 없음

		assertEquals(2, memoChanges.updated.size)
		assertTrue(memoChanges.updated.all { it.memoId == id && it.spaceId == couple.spaceId })
		assertEquals(listOf(couple.b.userId, couple.a.userId), memoChanges.updated.map { it.changedByUserId })
		assertEquals(0, memoChanges.deleted.size)
	}

	@Test
	fun `FR-MEMO-05 AC5 두 사람이 동시에 고정해도 고정 시각과 알림은 하나다`() {
		val couple = newCouple()
		val id = createMemo(couple.a.accessToken, """{"title":"메모","notifyPartner":false}""")
		val notificationsBefore = count("select count(*) from notifications")

		val results = inParallel(
			listOf(
				{ statusOf(putPin(couple.a.accessToken, id)) },
				{ statusOf(putPin(couple.b.accessToken, id)) },
			),
		)

		assertEquals(listOf(204, 204), results)
		// 한 번만 전환된다: 알림 행 하나(고정하지 않은 쪽), 이벤트 하나, pinned_by는 먼저 잠근 사람
		assertEquals(notificationsBefore + 1, count("select count(*) from notifications"))
		assertEquals(1, count("select count(*) from notifications where type = 'MEMO_PINNED'"))
		assertEquals(1, memoChanges.updated.size)
		val row = memoRow(id)
		assertEquals(memoChanges.updated.single().changedByUserId, row["pinned_by"])
		val recipient = jdbc.queryForObject("select user_id from notifications where type = 'MEMO_PINNED'", java.util.UUID::class.java)
		assertNotEquals(row["pinned_by"], recipient)
	}

	private val pgOrder = Comparator<java.util.UUID> { a, b ->
		val high = java.lang.Long.compareUnsigned(a.mostSignificantBits, b.mostSignificantBits)
		if (high != 0) high else java.lang.Long.compareUnsigned(a.leastSignificantBits, b.leastSignificantBits)
	}
}
