package com.duriseo.server.user.domain

import com.duriseo.server.common.persistence.UuidEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant

/** 사용자 한 명 (ERD 3.1 users). 탈퇴하면 행을 지우지 않고 익명화한다 (2부) */
@Entity
@Table(name = "users")
class User(accountType: AccountType, nickname: String, timeZone: String, now: Instant) : UuidEntity() {

	@Enumerated(EnumType.STRING)
	@Column(name = "account_type", nullable = false, length = 10)
	var accountType: AccountType = accountType

	@Column(name = "nickname", nullable = false, length = 10)
	var nickname: String = nickname

	@Column(name = "profile_image_key", length = 200)
	var profileImageKey: String? = null

	@Column(name = "time_zone", nullable = false, length = 50)
	var timeZone: String = timeZone

	@Column(name = "deleted_at")
	var deletedAt: Instant? = null

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: Instant = now

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = now

	companion object {
		const val DEFAULT_TIME_ZONE = "Asia/Seoul"
	}
}
