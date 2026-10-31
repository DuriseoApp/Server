package com.duriseo.server.space.application

import com.duriseo.server.common.access.SpaceAccessPolicy
import com.duriseo.server.space.domain.SpaceMemberRepository
import com.duriseo.server.space.domain.SpaceRepository
import com.duriseo.server.space.domain.SpaceType
import com.duriseo.server.user.application.UserService
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 현재 공간 상세 (`GET /space`, FR-SET-01, FR-CAL-04, D-57). 요청자의 활성 공간만 대상이라 경로에 공간 ID가 없다 (R-01).
 * 해제된 이전 커플 공간은 활성 소속이 없어서 나오지 않는다.
 */
@Service
class SpaceDetailService(
	private val policy: SpaceAccessPolicy,
	private val spaces: SpaceRepository,
	private val members: SpaceMemberRepository,
	private val userService: UserService,
) {

	@Transactional(readOnly = true)
	fun get(userId: UUID): SpaceDetail {
		// R-01: 활성 공간은 SpaceAccessPolicy가 찾는다
		val activeSpaceId = checkNotNull(policy.activeSpaceIdOf(userId)) { "user has no active space" }
		val space = checkNotNull(spaces.findByIdOrNull(activeSpaceId)) { "space of an active membership is missing" }
		val activeMembers = members.findAllBySpaceIdAndLeftAtIsNull(space.requireId()).sortedBy { it.joinedAt }
		val profiles = userService.profilesOf(activeMembers.map { it.userId })
		return SpaceDetail(
			id = space.requireId(),
			type = space.type,
			togetherColor = if (space.type == SpaceType.COUPLE) space.togetherColor else null,
			startDate = if (space.type == SpaceType.COUPLE) space.startDate else null,
			members = activeMembers.map { member ->
				val profile = checkNotNull(profiles[member.userId]) { "member user is missing" }
				SpaceMemberDetail(member.userId, profile.nickname, member.color, profile.hasProfileImage)
			},
		)
	}
}
