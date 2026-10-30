package com.duriseo.server.couple.api

import com.duriseo.server.common.web.AuthenticatedUser
import com.duriseo.server.common.web.CurrentUser
import com.duriseo.server.couple.application.CoupleImportService
import com.duriseo.server.couple.application.CoupleJoinService
import com.duriseo.server.couple.application.CoupleUnlinkService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** 커플 API (API.md 3.2): 연결(`POST /couple/join`), 기록 가져오기(`POST /couple/import`), 연결 해제(`DELETE /couple`) */
@RestController
class CoupleController(
	private val joinService: CoupleJoinService,
	private val importService: CoupleImportService,
	private val unlinkService: CoupleUnlinkService,
) {

	/**
	 * 초대코드로 연결 (R-09, FR-COUPLE-02). 200과 `CoupleJoinResult`.
	 * 본문 `{code}`는 `code` 누락(400)보다 R-09(403·409)가 먼저 나가야 해서 `Map`으로 받고 필수로 두지 않는다.
	 */
	@PostMapping("/couple/join")
	fun join(
		@CurrentUser requester: AuthenticatedUser,
		@RequestBody(required = false) body: Map<String, Any?>?,
	): CoupleJoinResponse =
		CoupleJoinResponse.from(joinService.join(requester.userId, body?.get("code")))

	/**
	 * 기록 가져오기 결정 (R-18, FR-COUPLE-03). 204. 본문 `{import: boolean}`은 R-18(409)이 `import` 누락(400)보다 먼저 나가야 해서
	 * `Map`으로 받고 필수로 두지 않는다.
	 */
	@PostMapping("/couple/import")
	fun decideImport(
		@CurrentUser requester: AuthenticatedUser,
		@RequestBody(required = false) body: Map<String, Any?>?,
	): ResponseEntity<Void> {
		importService.decide(requester.userId, body?.get("import"))
		return ResponseEntity.noContent().build()
	}

	/** 연결 해제 (R-18, FR-COUPLE-05, API.md 4.5). 본문 없음, 204. 개인 공간이면 409 `NOT_COUPLE` */
	@DeleteMapping("/couple")
	fun unlink(@CurrentUser requester: AuthenticatedUser): ResponseEntity<Void> {
		unlinkService.unlink(requester.userId)
		return ResponseEntity.noContent().build()
	}
}
