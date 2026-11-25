package com.duriseo.server.appops

import com.duriseo.server.appops.application.ForceUpdateRules
import com.duriseo.server.support.IntegrationTestSupport
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put

/** 공지·업데이트 규칙 테스트가 함께 쓰는 도구. 관리자 키는 `src/test/resources/application.properties`의 가짜 값이다 */
abstract class AppOpsTestSupport : IntegrationTestSupport() {

	@Autowired
	protected lateinit var forceRules: ForceUpdateRules

	@BeforeEach
	fun resetAppOps() {
		jdbc.execute("truncate table app_notices, app_update_rules")
		forceRules.invalidate()
	}

	protected fun adminGet(path: String): ResultActions =
		mockMvc.perform(get("$API/admin/$path").header("X-Admin-Key", ADMIN_KEY))

	protected fun adminPut(path: String, json: String): ResultActions =
		mockMvc.perform(put("$API/admin/$path").header("X-Admin-Key", ADMIN_KEY).contentType(MediaType.APPLICATION_JSON).content(json))

	protected fun adminDelete(path: String): ResultActions =
		mockMvc.perform(delete("$API/admin/$path").header("X-Admin-Key", ADMIN_KEY))

	protected fun putNotice(id: String, json: String): ResultActions = adminPut("app-notices/$id", json)

	protected fun putRule(id: String, json: String): ResultActions = adminPut("app-update-rules/$id", json)

	/** 400 응답의 `details.fields`를 `필드 → reason`으로 읽는다 */
	protected fun fieldErrors(result: ResultActions): Map<String, String> {
		val fields = JsonPath.read<List<Map<String, String>>>(bodyOf(result), "$.details.fields")
		return fields.associate { it.getValue("field") to it.getValue("reason") }
	}

	companion object {
		const val ADMIN_KEY = "fake-admin-key-for-tests"
		const val VALID_NOTICE = """{"closable": true, "title": "점검 안내", "body": "내용", "image": "preset:maintenance"}"""
		const val VALID_RULE = """{"mode": "FORCE", "platform": "ANDROID", "build": {"op": "LTE", "value": 9}, "title": "업데이트가 필요해요", "body": "새 버전으로 업데이트해 주세요"}"""
	}
}
