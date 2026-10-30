package com.duriseo.server.couple.api

import com.duriseo.server.common.web.AuthenticatedUser
import com.duriseo.server.common.web.CurrentUser
import com.duriseo.server.couple.application.CoupleJoinService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** 커플 API (API.md 3.2). `POST /couple/import`와 `DELETE /couple`은 구현 2부에서 더한다 */
@RestController
class CoupleController(private val joinService: CoupleJoinService) {

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
}
