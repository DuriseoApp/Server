package com.duriseo.server.support

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * 테스트 동안 남은 로그를 모은다 (토큰·이메일이 로그에 없는지 확인용, NFR-SEC-01, NFR-PRIV-01).
 * 서버 코드(`com.duriseo.server`)는 DEBUG까지 모은다 (dev 환경과 같다). `use { }`로 쓰면 끝난 뒤 원래대로 돌린다.
 */
class LogCapture : AutoCloseable {

	private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
	private val serverLogger = LoggerFactory.getLogger("com.duriseo.server") as Logger
	private val previousServerLevel: Level? = serverLogger.level
	private val appender = ListAppender<ILoggingEvent>()

	init {
		appender.start()
		root.addAppender(appender)
		serverLogger.level = Level.DEBUG
	}

	/** 지금까지 남은 로그 메시지와 예외 메시지를 한 문자열로 */
	fun text(): String = appender.list.joinToString("\n") { event ->
		val throwable = event.throwableProxy?.let { " | ${it.className}: ${it.message}" }.orEmpty()
		"${event.loggerName} ${event.formattedMessage}$throwable"
	}

	override fun close() {
		root.detachAppender(appender)
		appender.stop()
		serverLogger.level = previousServerLevel
	}
}
