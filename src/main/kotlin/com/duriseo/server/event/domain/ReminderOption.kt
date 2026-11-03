package com.duriseo.server.event.domain

/**
 * 일정 알림 `option` (API.md 3.3 "일정 알림 `option`", ERD `ck_event_reminders_option`). 값 이름은 문서 그대로다.
 * "없음"은 값이 아니라 행이 없는 것이다.
 */
enum class ReminderOption(val forAllDay: Boolean) {
	AT_TIME(false),
	MIN_10(false),
	HOUR_1(false),
	DAY_1(false),
	ALLDAY_9AM(true),
	ALLDAY_PREV_9AM(true),
}
