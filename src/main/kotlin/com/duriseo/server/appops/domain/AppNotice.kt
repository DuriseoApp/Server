package com.duriseo.server.appops.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 공지 팝업 (ERD 3.4 app_notices, FR-APP-01). PK는 운영자가 정한 문자열이다 (DB-21).
 * 제목·본문·이미지 조합과 기간 순서는 DB가 아니라 `AppOpsValidator`가 검사한다.
 */
@Entity
@Table(name = "app_notices")
class AppNotice(
	id: String,
	closable: Boolean,
	now: Instant,
) {

	@Id
	@Column(name = "id", nullable = false, updatable = false, length = 50)
	var id: String = id
		protected set

	@Column(name = "closable", nullable = false)
	var closable: Boolean = closable

	@Column(name = "title", length = 40)
	var title: String? = null

	@Column(name = "body", length = 500)
	var body: String? = null

	@Column(name = "image", length = 500)
	var image: String? = null

	@Column(name = "image_alt", length = 100)
	var imageAlt: String? = null

	@Column(name = "link_text", length = 20)
	var linkText: String? = null

	@Column(name = "link_url", length = 500)
	var linkUrl: String? = null

	@Enumerated(EnumType.STRING)
	@Column(name = "platform", length = 10)
	var platform: AppPlatform? = null

	@Column(name = "starts_at")
	var startsAt: Instant? = null

	@Column(name = "ends_at")
	var endsAt: Instant? = null

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant = now

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = now
}
