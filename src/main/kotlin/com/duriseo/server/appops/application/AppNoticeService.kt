package com.duriseo.server.appops.application

import com.duriseo.server.appops.domain.AppNotice
import com.duriseo.server.appops.domain.AppNoticeRepository
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.time.nowInSeconds
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 공지 팝업 (FR-APP-01, D-176). 관리자 쓰기는 동기화 신호와 알림을 보내지 않는다 (API.md 6장).
 * 공간·사용자와 관계가 없어 `SpaceAccessPolicy`를 쓰지 않는다 (R-19가 관리자 키로 막는다).
 */
@Service
class AppNoticeService(
	private val notices: AppNoticeRepository,
	private val clock: Clock,
) {

	/** `GET /app-notices`: 지금 유효한 것만 */
	@Transactional(readOnly = true)
	fun listValid(): List<AppNoticeView> = notices.findValidAt(clock.instant()).map { AppNoticeView.of(it) }

	/** `GET /admin/app-notices`: 끝난 것·아직 시작 안 한 것 포함 전부 */
	@Transactional(readOnly = true)
	fun listAll(): List<AppNoticeView> = notices.findAllSorted().map { AppNoticeView.of(it) }

	/** `PUT /admin/app-notices/{id}`: 없으면 만들고 있으면 바꾼다. 바꿀 때 `created_at`은 그대로 둔다 (ERD 3.4) */
	@Transactional
	fun upsert(id: String, body: Map<String, Any?>): AppNoticeView {
		val input = AppOpsValidator.parseNotice(id, body)
		val now = clock.nowInSeconds()
		val notice = notices.findById(id).orElse(null) ?: AppNotice(id, input.closable, now)
		notice.closable = input.closable
		notice.title = input.title
		notice.body = input.body
		notice.image = input.image
		notice.imageAlt = input.imageAlt
		notice.linkText = input.linkText
		notice.linkUrl = input.linkUrl
		notice.platform = input.platform
		notice.startsAt = input.startsAt
		notice.endsAt = input.endsAt
		notice.updatedAt = now
		return AppNoticeView.of(notices.saveAndFlush(notice))
	}

	/** `DELETE /admin/app-notices/{id}`: 없거나 `id` 형식이 틀리면 404 */
	@Transactional
	fun delete(id: String) {
		if (!AppOpsValidator.isValidId(id)) throw ApiException(ErrorCode.NOT_FOUND)
		val notice = notices.findById(id).orElse(null) ?: throw ApiException(ErrorCode.NOT_FOUND)
		notices.delete(notice)
		notices.flush()
	}
}
