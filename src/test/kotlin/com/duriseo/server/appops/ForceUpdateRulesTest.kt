package com.duriseo.server.appops

import com.duriseo.server.appops.application.ForceUpdateRules
import com.duriseo.server.appops.domain.AppPlatform
import com.duriseo.server.appops.domain.AppUpdateRule
import com.duriseo.server.appops.domain.BuildOp
import com.duriseo.server.appops.domain.UpdateMode
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 규칙 맞춤 판단 식 (API.md 3.7 "업데이트 규칙 맞춤 판단", R-20) */
class ForceUpdateRulesTest {

	private fun rule(
		op: BuildOp,
		value: Int? = null,
		min: Int? = null,
		max: Int? = null,
		values: List<Int>? = null,
		osMin: Int? = null,
		osMax: Int? = null,
		platform: AppPlatform = AppPlatform.ANDROID,
	) = AppUpdateRule("r", UpdateMode.FORCE, platform, op, Instant.EPOCH).also {
		it.buildValue = value
		it.buildMin = min
		it.buildMax = max
		it.buildValues = values
		it.osMin = osMin
		it.osMax = osMax
	}

	private fun matches(rule: AppUpdateRule, build: Int, os: Int? = 34, platform: AppPlatform = AppPlatform.ANDROID) =
		ForceUpdateRules.matches(rule, platform, build, os)

	@Test
	fun `R_20_EQ는_같을_때만`() {
		val rule = rule(BuildOp.EQ, value = 9)
		assertTrue(matches(rule, 9))
		assertFalse(matches(rule, 8))
		assertFalse(matches(rule, 10))
	}

	@Test
	fun `R_20_LTE는_이하`() {
		val rule = rule(BuildOp.LTE, value = 9)
		assertTrue(matches(rule, 9))
		assertTrue(matches(rule, 0))
		assertFalse(matches(rule, 10))
	}

	@Test
	fun `R_20_GTE는_이상`() {
		val rule = rule(BuildOp.GTE, value = 9)
		assertTrue(matches(rule, 9))
		assertTrue(matches(rule, Int.MAX_VALUE))
		assertFalse(matches(rule, 8))
	}

	@Test
	fun `R_20_RANGE는_양끝을_포함한다`() {
		val rule = rule(BuildOp.RANGE, min = 5, max = 9)
		assertTrue(matches(rule, 5))
		assertTrue(matches(rule, 9))
		assertTrue(matches(rule, 7))
		assertFalse(matches(rule, 4))
		assertFalse(matches(rule, 10))
	}

	@Test
	fun `R_20_IN은_목록에_있을_때만`() {
		val rule = rule(BuildOp.IN, values = listOf(3, 7, 11))
		assertTrue(matches(rule, 7))
		assertFalse(matches(rule, 8))
	}

	@Test
	fun `R_20_플랫폼이_다르면_맞지_않는다`() {
		assertFalse(matches(rule(BuildOp.LTE, value = 9, platform = AppPlatform.IOS), 9))
	}

	@Test
	fun `R_20_OS조건은_양끝을_포함하고_null_쪽은_제한이_없다`() {
		val both = rule(BuildOp.LTE, value = 9, osMin = 26, osMax = 30)
		assertTrue(matches(both, 9, os = 26))
		assertTrue(matches(both, 9, os = 30))
		assertFalse(matches(both, 9, os = 25))
		assertFalse(matches(both, 9, os = 31))
		val onlyMin = rule(BuildOp.LTE, value = 9, osMin = 26)
		assertTrue(matches(onlyMin, 9, os = 99))
		assertFalse(matches(onlyMin, 9, os = 25))
		val onlyMax = rule(BuildOp.LTE, value = 9, osMax = 30)
		assertTrue(matches(onlyMax, 9, os = 0))
		assertFalse(matches(onlyMax, 9, os = 31))
	}

	@Test
	fun `R_20_OS헤더가_없으면_OS조건이_있는_규칙은_맞지_않는다`() {
		assertFalse(matches(rule(BuildOp.LTE, value = 9, osMin = 26), 9, os = null))
		assertTrue(matches(rule(BuildOp.LTE, value = 9), 9, os = null))
	}
}
