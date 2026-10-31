package com.duriseo.server.space.application

import com.duriseo.server.space.domain.SpaceType
import java.time.LocalDate
import java.util.UUID

/** `GET /space`·`PATCH /space`·`PATCH /space/colors`의 내용 (API.md 3.2 `Space`) */
data class SpaceDetail(
	val id: UUID,
	val type: SpaceType,
	val togetherColor: String?,
	val startDate: LocalDate?,
	val members: List<SpaceMemberDetail>,
)

/** API.md 3.2 `SpaceMember` */
data class SpaceMemberDetail(
	val userId: UUID,
	val nickname: String,
	val color: String,
	val hasProfileImage: Boolean,
)
