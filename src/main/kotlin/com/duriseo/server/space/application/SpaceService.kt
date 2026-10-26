package com.duriseo.server.space.application

import com.duriseo.server.common.time.nowInSeconds
import com.duriseo.server.space.domain.PersonColors
import com.duriseo.server.space.domain.Space
import com.duriseo.server.space.domain.SpaceMember
import com.duriseo.server.space.domain.SpaceMemberRepository
import com.duriseo.server.space.domain.SpaceRepository
import com.duriseo.server.space.domain.SpaceStatus
import com.duriseo.server.space.domain.SpaceType
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
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

	/** 사용자의 활성 공간 종류. 활성 소속이 없으면 null (탈퇴가 커플 공간 여부를 볼 때 쓴다) */
	@Transactional(readOnly = true)
	fun activeSpaceType(userId: UUID): SpaceType? {
		val membership = members.findByUserIdAndLeftAtIsNull(userId) ?: return null
		return spaces.findByIdOrNull(membership.spaceId)?.type
	}

	/**
	 * 탈퇴 2단계 (ERD 4.3, D-36): 사용자가 속했던 UNLINKED 커플 공간의 `purge_at`을 탈퇴 시각 + 90일로 늦춘다.
	 * 이미 그보다 늦으면 그대로 둔다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun extendPurgeOfUnlinkedSpacesForWithdrawal(userId: UUID) {
		val now = clock.nowInSeconds()
		spaces.extendPurgeAtOfSpacesOf(userId, SpaceType.COUPLE, SpaceStatus.UNLINKED, now.plus(WITHDRAWAL_RETENTION), now)
	}

	/** 탈퇴 3단계 (ERD 4.3): 활성 개인 공간과 가져오기 대기 중인 개인 공간을 지운다. 콘텐츠는 CASCADE로 함께 지워진다 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun deletePersonalSpacesOf(userId: UUID) {
		spaces.deleteAllOfTypeBelongingTo(userId, SpaceType.PERSONAL)
	}

	private companion object {
		/** 탈퇴한 사용자가 속했던 커플 공간 보관 기간 (PRD D-36) */
		val WITHDRAWAL_RETENTION: Duration = Duration.ofDays(90)
	}
}
