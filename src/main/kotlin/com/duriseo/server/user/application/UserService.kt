package com.duriseo.server.user.application

import com.duriseo.server.common.access.SpaceAccessPolicy
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.time.nowInSeconds
import com.duriseo.server.space.application.SpaceService
import com.duriseo.server.user.domain.AccountType
import com.duriseo.server.user.domain.NotificationSettings
import com.duriseo.server.user.domain.NotificationSettingsRepository
import com.duriseo.server.user.domain.Nicknames
import com.duriseo.server.user.domain.User
import com.duriseo.server.user.domain.UserRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class UserService(
	private val users: UserRepository,
	private val notificationSettings: NotificationSettingsRepository,
	private val spaceService: SpaceService,
	private val policy: SpaceAccessPolicy,
	private val events: ApplicationEventPublisher,
	private val clock: Clock,
) {

	/** 게스트 사용자를 만든다: users(GUEST, "게스트"), notification_settings, 개인 공간·소속 (API.md 4.1, ERD 4.1) */
	@Transactional
	fun createGuest(): User = createUser(AccountType.GUEST, Nicknames.GUEST_DEFAULT)

	/**
	 * 새 회원을 만든다 (2부 `/auth/social` 신규 가입이 쓴다): users(MEMBER), notification_settings, 개인 공간·소속.
	 * 닉네임은 소셜 프로필 이름에서 정한다 (FR-AUTH-07.AC1, D-56, D-69, D-82). social_accounts 행은 부른 쪽이 만든다.
	 */
	@Transactional
	fun createMember(socialName: String?): User = createUser(AccountType.MEMBER, Nicknames.fromSocialName(socialName))

	/**
	 * `PATCH /me`: 넘어온 값만 바꾼다. 값 검증은 api 계층이 했다 (닉네임은 정리된 값, 시간대는 IANA 이름).
	 * 시간대가 실제로 바뀌면 `UserTimeZoneChanged`를 발행한다.
	 */
	@Transactional
	fun updateProfile(userId: UUID, nickname: String?, timeZone: String?) {
		val user = users.findByIdOrNull(userId)?.takeIf { it.deletedAt == null }
			?: throw ApiException(ErrorCode.SESSION_INVALID, "user not found")
		val previousTimeZone = user.timeZone
		if (nickname != null) user.nickname = nickname
		if (timeZone != null) user.timeZone = timeZone
		user.updatedAt = clock.nowInSeconds()
		if (timeZone != null && timeZone != previousTimeZone) {
			events.publishEvent(UserTimeZoneChanged(userId, previousTimeZone, timeZone))
		}
	}

	/**
	 * 게스트 연동 (`POST /auth/link`, API.md 4.3 3~4단계): 같은 사용자를 MEMBER로 바꾼다. ID와 모든 데이터는 그대로다.
	 * 닉네임이 정확히 "게스트"일 때만 소셜 프로필 이름으로 바꾼다 (이름이 없거나 공백뿐이면 "사용자", D-68, D-69, D-82).
	 * 사용자가 직접 바꾼 닉네임은 그대로 둔다. 사용자 행을 잠근 뒤 처리하며, 그 사이 회원이 됐으면 403 GUEST_ONLY다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun promoteGuestToMember(userId: UUID, socialName: String?) {
		val user = lockForUpdate(userId)
		// 잠근 뒤 R-14를 정책에서 다시 확인한다 (탈퇴했으면 SESSION_INVALID, 그 사이 회원이 됐으면 GUEST_ONLY)
		policy.requireGuest(userId)
		user.accountType = AccountType.MEMBER
		if (user.nickname == Nicknames.GUEST_DEFAULT) user.nickname = Nicknames.fromSocialName(socialName)
		user.updatedAt = clock.nowInSeconds()
	}

	/** 사용자 행을 잠그고(SELECT … FOR UPDATE) 읽는다. 로그인 3종의 이전 세션·기기 정리와 switch가 쓴다 (D-51, DB-12) */
	@Transactional(propagation = Propagation.MANDATORY)
	fun lockForUpdate(userId: UUID): User =
		checkNotNull(users.findByIdForUpdate(userId)) { "user to lock is missing" }

	/**
	 * 사용자 행과 커플 상대의 행을 **UUID 오름차순**으로 잠그고(SELECT … FOR UPDATE) 사용자를 돌려준다. 연결 해제와 탈퇴가 쓴다.
	 * 두 사람이 한 커플 공간을 해제·탈퇴하는 흐름은 모두 사용자 둘을 같은 순서로 잠근 뒤에 공간 행을 잠가야 한다:
	 * 한쪽이 공간 행을 먼저 잡고 상대 알림·개인 공간 소속을 넣으면(외래 키 검사가 상대 행에 `FOR KEY SHARE`를 요구) 상대 행을
	 * 잡은 쪽과 교착한다 (잠금 순서 users → invite_codes → spaces).
	 * 상대는 잠금 없이 먼저 읽는다. 그 사이 커플이 바뀌었으면 새 상대도 이어서 잠근다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun lockForUpdateWithPartner(userId: UUID): User {
		val locked = mutableMapOf<UUID, User>()
		listOfNotNull(userId, spaceService.activePartnerIdOf(userId)).sorted().forEach { locked[it] = lockForUpdate(it) }
		spaceService.activePartnerIdOf(userId)?.takeIf { it !in locked }?.let { locked[it] = lockForUpdate(it) }
		return locked.getValue(userId)
	}

	/** 공간 구성원 목록이 보여 줄 닉네임·프로필 사진 여부 (`GET /space`, API.md 3.2 `SpaceMember`). 없는 ID는 결과에 없다 */
	@Transactional(readOnly = true)
	fun profilesOf(userIds: Collection<UUID>): Map<UUID, UserProfile> =
		users.findAllById(userIds).associate { user ->
			user.requireId() to UserProfile(user.requireId(), user.nickname, user.profileImageKey != null)
		}

	/** 탈퇴하지 않은 사용자. 없거나 탈퇴했으면 null */
	@Transactional(readOnly = true)
	fun findActive(userId: UUID): User? = users.findByIdOrNull(userId)?.takeIf { it.deletedAt == null }

	private fun createUser(accountType: AccountType, nickname: String): User {
		val now = clock.nowInSeconds()
		val user = users.save(User(accountType, nickname, User.DEFAULT_TIME_ZONE, now))
		notificationSettings.save(NotificationSettings(user.requireId(), now))
		spaceService.createPersonalSpace(user.requireId())
		return user
	}
}
