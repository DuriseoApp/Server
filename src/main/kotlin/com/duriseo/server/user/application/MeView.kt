package com.duriseo.server.user.application

import com.duriseo.server.space.domain.SpaceType
import com.duriseo.server.user.domain.AccountType
import com.duriseo.server.user.domain.SocialProvider
import java.time.LocalDate
import java.util.UUID

/** `GET /me`·`PATCH /me`의 내용 (API.md 3.1 `Me`) */
data class MeView(
	val id: UUID,
	val accountType: AccountType,
	val nickname: String,
	val timeZone: String,
	val providers: List<SocialProvider>,
	val hasProfileImage: Boolean,
	val space: MeSpaceView,
)

data class MeSpaceView(
	val id: UUID,
	val type: SpaceType,
	val myColor: String,
	val partner: MePartnerView?,
	val togetherColor: String?,
	val startDate: LocalDate?,
	val importPending: Boolean,
)

data class MePartnerView(val id: UUID, val nickname: String, val color: String, val hasProfileImage: Boolean)
