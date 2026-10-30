package com.duriseo.server.space.application

import com.duriseo.server.common.time.nowInSeconds
import com.duriseo.server.space.domain.PersonColors
import com.duriseo.server.space.domain.Space
import com.duriseo.server.space.domain.SpaceMember
import com.duriseo.server.space.domain.SpaceMemberRepository
import com.duriseo.server.space.domain.SpaceRepository
import com.duriseo.server.space.domain.SpaceStatus
import com.duriseo.server.space.domain.SpaceType
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
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

	/** 사용자가 지금 커플 공간에 있는가 (초대코드를 만든 사람이 연결 중인지 볼 때 쓴다, API.md 4.4 2단계 9번) */
	@Transactional(readOnly = true)
	fun isInCoupleSpace(userId: UUID): Boolean = members.findActiveSpaceType(userId) == SpaceType.COUPLE

	/** 사용자에게 가져오기 대기 중인 개인 공간(소속에 `left_at`이 있는 PERSONAL)이 있는가 (API.md 4.4 6단계·10단계) */
	@Transactional(readOnly = true)
	fun hasPendingImport(userId: UUID): Boolean = members.countLeftSpacesOfType(userId, SpaceType.PERSONAL) > 0

	/**
	 * 복구할 수 있는 이전 커플 공간 ID (FR-COUPLE-06, API.md 4.4 4단계). 찾으면 그 공간 행을 잠근다.
	 * 없으면 null. 잠금 순서(users → invite_codes → spaces)를 지키려고 사용자·코드를 잠근 뒤에 부른다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun findRestorableCoupleSpace(userA: UUID, userB: UUID, now: Instant): UUID? =
		spaces.findRestorableForUpdate(userA, userB, SpaceType.COUPLE, SpaceStatus.UNLINKED, now, PageRequest.of(0, 1))
			.firstOrNull()?.requireId()

	/**
	 * 이전 커플 공간을 ACTIVE로 되돌린다: `unlinked_at`, `purge_at`을 비우고 두 소속의 `left_at`을 비운다 (FR-COUPLE-06).
	 * 구성원 색과 '함께' 색, 처음 만난 날, 콘텐츠는 그대로다. 두 사람의 개인 공간 소속을 먼저 정리한 뒤에 부른다
	 * (활성 소속은 사용자당 하나라는 부분 UNIQUE `uq_space_members_active_user` 때문).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun restoreCoupleSpace(spaceId: UUID, now: Instant) {
		spaces.reactivate(spaceId, SpaceStatus.ACTIVE, now)
		members.reopenAllOf(spaceId)
	}

	/**
	 * 새 커플 공간과 소속 둘을 만든다: 함께 보라, 만든 사람 주황, 입력한 사람 파랑 (FR-CAL-04.AC3, ERD 4.1).
	 * 두 사람의 개인 공간 소속을 먼저 정리한 뒤에 부른다. 소속 INSERT가 UPDATE보다 먼저 나가는 JPA 쓰기 순서 때문에
	 * 부분 UNIQUE에 걸리지 않도록 각 단계를 바로 반영(flush)한다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun createCoupleSpace(creatorUserId: UUID, joinerUserId: UUID, now: Instant): UUID {
		val space = Space(SpaceType.COUPLE, now)
		space.togetherColor = PersonColors.PURPLE
		spaces.saveAndFlush(space)
		members.saveAndFlush(SpaceMember(space.requireId(), creatorUserId, PersonColors.ORANGE, now))
		members.saveAndFlush(SpaceMember(space.requireId(), joinerUserId, PersonColors.BLUE, now))
		return space.requireId()
	}

	/** 사용자의 활성 소속을 떠난다. 가져오기 대기 중인 개인 공간으로 남길 때 쓴다 (API.md 4.4 5단계) */
	@Transactional(propagation = Propagation.MANDATORY)
	fun leaveSpace(userId: UUID, spaceId: UUID, now: Instant) {
		members.closeActiveMembership(userId, spaceId, now)
	}

	/** 공간 하나를 지운다. 소속·콘텐츠·공간 알림은 CASCADE로 함께 지워진다 (ERD 4.5) */
	@Transactional(propagation = Propagation.MANDATORY)
	fun deleteSpace(spaceId: UUID) {
		spaces.deleteSpaceById(spaceId)
	}

	private companion object {
		/** 탈퇴한 사용자가 속했던 커플 공간 보관 기간 (PRD D-36) */
		val WITHDRAWAL_RETENTION: Duration = Duration.ofDays(90)
	}
}
