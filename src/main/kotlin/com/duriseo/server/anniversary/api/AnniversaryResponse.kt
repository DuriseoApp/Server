package com.duriseo.server.anniversary.api

import com.duriseo.server.anniversary.application.AnniversaryKind
import com.duriseo.server.anniversary.application.AnniversaryOccurrence
import com.duriseo.server.anniversary.application.AnniversaryView
import com.duriseo.server.anniversary.application.PastAnniversary
import com.duriseo.server.anniversary.application.PastPage
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** API.md 3.3 `Anniversary`: `POST`·`PATCH /anniversaries`의 응답 본문. `updatedBy`는 없다 */
data class AnniversaryResponse(
	val id: UUID,
	val name: String,
	val date: LocalDate,
	val repeatsYearly: Boolean,
	val authorId: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
) {
	companion object {
		fun from(view: AnniversaryView) = AnniversaryResponse(
			view.id, view.name, view.date, view.repeatsYearly, view.authorId, view.createdAt, view.updatedAt,
		)
	}
}

/** API.md 3.3 `AnniversaryOccurrence`: 캘린더용 `GET /anniversaries`와 `GET /anniversaries/upcoming`의 항목. 값이 없는 필드도 `null`로 보낸다 */
data class AnniversaryOccurrenceResponse(
	val anniversaryId: UUID?,
	val kind: AnniversaryKind,
	val name: String,
	val date: LocalDate,
	val daysLeft: Long,
	val repeatsYearly: Boolean,
	val authorId: UUID?,
) {
	companion object {
		fun from(occurrence: AnniversaryOccurrence) = AnniversaryOccurrenceResponse(
			occurrence.anniversaryId,
			occurrence.kind,
			occurrence.name,
			occurrence.date,
			occurrence.daysLeft,
			occurrence.repeatsYearly,
			occurrence.authorId,
		)
	}
}

/** `GET /anniversaries/past` 항목 `{anniversaryId, name, date, authorId}` */
data class PastAnniversaryResponse(val anniversaryId: UUID, val name: String, val date: LocalDate, val authorId: UUID) {
	companion object {
		fun from(item: PastAnniversary) = PastAnniversaryResponse(item.anniversaryId, item.name, item.date, item.authorId)
	}
}

/** `GET /anniversaries/past`: `{items, nextCursor, totalCount}` */
data class PastPageResponse(val items: List<PastAnniversaryResponse>, val nextCursor: String?, val totalCount: Long) {
	companion object {
		fun from(page: PastPage) = PastPageResponse(page.items.map { PastAnniversaryResponse.from(it) }, page.nextCursor, page.totalCount)
	}
}
