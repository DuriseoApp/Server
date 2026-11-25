package com.duriseo.server.appops.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface AppUpdateRuleRepository : JpaRepository<AppUpdateRule, String> {

	/** 전부, `created_at` 오래된 순 → `id` */
	@Query("select r from AppUpdateRule r order by r.createdAt asc, r.id asc")
	fun findAllSorted(): List<AppUpdateRule>
}
