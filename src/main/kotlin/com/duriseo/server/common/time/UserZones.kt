package com.duriseo.server.common.time

import java.time.DateTimeException
import java.time.ZoneId

/**
 * `users.time_zone` 문자열을 시간대로 바꾼다. 해석할 수 없으면 UTC다 (서버 시간대는 쓰지 않는다, NFR-TIME-01).
 * 일정(단위 3)과 기념일(단위 4)이 함께 쓰므로 `common`에 둔다.
 */
object UserZones {

	private val UTC: ZoneId = ZoneId.of("UTC")

	fun parseOrUtc(timeZone: String?): ZoneId {
		if (timeZone == null) return UTC
		return try {
			ZoneId.of(timeZone)
		} catch (e: DateTimeException) {
			UTC
		}
	}
}
