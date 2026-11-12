package com.duriseo.server.cleanup

import com.duriseo.server.job.AnniversaryReminderJob
import com.duriseo.server.job.CleanupJob
import com.duriseo.server.job.EventReminderJob
import org.junit.jupiter.api.Test
import org.springframework.scheduling.annotation.Scheduled
import kotlin.test.assertEquals

/** 스케줄 주기 (API.md 7장 표, ERD DB-03). 작업은 `fixedDelay`·`cron`이라 같은 작업이 겹쳐 돌지 않는다 */
class JobScheduleTest {

	private fun scheduled(type: Class<*>) = type.getMethod("scheduledRun").getAnnotation(Scheduled::class.java)!!

	@Test
	fun `7장 일정 알림은 1분마다 이전 실행이 끝난 뒤 다시 돈다`() {
		val schedule = scheduled(EventReminderJob::class.java)
		assertEquals(60_000L, schedule.fixedDelay)
		assertEquals("", schedule.cron)
	}

	@Test
	fun `7장 기념일 알림은 매시 정각에 돈다`() {
		val schedule = scheduled(AnniversaryReminderJob::class.java)
		assertEquals("0 0 * * * *", schedule.cron)
	}

	@Test
	fun `7장 정리 작업은 매일 03시 서울 시간에 돈다`() {
		val schedule = scheduled(CleanupJob::class.java)
		assertEquals("0 0 3 * * *", schedule.cron)
		assertEquals("Asia/Seoul", schedule.zone)
	}
}
