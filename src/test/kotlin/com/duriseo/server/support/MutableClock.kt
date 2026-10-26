package com.duriseo.server.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** 테스트에서 시각을 고정하고 앞으로 돌리는 Clock (CONVENTIONS: 시간이 걸리는 테스트는 Clock을 고정) */
class MutableClock(initial: Instant) : Clock() {

	@Volatile
	private var current: Instant = initial

	fun set(instant: Instant) {
		current = instant
	}

	fun advance(duration: Duration) {
		current = current.plus(duration)
	}

	override fun getZone(): ZoneId = ZoneOffset.UTC

	override fun withZone(zone: ZoneId): Clock = this

	override fun instant(): Instant = current
}

/** 통합 테스트가 `@Import` 한다. 운영 `Clock` 빈 대신 이 시계를 쓴다 */
@TestConfiguration(proxyBeanMethods = false)
class TestClockConfig {

	@Bean
	@Primary
	fun testClock(): MutableClock = MutableClock(IntegrationTestSupport.START)
}
