package com.duriseo.server.event.application

import com.duriseo.server.event.domain.ReminderOption
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 일정 알림 시각 `fire_at` 계산 (API.md 3.3 "알림 시각 계산", PRD D-90, ERD event_reminders).
 *
 * - `AT_TIME` = `start_at`, `MIN_10` = 10분 전, `HOUR_1` = 1시간 전, `DAY_1` = 24시간 전(서머타임이 있는 날도 24시간)
 * - `ALLDAY_9AM` = `start_date` 09:00, `ALLDAY_PREV_9AM` = (`start_date` − 1일) 09:00. 둘 다 **알림 주인**의 시간대 기준이다.
 */
object EventReminderCalculator {

	private val NINE_AM: LocalTime = LocalTime.of(9, 0)

	/**
	 * [option]의 알림 시각. 시간 일정 옵션은 [startAt], 종일 일정 옵션은 [startDate]와 알림 주인의 시간대 [ownerZone]을 쓴다.
	 * 일정 종류와 맞는 값만 넘겨야 한다.
	 */
	fun fireAt(option: ReminderOption, startAt: Instant?, startDate: LocalDate?, ownerZone: ZoneId): Instant =
		when (option) {
			ReminderOption.AT_TIME -> requireStartAt(startAt)
			ReminderOption.MIN_10 -> requireStartAt(startAt).minus(Duration.ofMinutes(10))
			ReminderOption.HOUR_1 -> requireStartAt(startAt).minus(Duration.ofHours(1))
			ReminderOption.DAY_1 -> requireStartAt(startAt).minus(Duration.ofHours(24))
			ReminderOption.ALLDAY_9AM -> nineAm(requireStartDate(startDate), ownerZone)
			ReminderOption.ALLDAY_PREV_9AM -> nineAm(requireStartDate(startDate).minusDays(1), ownerZone)
		}

	/** 저장할 때의 `sent_at`: 계산한 시각이 지금 이전이면(같아도) 보내지 않기로 한 시각인 [now], 미래면 null (D-90) */
	fun sentAtFor(fireAt: Instant, now: Instant): Instant? = if (fireAt.isAfter(now)) null else now

	private fun nineAm(date: LocalDate, zone: ZoneId): Instant = date.atTime(NINE_AM).atZone(zone).toInstant()

	private fun requireStartAt(startAt: Instant?): Instant = checkNotNull(startAt) { "timed reminder option needs start_at" }

	private fun requireStartDate(startDate: LocalDate?): LocalDate = checkNotNull(startDate) { "all-day reminder option needs start_date" }
}
