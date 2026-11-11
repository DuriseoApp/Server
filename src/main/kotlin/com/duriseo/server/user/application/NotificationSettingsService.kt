package com.duriseo.server.user.application

import com.duriseo.server.common.time.nowInSeconds
import com.duriseo.server.user.domain.NotificationSettingsRepository
import jakarta.persistence.EntityManager
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** 알림 종류별 설정 값 (API.md 3.1 `NotificationSettings`). 켜짐이 `true` */
data class NotificationSettingsView(
	val eventReminder: Boolean,
	val partnerCreated: Boolean,
	val memoPinned: Boolean,
	val anniversary: Boolean,
)

/** 바꿀 값. `null`은 "바꾸지 않음"이다 (필드 없음). 요청의 `null`은 api 계층이 이미 400으로 막았다 */
data class NotificationSettingsChange(
	val eventReminder: Boolean?,
	val partnerCreated: Boolean?,
	val memoPinned: Boolean?,
	val anniversary: Boolean?,
)

/** `GET`·`PATCH /me/notification-settings` (FR-NOTI-02, D-62, D-145). 게스트도 쓴다 */
@Service
class NotificationSettingsService(
	private val settings: NotificationSettingsRepository,
	private val em: EntityManager,
	private val clock: Clock,
) {

	@Transactional
	fun get(userId: UUID): NotificationSettingsView {
		ensureRow(userId)
		return viewOf(checkNotNull(settings.findByIdOrNull(userId)) { "notification settings row is missing" })
	}

	/**
	 * 보낸 종류만 바꾼다. 바뀐 값이 하나도 없으면 `updated_at`도 그대로다. 응답은 전체 설정이다.
	 * 행을 `FOR UPDATE`로 잠근 뒤 읽고 고친다: 두 `PATCH`가 겹쳐도 뒤 요청이 옛 값으로 앞 요청의 변경을 덮지 않는다.
	 */
	@Transactional
	fun update(userId: UUID, change: NotificationSettingsChange): NotificationSettingsView {
		ensureRow(userId)
		val row = checkNotNull(settings.findByUserIdForUpdate(userId)) { "notification settings row is missing" }
		var changed = false
		change.eventReminder?.takeIf { it != row.eventReminder }?.let { row.eventReminder = it; changed = true }
		change.partnerCreated?.takeIf { it != row.partnerCreated }?.let { row.partnerCreated = it; changed = true }
		change.memoPinned?.takeIf { it != row.memoPinned }?.let { row.memoPinned = it; changed = true }
		change.anniversary?.takeIf { it != row.anniversary }?.let { row.anniversary = it; changed = true }
		if (changed) row.updatedAt = clock.nowInSeconds()
		return viewOf(row)
	}

	/**
	 * 사용자를 만들 때 함께 만들지만, 행이 없으면 기본값(모두 켜짐, DDL default)으로 만든다.
	 * 같은 사용자의 요청이 겹쳐도 기본 키 충돌(500)이 나지 않도록 `on conflict do nothing`으로 넣고 그 뒤에 읽는다.
	 */
	private fun ensureRow(userId: UUID) {
		em.createNativeQuery("insert into notification_settings (user_id, updated_at) values (:userId, :now) on conflict (user_id) do nothing")
			.setParameter("userId", userId)
			.setParameter("now", clock.nowInSeconds())
			.executeUpdate()
	}

	private fun viewOf(row: NotificationSettings) =
		NotificationSettingsView(row.eventReminder, row.partnerCreated, row.memoPinned, row.anniversary)
}
