package com.duriseo.server.user.application

import com.duriseo.server.anniversary.application.AnniversaryContentService
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.event.application.EventContentService
import com.duriseo.server.memo.application.MemoContentService
import com.duriseo.server.space.application.ImportCounts
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
	private val eventContent: EventContentService,
	private val anniversaryContent: AnniversaryContentService,
	private val memoContent: MemoContentService,
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
				profileImageVersion = partnerUser.profileImageKey,
			)
		}
		return MeView(
			id = user.requireId(),
			accountType = user.accountType,
			nickname = user.nickname,
			timeZone = user.timeZone,
			providers = socialAccounts.findAllByUserId(userId).map { it.provider }.sorted(),
			hasProfileImage = user.profileImageKey != null,
			profileImageVersion = user.profileImageKey,
			createdAt = user.createdAt.truncatedTo(ChronoUnit.SECONDS),
			space = MeSpaceView(
				id = space.spaceId,
				type = space.type,
				myColor = space.myColor,
				partner = partner,
				togetherColor = space.togetherColor,
				startDate = space.startDate,
				linkedAt = space.linkedAt,
				importPending = space.importPending,
				// 대기 공간이 있을 때만 요청 때 센다 (D-93). 없으면 null
				importCounts = if (space.importPending) importCountsOf(space.pendingImportSpaceIds) else null,
			),
		)
	}

	/**
	 * 가져오기 대기 중인 개인 공간 [spaceIds]의 `events`, `anniversaries`, `memos` 행 수 (`MeSpace.importCounts`, API.md 3.1, D-93).
	 * 요청 때 세고 저장하지 않는다. 각 도메인 서비스가 센다(자동 기념일은 저장하지 않으므로 세지 않는다).
	 */
	private fun importCountsOf(spaceIds: Collection<UUID>) = ImportCounts(
		eventContent.countIn(spaceIds),
		anniversaryContent.countIn(spaceIds),
		memoContent.countIn(spaceIds),
	)
}
