package com.duriseo.server.support

import com.jayway.jsonpath.JsonPath
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

/**
 * 알림 목록·설정(묶음 A)과 푸시·동기화 신호(묶음 B) 테스트가 함께 쓰는 도구 (단위 6). 구체적인 테스트 클래스에 `@SpringBootTest`와
 * `@Import(TestcontainersConfig::class, TestClockConfig::class)`를 붙인다. 발송기는 `RecordingPushSender`(`push`)다.
 * 새 사용자의 시간대는 `Asia/Seoul`, 시각은 `MutableClock`이 `2026-10-26T00:00:00Z`로 고정한다.
 */
abstract class NotificationTestSupport : IntegrationTestSupport() {

	/** 연결된 커플 한 쌍. A("에이")의 기기 토큰은 [TOKEN_A], B("비비")는 [TOKEN_B]다. 연결·등록 때 간 메시지는 지워 둔다 */
	protected class Couple(val a: Tokens, val b: Tokens, val spaceId: UUID)

	/** 두 사람 모두 기기를 등록한 뒤 연결한다. 연결로 간 메시지는 [push]에서 지운다 */
	protected fun newCouple(): Couple {
		val a = startMember("에이")
		val b = startMember("비비")
		registerDevice(a, TOKEN_A)
		registerDevice(b, TOKEN_B)
		val spaceId = connect(a, b)
		push.reset()
		return Couple(a, b, spaceId)
	}

	protected fun registerDevice(tokens: Tokens, fcmToken: String) {
		putDevice(tokens.accessToken, """{"fcmToken":"$fcmToken"}""").andExpect(status().isNoContent)
	}

	// ── 요청 ────────────────────────────────────────────

	private fun auth(request: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder, token: String) =
		request.header("Authorization", "Bearer $token")

	/** `GET /notifications`. null인 쿼리 파라미터는 보내지 않는다 */
	protected fun getNotifications(token: String, cursor: String? = null, size: String? = null): ResultActions {
		val request = auth(get("$API/notifications"), token)
		if (cursor != null) request.param("cursor", cursor)
		if (size != null) request.param("size", size)
		return mockMvc.perform(request)
	}

	protected fun getUnreadCount(token: String): ResultActions = mockMvc.perform(auth(get("$API/notifications/unread-count"), token))

	protected fun putRead(token: String, id: Any): ResultActions = mockMvc.perform(auth(put("$API/notifications/$id/read"), token))

	protected fun getSettings(token: String): ResultActions = mockMvc.perform(auth(get("$API/me/notification-settings"), token))

	protected fun patchSettings(token: String, json: String): ResultActions =
		mockMvc.perform(auth(patch("$API/me/notification-settings"), token).contentType(MediaType.APPLICATION_JSON).content(json))

	protected fun postMemo(token: String, json: String): UUID {
		val body = bodyOf(
			mockMvc.perform(auth(post("$API/memos"), token).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isCreated),
		)
		return UUID.fromString(JsonPath.read<String>(body, "$.id"))
	}

	protected fun patchMemoJson(token: String, id: Any, json: String): ResultActions =
		mockMvc.perform(auth(patch("$API/memos/$id"), token).contentType(MediaType.APPLICATION_JSON).content(json))

	protected fun deleteMemoOf(token: String, id: Any): ResultActions = mockMvc.perform(auth(delete("$API/memos/$id"), token))

	protected fun pinMemo(token: String, id: Any): ResultActions = mockMvc.perform(auth(put("$API/memos/$id/pin"), token))

	protected fun unpinMemo(token: String, id: Any): ResultActions = mockMvc.perform(auth(delete("$API/memos/$id/pin"), token))

	protected fun favoriteMemo(token: String, id: Any): ResultActions = mockMvc.perform(auth(put("$API/memos/$id/favorite"), token))

	protected fun allDayEventJson(title: String = "회식", start: String = "2026-11-03", extra: String = ""): String =
		"""{"title":"$title","isAllDay":true,"start":"$start","end":"$start"$extra}"""

	// ── DB ──────────────────────────────────────────────

	/** 알림 행 하나를 마음대로 넣는다 */
	protected fun insertNotificationRow(
		userId: UUID,
		createdAt: Instant = clock.instant(),
		type: String = "PARTNER_CREATED",
		title: String = "테스트 알림",
		body: String? = null,
		readAt: Instant? = null,
		id: UUID = UUID.randomUUID(),
	): UUID {
		jdbc.update(
			"insert into notifications (id, user_id, type, title, body, read_at, created_at) values (?, ?, ?, ?, ?, ?, ?)",
			id, userId, type, title, body, readAt?.let { ts(it) }, ts(createdAt),
		)
		return id
	}

	protected fun notificationRows(userId: UUID, type: String): List<Map<String, Any?>> =
		jdbc.queryForList("select * from notifications where user_id = ? and type = ? order by id", userId, type)

	protected fun readAtOf(id: UUID): Instant? =
		jdbc.queryForObject("select read_at from notifications where id = ?", java.sql.Timestamp::class.java, id)?.toInstant()

	/** 사용자의 세션을 모두 무효화한다 (R-16 거부 조건). 기기 행은 그대로 둔다 */
	protected fun revokeSessions(userId: UUID) {
		jdbc.update("update auth_sessions set revoked_at = ?, revoked_reason = 'LOGOUT' where user_id = ?", ts(clock.instant()), userId)
	}

	companion object {
		const val TOKEN_A = "fcm-token-a-fake"
		const val TOKEN_B = "fcm-token-b-fake"
	}
}
