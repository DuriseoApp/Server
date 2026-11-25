package com.duriseo.server.appops.application

import com.duriseo.server.appops.domain.AppUpdateRule
import com.duriseo.server.appops.domain.AppUpdateRuleRepository
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.time.nowInSeconds
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 강제·권장 업데이트 규칙 (FR-APP-02, D-177). 관리자 쓰기는 동기화 신호와 알림을 보내지 않는다 (API.md 6장).
 * 쓰기가 커밋되면 R-20의 메모리 캐시를 비워 이 서버는 바로 새 규칙으로 판단한다.
 */
@Service
class AppUpdateRuleService(
	private val rules: AppUpdateRuleRepository,
	private val forceRules: ForceUpdateRules,
	private val clock: Clock,
) {

	/** `GET /app-update-rules`, `GET /admin/app-update-rules`: 기간이 없어 모두 */
	@Transactional(readOnly = true)
	fun listAll(): List<AppUpdateRuleView> = rules.findAllSorted().map { AppUpdateRuleView.of(it) }

	/** `PUT /admin/app-update-rules/{id}`: upsert. 바꿀 때 `created_at`은 그대로 둔다 */
	@Transactional
	fun upsert(id: String, body: Map<String, Any?>): AppUpdateRuleView {
		val input = AppOpsValidator.parseRule(id, body)
		val now = clock.nowInSeconds()
		val rule = rules.findById(id).orElse(null) ?: AppUpdateRule(id, input.mode, input.platform, input.buildOp, now)
		rule.mode = input.mode
		rule.platform = input.platform
		rule.buildOp = input.buildOp
		rule.buildValue = input.buildValue
		rule.buildMin = input.buildMin
		rule.buildMax = input.buildMax
		rule.buildValues = input.buildValues
		rule.osMin = input.osMin
		rule.osMax = input.osMax
		rule.title = input.title
		rule.body = input.body
		rule.image = input.image
		rule.imageAlt = input.imageAlt
		rule.updatedAt = now
		val saved = rules.saveAndFlush(rule)
		invalidateAfterCommit()
		return AppUpdateRuleView.of(saved)
	}

	/** `DELETE /admin/app-update-rules/{id}`: 없거나 `id` 형식이 틀리면 404 */
	@Transactional
	fun delete(id: String) {
		if (!AppOpsValidator.isValidId(id)) throw ApiException(ErrorCode.NOT_FOUND)
		val rule = rules.findById(id).orElse(null) ?: throw ApiException(ErrorCode.NOT_FOUND)
		rules.delete(rule)
		rules.flush()
		invalidateAfterCommit()
	}

	private fun invalidateAfterCommit() {
		TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
			override fun afterCommit() = forceRules.invalidate()
		})
	}
}
