package com.duriseo.server.appops.application

import com.duriseo.server.appops.domain.AppPlatform
import com.duriseo.server.appops.domain.AppUpdateRule
import com.duriseo.server.appops.domain.AppUpdateRuleRepository
import com.duriseo.server.appops.domain.BuildOp
import com.duriseo.server.appops.domain.UpdateMode
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** 요청 헤더(1장 공통 요청 헤더)에서 읽은 앱 정보. 읽을 수 없는 값은 null이다 */
data class ClientInfo(val platform: AppPlatform?, val build: Int?, val osVersion: Int?)

/** `FORCE` 규칙이 맞았을 때 426 `details`로 보낼 값 (API.md 1.1 `APP_VERSION_UNSUPPORTED`) */
data class ForceMatch(val ruleId: String, val title: String?, val body: String?, val image: String?, val imageAlt: String?)

/**
 * R-20의 판단 (API.md 3.7 "업데이트 규칙 맞춤 판단"). 규칙은 요청마다 읽지 않고 [TTL] 동안 메모리에 둔다.
 * 관리자 쓰기는 [invalidate]로 바로 비우지만, 다른 서버 프로세스가 있어도 최대 [TTL] 안에 바뀐다(API.md R-20 허용).
 */
@Component
class ForceUpdateRules(
	private val rules: AppUpdateRuleRepository,
	private val clock: Clock,
) {

	private class Snapshot(val loadedAt: Instant, val force: List<AppUpdateRule>)

	@Volatile
	private var snapshot: Snapshot? = null

	fun invalidate() {
		snapshot = null
	}

	/** 맞는 `FORCE` 규칙 중 정렬 첫 번째. 없으면 null. 플랫폼이나 빌드를 읽을 수 없으면 판단하지 않는다 (통과) */
	fun findMatch(client: ClientInfo): ForceMatch? {
		val platform = client.platform ?: return null
		val build = client.build ?: return null
		val rule = currentForceRules().firstOrNull { matches(it, platform, build, client.osVersion) } ?: return null
		return ForceMatch(rule.id, rule.title, rule.body, rule.image, rule.imageAlt)
	}

	private fun currentForceRules(): List<AppUpdateRule> {
		val now = clock.instant()
		val current = snapshot
		if (current != null && Duration.between(current.loadedAt, now) < TTL) return current.force
		// 정렬은 created_at 오래된 순, 같으면 id (R-20)
		val loaded = rules.findAllSorted().filter { it.mode == UpdateMode.FORCE }
		snapshot = Snapshot(now, loaded)
		return loaded
	}

	companion object {
		val TTL: Duration = Duration.ofSeconds(30)

		/** 규칙이 요청에 맞는지. 앱(4.9)과 같은 식이다. [os]가 null이면 OS 조건이 있는 규칙은 맞지 않는다 */
		fun matches(rule: AppUpdateRule, platform: AppPlatform, build: Int, os: Int?): Boolean {
			if (rule.platform != platform) return false
			val buildOk = when (rule.buildOp) {
				BuildOp.EQ -> rule.buildValue?.let { build == it } ?: false
				BuildOp.LTE -> rule.buildValue?.let { build <= it } ?: false
				BuildOp.GTE -> rule.buildValue?.let { build >= it } ?: false
				BuildOp.RANGE -> rule.buildMin != null && rule.buildMax != null && build >= rule.buildMin!! && build <= rule.buildMax!!
				BuildOp.IN -> rule.buildValues?.contains(build) ?: false
			}
			if (!buildOk) return false
			val min = rule.osMin
			val max = rule.osMax
			if (min == null && max == null) return true
			if (os == null) return false
			return (min == null || os >= min) && (max == null || os <= max)
		}
	}
}
