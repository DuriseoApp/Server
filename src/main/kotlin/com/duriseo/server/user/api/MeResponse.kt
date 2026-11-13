package com.duriseo.server.user.api

import com.duriseo.server.space.application.ImportCounts
import com.duriseo.server.space.domain.SpaceType
import com.duriseo.server.user.application.MePartnerView
import com.duriseo.server.user.application.MeSpaceView
import com.duriseo.server.user.application.MeView
import com.duriseo.server.user.domain.AccountType
import com.duriseo.server.user.domain.SocialProvider
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** API.md 3.1 `Me`. `GET /me`, `PATCH /me`의 응답 본문 */
data class MeResponse(
	val id: UUID,
	val accountType: AccountType,
	val nickname: String,
	val timeZone: String,
	val providers: List<SocialProvider>,
	val hasProfileImage: Boolean,
	val profileImageVersion: String?,
	val createdAt: Instant,
	val space: MeSpaceResponse,
) {
	companion object {
		fun from(view: MeView) = MeResponse(
			id = view.id,
			accountType = view.accountType,
			nickname = view.nickname,
			timeZone = view.timeZone,
			providers = view.providers,
			hasProfileImage = view.hasProfileImage,
			profileImageVersion = view.profileImageVersion,
			createdAt = view.createdAt,
			space = MeSpaceResponse.from(view.space),
		)
	}
}

/** API.md 3.1 `MeSpace` */
data class MeSpaceResponse(
	val id: UUID,
	val type: SpaceType,
	val myColor: String,
	val partner: MePartnerResponse?,
	val togetherColor: String?,
	val startDate: LocalDate?,
	val linkedAt: Instant?,
	val importPending: Boolean,
	val importCounts: ImportCountsResponse?,
) {
	companion object {
		fun from(view: MeSpaceView) = MeSpaceResponse(
			id = view.id,
			type = view.type,
			myColor = view.myColor,
			partner = view.partner?.let { MePartnerResponse.from(it) },
			togetherColor = view.togetherColor,
			startDate = view.startDate,
			linkedAt = view.linkedAt,
			importPending = view.importPending,
			importCounts = view.importCounts?.let { ImportCountsResponse.from(it) },
		)
	}
}

/** API.md 3.1 `ImportCounts` */
data class ImportCountsResponse(val events: Int, val anniversaries: Int, val memos: Int) {
	companion object {
		fun from(counts: ImportCounts) = ImportCountsResponse(counts.events, counts.anniversaries, counts.memos)
	}
}

/** API.md 3.1 `MePartner` */
data class MePartnerResponse(
	val id: UUID,
	val nickname: String,
	val color: String,
	val hasProfileImage: Boolean,
	val profileImageVersion: String?,
) {
	companion object {
		fun from(view: MePartnerView) =
			MePartnerResponse(view.id, view.nickname, view.color, view.hasProfileImage, view.profileImageVersion)
	}
}
