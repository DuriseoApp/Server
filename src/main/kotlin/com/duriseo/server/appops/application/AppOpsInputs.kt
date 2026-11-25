package com.duriseo.server.appops.application

import com.duriseo.server.appops.domain.AppPlatform
import com.duriseo.server.appops.domain.BuildOp
import com.duriseo.server.appops.domain.UpdateMode
import com.duriseo.server.common.api.FieldErrorReason
import java.time.Instant

/** 검사를 마친 `PUT /admin/app-notices/{id}` 본문 */
data class AppNoticeInput(
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
)

/** 검사를 마친 `PUT /admin/app-update-rules/{id}` 본문 */
data class AppUpdateRuleInput(
	val mode: UpdateMode,
	val platform: AppPlatform,
	val buildOp: BuildOp,
	val buildValue: Int?,
	val buildMin: Int?,
	val buildMax: Int?,
	val buildValues: List<Int>?,
	val osMin: Int?,
	val osMax: Int?,
	val title: String?,
	val body: String?,
	val image: String?,
	val imageAlt: String?,
)

/** 관리자 API 본문 검사 (API.md 3.7, ERD 3.4). 틀린 필드를 모두 모아 400 `VALIDATION_FAILED`로 던진다 */
object AppOpsValidator {

	private val ID_FORMAT = Regex("[a-z0-9_-]{1,50}")
	private val PRESETS = setOf("preset:maintenance", "preset:error", "preset:update", "preset:notice")
	private const val MAX_BUILD_VALUES = 50

	/** `id` 규칙: 1~50자, `[a-z0-9_-]`. DELETE는 형식이 틀리면 그냥 404라 이 함수로 먼저 거른다 */
	fun isValidId(id: String): Boolean = ID_FORMAT.matches(id)

	fun parseNotice(id: String, body: Map<String, Any?>): AppNoticeInput {
		val r = BodyReader(body)
		if (!isValidId(id)) r.fail("id", FieldErrorReason.INVALID)

		val closableRaw = r.raw("closable")
		var closable = false
		when (closableRaw) {
			null -> r.fail("closable", FieldErrorReason.REQUIRED)
			is Boolean -> closable = closableRaw
			else -> r.fail("closable", FieldErrorReason.INVALID)
		}
		val common = readCommon(r)
		val linkText = r.string("linkText", 20)
		val linkUrl = r.httpsUrl("linkUrl", 500)
		val platform = readPlatform(r, required = false)
		val startsAt = r.instant("startsAt")
		val endsAt = r.instant("endsAt")
		if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) r.fail("endsAt", FieldErrorReason.INVALID)

		r.throwIfAny()
		return AppNoticeInput(
			closable, common.title, common.body, common.image, common.imageAlt, linkText, linkUrl, platform, startsAt, endsAt,
		)
	}

	fun parseRule(id: String, body: Map<String, Any?>): AppUpdateRuleInput {
		val r = BodyReader(body)
		if (!isValidId(id)) r.fail("id", FieldErrorReason.INVALID)

		val mode = readEnum<UpdateMode>(r, "mode", required = true)
		val platform = readPlatform(r, required = true)

		var buildOp: BuildOp? = null
		var value: Int? = null
		var min: Int? = null
		var max: Int? = null
		var values: List<Int>? = null
		val build = if (r.raw("build") == null) {
			r.fail("build", FieldErrorReason.REQUIRED)
			null
		} else {
			r.obj("build")
		}
		if (build != null) {
			val opRaw = build["op"]
			when {
				opRaw == null -> r.fail("build.op", FieldErrorReason.REQUIRED)
				opRaw !is String || BuildOp.entries.none { it.name == opRaw } -> r.fail("build.op", FieldErrorReason.INVALID)
				else -> buildOp = BuildOp.valueOf(opRaw as String)
			}
			value = r.int(build["value"], "build.value")
			min = r.int(build["min"], "build.min")
			max = r.int(build["max"], "build.max")
			values = readValues(r, build["values"])
			if (buildOp != null) {
				val usesValue = buildOp == BuildOp.EQ || buildOp == BuildOp.LTE || buildOp == BuildOp.GTE
				val usesRange = buildOp == BuildOp.RANGE
				val usesValues = buildOp == BuildOp.IN
				checkUse(r, build, "value", usesValue)
				checkUse(r, build, "min", usesRange)
				checkUse(r, build, "max", usesRange)
				checkUse(r, build, "values", usesValues)
				if (usesRange && min != null && max != null && min > max) r.fail("build.min", FieldErrorReason.INVALID)
				if (!usesValue) value = null
				if (!usesRange) {
					min = null
					max = null
				}
				if (!usesValues) values = null
			}
		}

		var osMin: Int? = null
		var osMax: Int? = null
		val os = r.obj("osVersion")
		if (os != null) {
			osMin = r.int(os["min"], "osVersion.min")
			osMax = r.int(os["max"], "osVersion.max")
			if (os["min"] == null && os["max"] == null) r.fail("osVersion", FieldErrorReason.INVALID)
		}

		val common = readCommon(r)
		r.throwIfAny()
		return AppUpdateRuleInput(
			mode = checkNotNull(mode), platform = checkNotNull(platform), buildOp = checkNotNull(buildOp),
			buildValue = value, buildMin = min, buildMax = max, buildValues = values, osMin = osMin, osMax = osMax,
			title = common.title, body = common.body, image = common.image, imageAlt = common.imageAlt,
		)
	}

	private class Common(val title: String?, val body: String?, val image: String?, val imageAlt: String?)

	/** 공지·규칙 공통: 제목·본문·이미지·이미지 설명과 D-183 조합 검사 */
	private fun readCommon(r: BodyReader): Common {
		val title = r.string("title", 40)
		val body = r.string("body", 500)
		val imageAlt = r.string("imageAlt", 100)
		val image = readImage(r)
		// 제목이 없는 팝업(이미지만 넣은 팝업)의 조합. 필드가 이미 틀렸으면 그 이유를 그대로 둔다 (같은 필드는 처음 이유만 남는다)
		if (r.raw("title") == null) {
			if (image == null || !BodyReader.isHttpsUrl(image)) r.fail("image", FieldErrorReason.REQUIRED)
			if (r.raw("imageAlt") == null) r.fail("imageAlt", FieldErrorReason.REQUIRED)
			if (r.raw("body") != null) r.fail("title", FieldErrorReason.REQUIRED)
		}
		return Common(title, body, image, imageAlt)
	}

	private fun readImage(r: BodyReader): String? {
		val value = r.raw("image") ?: return null
		if (value !is String || !(value in PRESETS || BodyReader.isHttpsUrl(value))) {
			r.fail("image", FieldErrorReason.INVALID)
			return null
		}
		if (value.codePointCount(0, value.length) > 500) {
			r.fail("image", FieldErrorReason.TOO_LONG)
			return null
		}
		return value
	}

	private fun readPlatform(r: BodyReader, required: Boolean): AppPlatform? {
		val raw = r.raw("platform")
		if (raw == null) {
			if (required) r.fail("platform", FieldErrorReason.REQUIRED)
			return null
		}
		if (raw !is String || AppPlatform.entries.none { it.name == raw }) {
			r.fail("platform", FieldErrorReason.INVALID)
			return null
		}
		return AppPlatform.valueOf(raw)
	}

	private inline fun <reified E : Enum<E>> readEnum(r: BodyReader, name: String, required: Boolean): E? {
		val raw = r.raw(name)
		if (raw == null) {
			if (required) r.fail(name, FieldErrorReason.REQUIRED)
			return null
		}
		val found = enumValues<E>().firstOrNull { it.name == raw }
		if (found == null) r.fail(name, FieldErrorReason.INVALID)
		return found
	}

	private fun readValues(r: BodyReader, raw: Any?): List<Int>? {
		if (raw == null) return null
		if (raw !is List<*>) {
			r.fail("build.values", FieldErrorReason.INVALID)
			return null
		}
		if (raw.size > MAX_BUILD_VALUES) {
			r.fail("build.values", FieldErrorReason.TOO_LONG)
			return null
		}
		val result = ArrayList<Int>(raw.size)
		raw.forEachIndexed { index, element ->
			val path = "build.values[$index]"
			if (element == null) {
				r.fail(path, FieldErrorReason.INVALID)
			} else {
				r.int(element, path)?.let { result.add(it) }
			}
		}
		return result
	}

	/** `op`에 쓰는 필드는 있어야 하고(REQUIRED), 쓰지 않는 필드는 null이어야 한다(INVALID) */
	private fun checkUse(r: BodyReader, build: Map<String, Any?>, name: String, used: Boolean) {
		val present = build[name] != null
		val path = "build.$name"
		if (used && !present) {
			r.fail(path, FieldErrorReason.REQUIRED)
		} else if (used && name == "values" && (build[name] as? List<*>)?.isEmpty() == true) {
			r.fail(path, FieldErrorReason.REQUIRED)
		} else if (!used && present) {
			r.fail(path, FieldErrorReason.INVALID)
		}
	}
}
