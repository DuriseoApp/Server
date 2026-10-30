package com.duriseo.server.couple.api

import com.duriseo.server.couple.application.InviteCodeView
import java.time.Instant

/** API.md 3.2 `InviteCode`. `POST /invite-codes`, `GET /invite-codes/current`의 응답 본문 */
data class InviteCodeResponse(
	val code: String,
	val expiresAt: Instant,
	val reissueAvailableAt: Instant,
) {
	companion object {
		fun from(view: InviteCodeView) = InviteCodeResponse(view.code, view.expiresAt, view.reissueAvailableAt)
	}
}
