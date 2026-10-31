package com.duriseo.server.user.application

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.space.application.SpaceService
import com.duriseo.server.user.domain.SocialAccountRepository
import com.duriseo.server.user.domain.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class MeService(
	private val users: UserRepository,
	private val socialAccounts: SocialAccountRepository,
	private val spaceService: SpaceService,
) {

	/** 내 정보 + 현재 활성 공간 요약 (FR-SET-01, FR-COUPLE-02.AC5). 화면은 이 값을 로컬에 저장해 쓴다 */
	@Transactional(readOnly = true)
	fun get(userId: UUID): MeView {
		val user = users.findByIdOrNull(userId)?.takeIf { it.deletedAt == null }
			?: throw ApiException(ErrorCode.SESSION_INVALID, "user not found")
		val space = spaceService.activeSpaceView(userId)
		val partner = space.partner?.let { membership ->
			val partnerUser = checkNotNull(users.findByIdOrNull(membership.userId)) { "partner user is missing" }
			MePartnerView(
				id = membership.userId,
				nickname = partnerUser.nickname,
				color = membership.color,
				hasProfileImage = partnerUser.profileImageKey != null,
			)
		}
		return MeView(
			id = user.requireId(),
			accountType = user.accountType,
			nickname = user.nickname,
			timeZone = user.timeZone,
			providers = socialAccounts.findAllByUserId(userId).map { it.provider }.sorted(),
			hasProfileImage = user.profileImageKey != null,
			createdAt = user.createdAt.truncatedTo(ChronoUnit.SECONDS),
			space = MeSpaceView(
				id = space.spaceId,
				type = space.type,
				myColor = space.myColor,
				partner = partner,
				togetherColor = space.togetherColor,
				startDate = space.startDate,
				importPending = space.importPending,
			),
		)
	}
}
