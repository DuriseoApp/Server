package com.duriseo.server.appops.application

import com.duriseo.server.appops.domain.AppNotice
import com.duriseo.server.appops.domain.AppPlatform
import com.duriseo.server.appops.domain.AppUpdateRule
import com.duriseo.server.appops.domain.BuildOp
import com.duriseo.server.appops.domain.UpdateMode
import java.time.Instant

/** API.md 3.7 `AppNotice`. 값이 없는 필드는 빼지 않고 null로 보낸다 */
data class AppNoticeView(
	val id: String,
	val closable: Boolean,
	val title: String?,
	val body: String?,
	val image: String?,
	val imageAlt: String?,
	val linkText: String?,
	val linkUrl: String?,
	val platform: AppPlatform?,
	val startsAt: Instant?,
	val endsAt: Instant?,
) {
	companion object {
		fun of(n: AppNotice) = AppNoticeView(
			n.id, n.closable, n.title, n.body, n.image, n.imageAlt, n.linkText, n.linkUrl, n.platform, n.startsAt, n.endsAt,
		)
	}
}

/** `AppUpdateRule.build`. 쓰지 않는 필드는 null */
data class BuildConditionView(val op: BuildOp, val value: Int?, val min: Int?, val max: Int?, val values: List<Int>?)

/** `AppUpdateRule.osVersion` */
data class OsConditionView(val min: Int?, val max: Int?)

/** API.md 3.7 `AppUpdateRule` */
data class AppUpdateRuleView(
	val id: String,
	val mode: UpdateMode,
	val platform: AppPlatform,
	val build: BuildConditionView,
	val osVersion: OsConditionView?,
	val title: String?,
	val body: String?,
	val image: String?,
	val imageAlt: String?,
) {
	companion object {
		fun of(r: AppUpdateRule) = AppUpdateRuleView(
			id = r.id,
			mode = r.mode,
			platform = r.platform,
			build = BuildConditionView(r.buildOp, r.buildValue, r.buildMin, r.buildMax, r.buildValues),
			// 둘 다 null이면 OS 조건 없음
			osVersion = if (r.osMin == null && r.osMax == null) null else OsConditionView(r.osMin, r.osMax),
			title = r.title,
			body = r.body,
			image = r.image,
			imageAlt = r.imageAlt,
		)
	}
}
