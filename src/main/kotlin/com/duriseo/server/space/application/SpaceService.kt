package com.duriseo.server.space.application

import com.duriseo.server.common.time.nowInSeconds
import com.duriseo.server.space.domain.PersonColors
import com.duriseo.server.space.domain.Space
import com.duriseo.server.space.domain.SpaceMember
import com.duriseo.server.space.domain.SpaceMemberRepository
import com.duriseo.server.space.domain.SpaceRepository
import com.duriseo.server.space.domain.SpaceType
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** 사용자의 활성 공간 요약. 상대의 닉네임은 user 도메인이 채운다 */
data class ActiveSpaceView(
	val spaceId: UUID,
	val type: SpaceType,
	val myColor: String,
	val togetherColor: String?,
	val startDate: LocalDate?,
	val partner: PartnerMembership?,
	val importPending: Boolean,
)

data class PartnerMembership(val userId: UUID, val color: String)

@Service
class SpaceService(
	private val spaces: SpaceRepository,
	private val members: SpaceMemberRepository,
	private val clock: Clock,
) {

	/** 새 사용자의 개인 공간과 소속(주황)을 만든다 (ERD 4.1). 사용자를 만드는 트랜잭션 안에서 부른다 */
	@Transactional
	fun createPersonalSpace(userId: UUID): Space {
		val now = clock.nowInSeconds()
		val space = spaces.save(Space(SpaceType.PERSONAL, now))
		members.save(SpaceMember(space.requireId(), userId, PersonColors.ORANGE, now))
		return space
	}

	/** `GET /me`용 활성 공간 요약. 활성 소속이 없는 것은 데이터 오류라 예외를 던진다 */
	@Transactional(readOnly = true)
	fun activeSpaceView(userId: UUID): ActiveSpaceView {
		val membership = checkNotNull(members.findByUserIdAndLeftAtIsNull(userId)) { "user has no active space" }
		val space = checkNotNull(spaces.findByIdOrNull(membership.spaceId)) { "space of an active membership is missing" }
		val partner = members.findAllBySpaceIdAndLeftAtIsNull(space.requireId())
			.firstOrNull { it.userId != userId }
			?.let { PartnerMembership(it.userId, it.color) }
		return ActiveSpaceView(
			spaceId = space.requireId(),
			type = space.type,
			myColor = membership.color,
			togetherColor = space.togetherColor,
			startDate = space.startDate,
			partner = if (space.type == SpaceType.COUPLE) partner else null,
			importPending = members.countLeftSpacesOfType(userId, SpaceType.PERSONAL) > 0,
		)
	}
}
