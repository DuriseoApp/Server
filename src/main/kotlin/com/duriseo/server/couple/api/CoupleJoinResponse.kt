package com.duriseo.server.couple.api

import com.duriseo.server.couple.application.CoupleJoinView
import java.util.UUID

/** API.md 3.2 `CoupleJoinResult`. `POST /couple/join`의 응답 본문 */
data class CoupleJoinResponse(
	val spaceId: UUID,
	val restored: Boolean,
	val importPending: Boolean,
) {
	companion object {
		fun from(view: CoupleJoinView) = CoupleJoinResponse(view.spaceId, view.restored, view.importPending)
	}
}
