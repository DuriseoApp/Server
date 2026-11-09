package com.duriseo.server.memo

import com.duriseo.server.support.IntegrationTestSupport
import com.duriseo.server.support.Tokens
import com.jayway.jsonpath.JsonPath
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

/**
 * 메모 API 테스트가 함께 쓰는 도구 (단위 5). 구체적인 테스트 클래스에 `@SpringBootTest`와
 * `@Import(TestcontainersConfig::class, TestClockConfig::class)`를 붙인다.
 * 새 사용자의 시간대는 `Asia/Seoul`(DB 기본값)이다. 시각은 `MutableClock`이 `2026-10-26T00:00:00Z`로 고정한다.
 */
abstract class MemoTestSupport : IntegrationTestSupport() {

	/** 연결된 커플 한 쌍: A("에이")와 B("비비") */
	protected class Couple(val a: Tokens, val b: Tokens, val spaceId: UUID)

	protected fun newCouple(): Couple {
		val a = startMember("에이")
		val b = startMember("비비")
		return Couple(a, b, connect(a, b))
	}

	// ── 요청 ────────────────────────────────────────────

	private fun auth(request: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder, token: String) =
		request.header("Authorization", "Bearer $token")

	/** `GET /memos`. null인 쿼리 파라미터는 보내지 않는다 */
	protected fun getMemos(token: String, view: String? = null, cursor: String? = null, size: String? = null): ResultActions {
		val request = auth(get("$API/memos"), token)
		if (view != null) request.param("view", view)
		if (cursor != null) request.param("cursor", cursor)
		if (size != null) request.param("size", size)
		return mockMvc.perform(request)
	}

	protected fun getPinnedMemos(token: String): ResultActions = mockMvc.perform(auth(get("$API/memos/pinned"), token))

	protected fun getDatedMemos(token: String, from: String?, to: String?): ResultActions {
		val request = auth(get("$API/memos/dated"), token)
		if (from != null) request.param("from", from)
		if (to != null) request.param("to", to)
		return mockMvc.perform(request)
	}

	/** `POST /memos` 본문을 그대로 보낸다. [json]이 null이면 본문 없이 보낸다 */
	protected fun postMemoRaw(token: String, json: String?): ResultActions {
		val request = auth(post("$API/memos"), token)
		return mockMvc.perform(if (json == null) request else request.contentType(MediaType.APPLICATION_JSON).content(json))
	}

	protected fun getMemo(token: String, id: Any): ResultActions = mockMvc.perform(auth(get("$API/memos/$id"), token))

	protected fun patchMemo(token: String, id: Any, json: String): ResultActions =
		mockMvc.perform(auth(patch("$API/memos/$id"), token).contentType(MediaType.APPLICATION_JSON).content(json))

	protected fun deleteMemo(token: String, id: Any): ResultActions = mockMvc.perform(auth(delete("$API/memos/$id"), token))

	protected fun putPin(token: String, id: Any): ResultActions = mockMvc.perform(auth(put("$API/memos/$id/pin"), token))

	protected fun deletePin(token: String, id: Any): ResultActions = mockMvc.perform(auth(delete("$API/memos/$id/pin"), token))

	protected fun putFavorite(token: String, id: Any): ResultActions = mockMvc.perform(auth(put("$API/memos/$id/favorite"), token))

	protected fun deleteFavorite(token: String, id: Any): ResultActions = mockMvc.perform(auth(delete("$API/memos/$id/favorite"), token))

	/** `POST /memos`가 201로 끝난 것을 확인하고 메모 ID를 돌려준다 */
	protected fun createMemo(token: String, json: String): UUID {
		val body = bodyOf(postMemoRaw(token, json).andExpect(status().isCreated))
		return UUID.fromString(JsonPath.read<String>(body, "$.id"))
	}

	// ── 확인 ────────────────────────────────────────────

	protected fun expectField(result: ResultActions, index: Int, field: String, reason: String): ResultActions =
		result
			.andExpect(jsonPath("$.details.fields[$index].field").value(field))
			.andExpect(jsonPath("$.details.fields[$index].reason").value(reason))

	/** 응답 `details.fields`의 (field, reason) 쌍들 */
	protected fun fieldsOf(result: ResultActions): List<Pair<String, String>> {
		val body = bodyOf(result)
		val fields = JsonPath.read<List<String>>(body, "$.details.fields[*].field")
		val reasons = JsonPath.read<List<String>>(body, "$.details.fields[*].reason")
		return fields.zip(reasons)
	}

	/** 응답 본문의 `items` 한 필드를 순서대로 모은다 */
	protected fun itemsOf(result: ResultActions, field: String): List<Any?> =
		JsonPath.read<List<Map<String, Any?>>>(bodyOf(result), "$.items").map { it[field] }

	/** 응답 본문의 `items[*].id`를 순서대로 */
	protected fun idsOf(result: ResultActions): List<UUID> = itemsOf(result, "id").map { UUID.fromString(it as String) }

	protected fun memoCount(): Int = count("select count(*) from memos")

	/** 메모 테이블의 내용을 한 문자열로 모은다. 거부된 요청이 "아무것도 바꾸지 않았다"를 확인한다 */
	protected fun memoSnapshot(): String =
		jdbc.queryForList("select * from memos order by id").toString() + jdbc.queryForList("select * from memo_favorites order by memo_id, user_id")

	/** 데이터베이스의 메모 한 행 */
	protected fun memoRow(id: UUID): Map<String, Any?> =
		jdbc.queryForMap(
			"select title, body, memo_date::text as memo_date, author_id, space_id, pinned_at, pinned_by, created_at, updated_at from memos where id = ?",
			id,
		)

	protected fun notificationsOf(userId: UUID, type: String): List<Map<String, Any?>> =
		jdbc.queryForList("select * from notifications where user_id = ? and type = ? order by id", userId, type)

	/** 메모 한 행을 마음대로 넣는다. 고정은 [pinnedAt]과 [pinnedBy]를 함께 준다 */
	protected fun insertMemoRow(
		spaceId: UUID,
		authorId: UUID,
		title: String? = "테스트 메모",
		body: String? = null,
		memoDate: String? = null,
		createdAt: Instant = clock.instant(),
		pinnedAt: Instant? = null,
		pinnedBy: UUID? = null,
		id: UUID = UUID.randomUUID(),
	): UUID {
		jdbc.update(
			"insert into memos (id, space_id, author_id, title, body, memo_date, pinned_at, pinned_by, created_at, updated_at) " +
				"values (?, ?, ?, ?, ?, cast(? as date), ?, ?, ?, ?)",
			id, spaceId, authorId, title, body, memoDate, pinnedAt?.let { ts(it) }, pinnedBy, ts(createdAt), ts(createdAt),
		)
		return id
	}
}
