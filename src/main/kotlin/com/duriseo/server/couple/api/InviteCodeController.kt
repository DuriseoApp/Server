package com.duriseo.server.couple.api

import com.duriseo.server.common.web.AuthenticatedUser
import com.duriseo.server.common.web.CurrentUser
import com.duriseo.server.couple.application.InviteCodeService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/** 초대코드 API (API.md 3.2, R-09: 회원이고 개인 공간). 권한 검사는 서비스가 `SpaceAccessPolicy`로 한다 */
@RestController
class InviteCodeController(private val inviteCodeService: InviteCodeService) {

	/** 초대코드 생성·재요청 (FR-COUPLE-01). 201과 `InviteCode` */
	@PostMapping("/invite-codes")
	fun issue(@CurrentUser requester: AuthenticatedUser): ResponseEntity<InviteCodeResponse> =
		ResponseEntity.status(HttpStatus.CREATED).body(InviteCodeResponse.from(inviteCodeService.issue(requester.userId)))

	/** 내 유효한 초대코드. 없으면 404 `NOT_FOUND` (앱이 `POST /invite-codes`를 부른다) */
	@GetMapping("/invite-codes/current")
	fun current(@CurrentUser requester: AuthenticatedUser): InviteCodeResponse =
		InviteCodeResponse.from(inviteCodeService.current(requester.userId))
}
