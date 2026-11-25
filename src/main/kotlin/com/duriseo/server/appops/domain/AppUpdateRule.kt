package com.duriseo.server.appops.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * 강제·권장 업데이트 규칙 (ERD 3.4 app_update_rules, FR-APP-02). PK는 운영자가 정한 문자열이다 (DB-21).
 * `build_op`에 따라 쓰는 컬럼이 정해지고 나머지는 null이다 (조합 검사는 `AppOpsValidator`).
 */
@Entity
@Table(name = "app_update_rules")
class AppUpdateRule(
	id: String,
	mode: UpdateMode,
	platform: AppPlatform,
	buildOp: BuildOp,
	now: Instant,
) {

	@Id
	@Column(name = "id", nullable = false, updatable = false, length = 50)
	var id: String = id
		protected set

	@Enumerated(EnumType.STRING)
	@Column(name = "mode", nullable = false, length = 10)
	var mode: UpdateMode = mode

	@Enumerated(EnumType.STRING)
	@Column(name = "platform", nullable = false, length = 10)
	var platform: AppPlatform = platform

	@Enumerated(EnumType.STRING)
	@Column(name = "build_op", nullable = false, length = 5)
	var buildOp: BuildOp = buildOp

	@Column(name = "build_value")
	var buildValue: Int? = null

	@Column(name = "build_min")
	var buildMin: Int? = null

	@Column(name = "build_max")
	var buildMax: Int? = null

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "build_values", columnDefinition = "integer[]")
	var buildValues: List<Int>? = null

	@Column(name = "os_min")
	var osMin: Int? = null

	@Column(name = "os_max")
	var osMax: Int? = null

	@Column(name = "title", length = 40)
	var title: String? = null

	@Column(name = "body", length = 500)
	var body: String? = null

	@Column(name = "image", length = 500)
	var image: String? = null

	@Column(name = "image_alt", length = 100)
	var imageAlt: String? = null

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant = now

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = now
}
