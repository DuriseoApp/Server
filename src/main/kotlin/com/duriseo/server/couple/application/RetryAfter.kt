package com.duriseo.server.couple.application

import java.time.Duration
import java.time.Instant

/** `details.retryAfterSeconds`: [until]까지 남은 시간을 초 단위로 올림한 값. 최소 1 (API.md 4.4) */
object RetryAfter {

	fun seconds(until: Instant, now: Instant): Long {
		val millis = Duration.between(now, until).toMillis()
		val seconds = (millis + 999) / 1000
		return if (seconds < 1) 1 else seconds
	}
}
