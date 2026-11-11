package com.duriseo.server.event.application

import com.duriseo.server.event.domain.ReminderOption
import java.time.Instant
import java.time.ZoneId

/**
 * 일정 알림 본문 (PRD 8장 "일정 알림의 [알림 시점] 문구", D-139, API.md 7장 "일정 알림").
 * "[알림 시점] · [시작 시각]": [시작 시각]은 "오후 7:30" 형식이고 알림을 설정한 본인의 시간대로 쓴다. 종일 일정은 문구 그대로다(시각 없음).
 */
object EventReminderTexts {

	private const val SEPARATOR = " · "
	private const val ALL_DAY_TODAY = "오늘 · 하루 종일"
	private const val ALL_DAY_TOMORROW = "내일 · 하루 종일"

	/** [startAt]은 시간 일정 옵션일 때만 쓴다(종일 옵션은 null이어도 된다). [ownerZone]은 알림 주인의 `users.time_zone` */
	fun body(option: ReminderOption, startAt: Instant?, ownerZone: ZoneId): String = when (option) {
		ReminderOption.AT_TIME -> "지금$SEPARATOR${startTime(requireStart(startAt), ownerZone)}"
		ReminderOption.MIN_10 -> "10분 뒤$SEPARATOR${startTime(requireStart(startAt), ownerZone)}"
		ReminderOption.HOUR_1 -> "1시간 뒤$SEPARATOR${startTime(requireStart(startAt), ownerZone)}"
		ReminderOption.DAY_1 -> "내일$SEPARATOR${startTime(requireStart(startAt), ownerZone)}"
		ReminderOption.ALLDAY_9AM -> ALL_DAY_TODAY
		ReminderOption.ALLDAY_PREV_9AM -> ALL_DAY_TOMORROW
	}

	/** "오후 7:30": 오전·오후, 시는 앞자리 0 없는 12시간제(0시와 12시는 12), 분은 두 자리. 로케일 데이터에 기대지 않고 직접 쓴다 */
	fun startTime(startAt: Instant, zone: ZoneId): String {
		val local = startAt.atZone(zone).toLocalTime()
		val meridiem = if (local.hour < 12) "오전" else "오후"
		val hour = if (local.hour % 12 == 0) 12 else local.hour % 12
		return "$meridiem $hour:${local.minute.toString().padStart(2, '0')}"
	}

	private fun requireStart(startAt: Instant?): Instant = checkNotNull(startAt) { "timed reminder option needs start_at" }
}
