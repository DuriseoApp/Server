package com.duriseo.server.user.application

import java.util.UUID

/**
 * 사용자의 시간대가 바뀌었다. 단위 3이 같은 트랜잭션의 리스너로 `event_reminders.fire_at`을 다시 계산한다 (ERD event_reminders).
 */
data class UserTimeZoneChanged(val userId: UUID, val previousTimeZone: String, val timeZone: String)
