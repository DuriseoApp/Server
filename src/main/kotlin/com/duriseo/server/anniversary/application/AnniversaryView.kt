package com.duriseo.server.anniversary.application

import com.duriseo.server.anniversary.domain.Anniversary
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** API.md 3.3 `Anniversary` (직접 기념일, `POST`·`PATCH` 응답). `updatedBy`는 없다 */
data class AnniversaryView(
	val id: UUID,
	val name: String,
	val date: LocalDate,
	val repeatsYearly: Boolean,
	val authorId: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
) {
	companion object {
		fun of(anniversary: Anniversary) = AnniversaryView(
			id = anniversary.requireId(),
			name = anniversary.name,
			date = anniversary.date,
			repeatsYearly = anniversary.repeatsYearly,
			authorId = anniversary.authorId,
			createdAt = anniversary.createdAt,
			updatedAt = anniversary.updatedAt,
		)
	}
}
