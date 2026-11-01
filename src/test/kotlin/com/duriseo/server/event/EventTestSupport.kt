package com.duriseo.server.event

import com.duriseo.server.support.IntegrationTestSupport
import com.duriseo.server.support.Tokens
import com.jayway.jsonpath.JsonPath
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * 일정 API 테스트가 함께 쓰는 도구 (단위 3). 구체적인 테스트 클래스에 `@SpringBootTest`와
 * `@Import(TestcontainersConfig::class, TestClockConfig::class)`를 붙인다.
 * 새 사용자의 시간대는 `Asia/Seoul`(DB 기본값)이다. 시각은 `MutableClock`이 `2026-10-26T00:00:00Z`로 고정한다.
 */
abstract class EventTestSupport : IntegrationTestSupport() {

	/** 연결된 커플 한 쌍: A("에이")와 B("비비") */
	protected class Couple(val a: Tokens, val b: Tokens, val spaceId: UUID)

	protected fun newCouple(): Couple {
		val a = startMember("에이")
		val b = startMember("비비")
		return Couple(a, b, connect(a, b))
	}

	/** 종일 일정 본문. [extra]는 `,"isTogether":true`처럼 `,`로 시작하는 추가 필드 */
	protected fun allDayJson(title: String = "회식", start: String = "2026-11-03", end: String = start, extra: String = ""): String =
		"""{"title":"$title","isAllDay":true,"start":"$start","end":"$end"$extra}"""

	/** 시간 일정 본문. 시작·종료는 UTC 일시다 */
	protected fun timedJson(
		title: String = "미팅",
		start: String = "2026-11-03T01:00:00Z",
		end: String = "2026-11-03T02:00:00Z",
		extra: String = "",
	): String = """{"title":"$title","isAllDay":false,"start":"$start","end":"$end"$extra}"""

	protected fun expectField(result: ResultActions, index: Int, field: String, reason: String): ResultActions =
		result
			.andExpect(jsonPath("$.details.fields[$index].field").value(field))
			.andExpect(jsonPath("$.details.fields[$index].reason").value(reason))

	/** 오류 응답 `details.fields`의 `field` 이름들 */
	protected fun fieldNamesOf(result: ResultActions): List<String> =
		JsonPath.read(bodyOf(result), "$.details.fields[*].field")

	protected fun setTimeZone(tokens: Tokens, timeZone: String) {
		jdbc.update("update users set time_zone = ? where id = ?", timeZone, tokens.userId)
	}

	protected fun eventCount(): Int = count("select count(*) from events")

	/** 일정과 일정 알림 테이블의 내용을 한 문자열로 모은다. 거부된 요청이 "아무것도 바꾸지 않았다"를 확인한다 */
	protected fun eventSnapshot(): String = listOf(
		"select * from events order by id",
		"select * from event_reminders order by id",
		"select id, user_id, type, title, body, target_type, target_id from notifications order by id",
	).joinToString("\n") { sql -> jdbc.queryForList(sql).toString() }

	/** 한 사용자가 한 일정에 건 알림의 `fire_at`. 행이 없으면 실패한다 */
	protected fun fireAtOf(eventId: UUID, userId: UUID): Instant =
		instantOf("select fire_at from event_reminders where event_id = ? and user_id = ?", eventId, userId)

	/** 한 사용자가 한 일정에 건 알림의 `sent_at`. 아직 안 보냈으면 null */
	protected fun sentAtOf(eventId: UUID, userId: UUID): Instant? =
		jdbc.queryForObject("select sent_at from event_reminders where event_id = ? and user_id = ?", Timestamp::class.java, eventId, userId)
			?.toInstant()

	protected fun optionOf(eventId: UUID, userId: UUID): String =
		jdbc.queryForObject("select option from event_reminders where event_id = ? and user_id = ?", String::class.java, eventId, userId)!!

	protected fun reminderCount(eventId: UUID, userId: UUID): Int =
		count("select count(*) from event_reminders where event_id = ? and user_id = ?", eventId, userId)

	protected fun titleOf(eventId: UUID): String = jdbc.queryForObject("select title from events where id = ?", String::class.java, eventId)!!
}
