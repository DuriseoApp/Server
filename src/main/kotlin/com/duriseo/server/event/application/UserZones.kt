package com.duriseo.server.event.application

import java.time.DateTimeException
import java.time.ZoneId

/** `users.time_zone` 문자열을 시간대로 바꾼다. 해석할 수 없으면 UTC다 (서버 시간대는 쓰지 않는다, NFR-TIME-01) */
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
