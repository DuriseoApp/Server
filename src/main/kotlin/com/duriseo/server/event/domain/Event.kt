package com.duriseo.server.event.domain

import com.duriseo.server.common.persistence.UuidEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 일정 (ERD 3.3 events). 종일이면 `start_date`·`end_date`만, 아니면 `start_at`·`end_at`만 값이 있다 (`ck_events_time_columns`).
 * 날짜 범위(1950-01-01 ~ 2050-12-31)는 DB가 아니라 서비스가 검사한다 (DB-13).
 */
@Entity
@Table(name = "events")
class Event(
	spaceId: UUID,
	authorId: UUID,
	isTogether: Boolean,
	title: String,
	note: String?,
	isAllDay: Boolean,
	startAt: Instant?,
	endAt: Instant?,
	startDate: LocalDate?,
	endDate: LocalDate?,
	now: Instant,
) : UuidEntity() {

	@Column(name = "space_id", nullable = false)
	var spaceId: UUID = spaceId

	@Column(name = "author_id", nullable = false, updatable = false)
	var authorId: UUID = authorId

	@Column(name = "is_together", nullable = false)
	var isTogether: Boolean = isTogether

	@Column(name = "title", nullable = false, length = 50)
	var title: String = title

	@Column(name = "note", length = 1000)
	var note: String? = note

	@Column(name = "is_all_day", nullable = false)
	var isAllDay: Boolean = isAllDay

	@Column(name = "start_at")
	var startAt: Instant? = startAt

	@Column(name = "end_at")
	var endAt: Instant? = endAt

	@Column(name = "start_date")
	var startDate: LocalDate? = startDate

	@Column(name = "end_date")
	var endDate: LocalDate? = endDate

	/** 마지막 수정자. 만든 뒤 수정한 적이 없으면 null (ERD 3.3, API.md `Event.updatedBy`) */
	@Column(name = "updated_by")
	var updatedBy: UUID? = null

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant = now

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = now

	/** 시간 필드를 한꺼번에 바꾼다. 종일이면 날짜만, 아니면 일시만 남겨 `ck_events_time_columns`를 지킨다 */
	fun changeTime(isAllDay: Boolean, startAt: Instant?, endAt: Instant?, startDate: LocalDate?, endDate: LocalDate?) {
		this.isAllDay = isAllDay
		this.startAt = if (isAllDay) null else startAt
		this.endAt = if (isAllDay) null else endAt
		this.startDate = if (isAllDay) startDate else null
		this.endDate = if (isAllDay) endDate else null
	}
}
