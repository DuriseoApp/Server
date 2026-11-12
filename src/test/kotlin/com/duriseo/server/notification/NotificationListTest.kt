package com.duriseo.server.notification

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.support.NotificationTestSupport
import com.duriseo.server.support.TestClockConfig
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 알림 목록·안 읽음 개수·읽음 처리 (API.md 3.5, FR-NOTI-05, FR-CAL-01 AC4, R-12, D-141, D-142) */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class NotificationListTest : NotificationTestSupport() {

	private val thirtyDays = Duration.ofDays(30)

	/** 모든 페이지를 [size]씩 따라가며 id를 모은다 */
	private fun pagedIds(token: String, size: Int): List<UUID> {
		val ids = mutableListOf<UUID>()
		var cursor: String? = null
		do {
			val body = bodyOf(getNotifications(token, cursor, size.toString()).andExpect(status().isOk))
			ids += JsonPath.read<List<String>>(body, "$.items[*].id").map { UUID.fromString(it) }
			cursor = JsonPath.read<String?>(body, "$.nextCursor")
		} while (cursor != null)
		return ids
	}

	@Test
	fun `FR-NOTI-05 AC2 GET notifications는 30일 안 알림을 최신순으로 주고 같은 created_at도 페이지 경계에서 빠지지 않는다`() {
		val me = startGuest()
		val now = clock.instant()
		// 같은 created_at이 5개(id만 다름) + 더 오래된 것 2개. 페이지 크기 2로 따라가면 같은 시각이 경계에 걸린다
		repeat(5) { insertNotificationRow(me.userId, createdAt = now.minusSeconds(60)) }
		insertNotificationRow(me.userId, createdAt = now.minusSeconds(120))
		insertNotificationRow(me.userId, createdAt = now)

		val expected = jdbc.queryForList(
			"select id from notifications where user_id = ? order by created_at desc, id desc", UUID::class.java, me.userId,
		)

		val paged = pagedIds(me.accessToken, 2)

		assertEquals(7, paged.size)
		assertEquals(expected, paged)
		assertEquals(paged.toSet().size, paged.size)
	}

	@Test
	fun `FR-NOTI-05 AC2 항목은 id type title body targetType targetId read createdAt을 주고 값이 없으면 null이다`() {
		val me = startGuest()
		val id = insertNotificationRow(me.userId, type = "COUPLE_LINKED", title = "비비님과 연결됐어요", body = null)

		val body = bodyOf(getNotifications(me.accessToken).andExpect(status().isOk))
		val item = JsonPath.read<Map<String, Any?>>(body, "$.items[0]")
		assertEquals(
			setOf("id", "type", "title", "body", "targetType", "targetId", "read", "createdAt"), item.keys,
		)
		assertEquals(id.toString(), item["id"])
		assertEquals("COUPLE_LINKED", item["type"])
		assertEquals("비비님과 연결됐어요", item["title"])
		assertNull(item["body"])
		assertNull(item["targetType"])
		assertNull(item["targetId"])
		assertEquals(false, item["read"])
		assertEquals("2026-10-26T00:00:00Z", item["createdAt"])
		assertNull(JsonPath.read<Any?>(body, "$.nextCursor"))
	}

	@Test
	fun `FR-NOTI-05 AC2 size와 cursor가 틀리면 400 VALIDATION_FAILED다`() {
		val me = startGuest()

		getNotifications(me.accessToken, size = "0").expectError(400, "VALIDATION_FAILED")
			.andExpect(jsonPath("$.details.fields[0].field").value("size"))
			.andExpect(jsonPath("$.details.fields[0].reason").value("INVALID"))
		getNotifications(me.accessToken, size = "101").expectError(400, "VALIDATION_FAILED")
		getNotifications(me.accessToken, cursor = "not-a-cursor").expectError(400, "VALIDATION_FAILED")
			.andExpect(jsonPath("$.details.fields[0].field").value("cursor"))
	}

	@Test
	fun `FR-NOTI-05 AC2 30일이 지난 알림은 정리 전에도 목록과 개수에 없다`() {
		val me = startGuest()
		val now = clock.instant()
		val recent = insertNotificationRow(me.userId, createdAt = now.minus(thirtyDays).plusSeconds(1))
		insertNotificationRow(me.userId, createdAt = now.minus(thirtyDays)) // 경계: created_at > now − 30일 이므로 안 보인다
		insertNotificationRow(me.userId, createdAt = now.minus(thirtyDays).minusSeconds(1))

		val ids = pagedIds(me.accessToken, 20)

		assertEquals(listOf(recent), ids)
		getUnreadCount(me.accessToken).andExpect(status().isOk).andExpect(jsonPath("$.count").value(1))
		// 행은 정리 작업이 지울 때까지 남아 있다
		assertEquals(3, count("select count(*) from notifications where user_id = ?", me.userId))
	}

	@Test
	fun `FR-CAL-01 AC4 unread-count는 30일 안의 안 읽은 알림만 센다`() {
		val me = startGuest()
		val now = clock.instant()
		insertNotificationRow(me.userId)
		insertNotificationRow(me.userId, createdAt = now.minusSeconds(10))
		insertNotificationRow(me.userId, readAt = now) // 읽음
		insertNotificationRow(me.userId, createdAt = now.minus(thirtyDays).minusSeconds(1)) // 30일 지남

		getUnreadCount(me.accessToken).andExpect(status().isOk).andExpect(jsonPath("$.count").value(2))
	}

	@Test
	fun `FR-CAL-01 AC4 알림이 없으면 unread-count는 0이다`() {
		val me = startGuest()

		getUnreadCount(me.accessToken).andExpect(status().isOk).andExpect(jsonPath("$.count").value(0))
	}

	@Test
	fun `FR-NOTI-05 AC3 읽음 처리는 204이고 다시 불러도 read_at이 그대로다`() {
		val me = startGuest()
		val id = insertNotificationRow(me.userId)

		putRead(me.accessToken, id).andExpect(status().isNoContent)
		val firstReadAt = readAtOf(id)
		assertEquals(clock.instant(), firstReadAt)

		clock.advance(Duration.ofMinutes(5))
		val refreshed = refreshed(me)
		putRead(refreshed.accessToken, id).andExpect(status().isNoContent)

		assertEquals(firstReadAt, readAtOf(id))
		getUnreadCount(refreshed.accessToken).andExpect(jsonPath("$.count").value(0))
		getNotifications(refreshed.accessToken).andExpect(jsonPath("$.items[0].read").value(true))
	}

	@Test
	fun `D-142 이미 읽은 알림의 읽음 처리도 204이고 read_at을 바꾸지 않는다`() {
		val me = startGuest()
		val earlier = clock.instant().minusSeconds(3600)
		val id = insertNotificationRow(me.userId, readAt = earlier)

		putRead(me.accessToken, id).andExpect(status().isNoContent)

		assertEquals(earlier, readAtOf(id))
	}

	@Test
	fun `FR-NOTI-05 AC3 30일이 지난 알림의 읽음 처리는 404 NOT_FOUND다`() {
		val me = startGuest()
		val id = insertNotificationRow(me.userId, createdAt = clock.instant().minus(thirtyDays).minusSeconds(1))

		putRead(me.accessToken, id).expectError(404, "NOT_FOUND")

		assertNull(readAtOf(id))
	}

	@Test
	fun `R-12 본인의 알림만 보이고 읽음 처리된다`() {
		val a = startGuest()
		val b = startGuest()
		val mine = insertNotificationRow(a.userId, title = "에이 것")
		insertNotificationRow(b.userId, title = "비 것")

		getNotifications(a.accessToken)
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].id").value(mine.toString()))
		putRead(a.accessToken, mine).andExpect(status().isNoContent)
		assertNotNull(readAtOf(mine))
	}

	@Test
	fun `R-12 다른 사람의 알림은 목록 개수에 없고 읽음 처리는 404다`() {
		val a = startGuest()
		val b = startGuest()
		val others = insertNotificationRow(b.userId)

		getNotifications(a.accessToken).andExpect(jsonPath("$.items.length()").value(0))
		getUnreadCount(a.accessToken).andExpect(jsonPath("$.count").value(0))
		// 남의 알림, 없는 ID, UUID 형식이 아닌 ID는 같은 404다
		putRead(a.accessToken, others).expectError(404, "NOT_FOUND")
		putRead(a.accessToken, UUID.randomUUID()).expectError(404, "NOT_FOUND")
		putRead(a.accessToken, "not-a-uuid").expectError(404, "NOT_FOUND")

		assertNull(readAtOf(others))
		getUnreadCount(b.accessToken).andExpect(jsonPath("$.count").value(1))
	}

	@Test
	fun `FR-NOTI-05 AC4 같은 공간의 상대에게도 내 알림은 보이지 않는다`() {
		val couple = newCouple()
		val mine = insertNotificationRow(couple.a.userId)

		// 연결 알림(각자 하나)만 있고, 에이가 넣은 행은 비비에게 없다
		val bIds = jdbc.queryForList("select id from notifications where user_id = ?", UUID::class.java, couple.b.userId)
		assertEquals(false, mine in bIds)
		putRead(couple.b.accessToken, mine).expectError(404, "NOT_FOUND")
	}

	@Test
	fun `FR-NOTI-05 AC1 알림 API는 access 토큰이 없으면 401이다`() {
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("$API/notifications"))
			.andExpect(status().isUnauthorized)
	}
}
