package com.duriseo.server.appops.api

import com.duriseo.server.appops.application.AppNoticeService
import com.duriseo.server.appops.application.AppNoticeView
import com.duriseo.server.appops.application.AppUpdateRuleService
import com.duriseo.server.appops.application.AppUpdateRuleView
import com.duriseo.server.common.api.ListResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * 공지 팝업·업데이트 규칙 (API.md 3.7). 공개 조회 2개는 로그인이 필요 없고 R-20 검사 예외다.
 * 관리자 API(`/admin/` 아래 경로)는 `AdminKeyFilter`가 키를 먼저 검사한다 (R-19). 본문은 `Map`으로 받아 서비스가 필드마다 검사한다.
 */
@RestController
class AppNoticeController(
	private val notices: AppNoticeService,
	private val rules: AppUpdateRuleService,
) {

	@GetMapping("/app-notices")
	fun listNotices(): ListResponse<AppNoticeView> = ListResponse(notices.listValid())

	@GetMapping("/app-update-rules")
	fun listRules(): ListResponse<AppUpdateRuleView> = ListResponse(rules.listAll())

	@GetMapping("/admin/app-notices")
	fun adminListNotices(): ListResponse<AppNoticeView> = ListResponse(notices.listAll())

	@PutMapping("/admin/app-notices/{id}")
	fun adminPutNotice(@PathVariable("id") id: String, @RequestBody body: Map<String, Any?>): AppNoticeView = notices.upsert(id, body)

	@DeleteMapping("/admin/app-notices/{id}")
	fun adminDeleteNotice(@PathVariable("id") id: String): ResponseEntity<Void> {
		notices.delete(id)
		return ResponseEntity.noContent().build()
	}

	@GetMapping("/admin/app-update-rules")
	fun adminListRules(): ListResponse<AppUpdateRuleView> = ListResponse(rules.listAll())

	@PutMapping("/admin/app-update-rules/{id}")
	fun adminPutRule(@PathVariable("id") id: String, @RequestBody body: Map<String, Any?>): AppUpdateRuleView = rules.upsert(id, body)

	@DeleteMapping("/admin/app-update-rules/{id}")
	fun adminDeleteRule(@PathVariable("id") id: String): ResponseEntity<Void> {
		rules.delete(id)
		return ResponseEntity.noContent().build()
	}
}
