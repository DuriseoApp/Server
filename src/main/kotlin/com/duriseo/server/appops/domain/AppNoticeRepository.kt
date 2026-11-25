package com.duriseo.server.appops.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface AppNoticeRepository : JpaRepository<AppNotice, String> {

	/** 전부, `created_at` 오래된 순 → `id` (관리자 조회) */
	@Query("select n from AppNotice n order by n.createdAt asc, n.id asc")
	fun findAllSorted(): List<AppNotice>

	/** 지금 유효한 것만 (D-176): `(starts_at IS NULL OR starts_at <= now) AND (ends_at IS NULL OR now < ends_at)` */
	@Query(
		"select n from AppNotice n where (n.startsAt is null or n.startsAt <= :now) and (n.endsAt is null or :now < n.endsAt) " +
			"order by n.createdAt asc, n.id asc",
	)
	fun findValidAt(@Param("now") now: Instant): List<AppNotice>
}
