package com.duriseo.server.job

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 서버가 준비되면 기념일 알림 작업을 한 번 더 돌린다 (API.md 7장 "기념일 알림", D-138 "오늘 아직 안 보낸 사용자를 매시 처리한다").
 * 작업이 매시 정각(UTC)에만 돌면 한국 오전 9시(00:00 UTC)에 재시작(배포)이 걸릴 때 그날 알림이 통째로 빠지기 때문이다.
 * 이미 보낸 사용자·기념일은 DB-18 규칙(같은 날 같은 (user, type, title) 행)이 건너뛰므로 여러 번 돌아도 한 번만 간다.
 * 스케줄러가 꺼져 있으면(`duriseo.jobs.enabled=false`, 테스트) 하지 않는다. 어떤 오류도 시작을 막지 않는다.
 */
@Component
class AnniversaryStartupRunner(
	private val job: AnniversaryReminderJob,
	@Value("\${duriseo.jobs.enabled:true}") private val jobsEnabled: Boolean,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	/** 돌렸으면 true. 켜져 있지 않거나 실패했으면 false (테스트가 직접 부른다) */
	@EventListener(ApplicationReadyEvent::class)
	fun onReady(): Boolean {
		if (!jobsEnabled) return false
		return try {
			val created = job.run()
			log.info("anniversary job ran at startup: {} notification(s) created", created)
			true
		} catch (e: Exception) {
			log.error("anniversary job at startup failed", e)
			false
		}
	}
}
