package com.duriseo.server.couple.application

import java.time.Instant

/** API.md 3.2 `InviteCode`의 내용 */
data class InviteCodeView(val code: String, val expiresAt: Instant, val reissueAvailableAt: Instant)
