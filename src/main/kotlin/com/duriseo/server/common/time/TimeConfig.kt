package com.duriseo.server.common.time

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 현재 시각은 이 Clock 빈으로만 읽는다 (ARCHITECTURE 6장). 테스트는 고정 Clock으로 바꾼다 */
@Configuration(proxyBeanMethods = false)
class TimeConfig {

	@Bean
	fun clock(): Clock = Clock.systemUTC()
}

/** 응답·DB·토큰에 쓰는 시각은 초 단위로 맞춘다 (JWT의 iat·exp가 초 단위라 서로 어긋나지 않게) */
fun Clock.nowInSeconds(): Instant = instant().truncatedTo(ChronoUnit.SECONDS)
