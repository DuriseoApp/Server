package com.duriseo.server.anniversary

import com.duriseo.server.support.IntegrationTestSupport
import com.duriseo.server.support.Tokens
import java.util.UUID

/**
 * 기념일 API 테스트가 함께 쓰는 도구 (단위 4). 구체적인 테스트 클래스에 `@SpringBootTest`와
 * `@Import(TestcontainersConfig::class, TestClockConfig::class)`를 붙인다.
 * 새 사용자의 시간대는 `Asia/Seoul`(DB 기본값)이다. 시각은 `MutableClock`이 `2026-10-26T00:00:00Z`로 고정한다(서울 2026-10-26 09:00).
 */
abstract class AnniversaryTestSupport : IntegrationTestSupport() {

	/** 연결된 커플 한 쌍: A("에이")와 B("비비") */
	protected class Couple(val a: Tokens, val b: Tokens, val spaceId: UUID)

	protected fun newCouple(): Couple {
		val a = startMember("에이")
		val b = startMember("비비")
		return Couple(a, b, connect(a, b))
	}

	protected fun setTimeZone(tokens: Tokens, timeZone: String) {
		jdbc.update("update users set time_zone = ? where id = ?", timeZone, tokens.userId)
	}

	/** 커플 공간의 처음 만난 날을 직접 정한다 (`PATCH /space`는 미래·검사 규칙이 있어 픽스처는 직접 넣는다) */
	protected fun setStartDate(spaceId: UUID, startDate: String) {
		jdbc.update("update spaces set start_date = cast(? as date) where id = ?", startDate, spaceId)
	}

	/** 직접 기념일 테이블의 내용을 한 문자열로 모은다. 거부된 요청이 "아무것도 바꾸지 않았다"를 확인한다 */
	protected fun anniversarySnapshot(): String = jdbc.queryForList("select * from anniversaries order by id").toString()

	protected fun anniversaryCount(): Int = count("select count(*) from anniversaries")
}
