package com.duriseo.server.space.api

import com.duriseo.server.space.application.SpaceDetail
import com.duriseo.server.space.application.SpaceMemberDetail
import com.duriseo.server.space.domain.SpaceType
import java.time.LocalDate
import java.util.UUID

/** API.md 3.2 `Space`. `GET /space`, `PATCH /space`, `PATCH /space/colors`의 응답 본문 */
data class SpaceResponse(
	val id: UUID,
	val type: SpaceType,
	val togetherColor: String?,
	val startDate: LocalDate?,
	val members: List<SpaceMemberResponse>,
) {
	companion object {
		fun from(detail: SpaceDetail) = SpaceResponse(
			id = detail.id,
			type = detail.type,
			togetherColor = detail.togetherColor,
			startDate = detail.startDate,
			members = detail.members.map { SpaceMemberResponse.from(it) },
		)
	}
}

/** API.md 3.2 `SpaceMember` */
data class SpaceMemberResponse(
	val userId: UUID,
	val nickname: String,
	val color: String,
	val hasProfileImage: Boolean,
) {
	companion object {
		fun from(detail: SpaceMemberDetail) = SpaceMemberResponse(detail.userId, detail.nickname, detail.color, detail.hasProfileImage)
	}
}
