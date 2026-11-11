package com.duriseo.server.user.application

import com.duriseo.server.common.access.SpaceAccessPolicy
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.persistence.sortedAsPostgres
import com.duriseo.server.common.time.nowInSeconds
import com.duriseo.server.space.application.SpaceService
import com.duriseo.server.space.application.SpaceUpdated
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
	 * 시간대가 실제로 바뀌면 `UserTimeZoneChanged`를, 닉네임이 실제로 바뀌고 커플 공간이면 `SpaceUpdated`를 발행한다(상대에게 SYNC `SPACE`/`UPDATED`, 단위 6).
	 * 첫 줄에서 사용자 행을 `FOR UPDATE`로 잠가 같은 사용자의 `PUT /events/{id}/reminder`(`lockShare`)와 한 줄로 세운다:
	 * 시간대 재계산(리스너)이 미리 읽은 알림 값으로 덮어쓰거나, 방금 만든 알림을 놓치지 않게 한다.
	 */
	@Transactional
	fun updateProfile(userId: UUID, nickname: String?, timeZone: String?) {
		val locked = users.findByIdForUpdate(userId)
		val user = locked?.takeIf { it.deletedAt == null }
			?: throw ApiException(ErrorCode.SESSION_INVALID, "user not found")
		val previousTimeZone = user.timeZone
		val nicknameChanged = nickname != null && nickname != user.nickname
		if (nickname != null) user.nickname = nickname
		if (timeZone != null) user.timeZone = timeZone
		user.updatedAt = clock.nowInSeconds()
		if (timeZone != null && timeZone != previousTimeZone) {
			events.publishEvent(UserTimeZoneChanged(userId, previousTimeZone, timeZone))
		}
		// 닉네임이 바뀌어 성공하고 커플 공간이면 상대에게 SYNC SPACE/UPDATED를 보낸다 (API.md 3.1 PATCH /me, 6장). 같은 값이면 없다 (D-123)
		if (nicknameChanged && spaceService.isInCoupleSpace(userId)) {
			policy.activeSpaceIdOf(userId)?.let { events.publishEvent(SpaceUpdated(it, userId)) }
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
	 * 사용자 행과 커플 상대의 행을 **UUID 오름차순**으로 잠그고(`PESSIMISTIC_WRITE`, PostgreSQL에서는 `FOR NO KEY UPDATE`) 사용자를 돌려준다.
	 * 연결 해제와 탈퇴가 쓴다. 두 사람이 한 커플 공간을 해제·탈퇴하는 흐름은 모두 사용자 둘을 같은 순서로 잠근 뒤에 공간 행을 잠가야 한다:
	 * 사용자 행끼리 서로 반대 순서로 기다리지 않게 하고, 사용자 행을 `FOR SHARE`로 먼저 잡는 일정 쓰기(`lockShare`)와도 같은 순서를 지킨다
	 * (잠금 순서 users → invite_codes → spaces). 외래 키 검사는 참조되는 행에 `FOR KEY SHARE`를 잡는다.
	 * 상대는 잠금 없이 먼저 읽는다. 잠근 뒤 상대가 바뀌었으면(그 사이 연결·해제됨) [PartnerLockRetry] 안에서는
	 * [PartnerChangedException]을 던져 트랜잭션을 롤백하고 처음부터 다시 잠그게 한다. 다시 시도할 수 없으면(재시도를 다 썼거나
	 * 도우미 밖) 새 상대를 이어서 잠근다. 이 경우 순서가 어긋날 수 있다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun lockForUpdateWithPartner(userId: UUID): User {
		val locked = mutableMapOf<UUID, User>()
		listOfNotNull(userId, spaceService.activePartnerIdOf(userId)).sortedAsPostgres().forEach { locked[it] = lockForUpdate(it) }
		val current = spaceService.activePartnerIdOf(userId)
		if (current != null && current !in locked) {
			if (PartnerLockRetry.canRetry()) throw PartnerChangedException()
			locked[current] = lockForUpdate(current)
		}
		return locked.getValue(userId)
	}

	/**
	 * [userIds]의 사용자 행을 ID 오름차순으로 `FOR SHARE`로 잠근다. 해제·탈퇴의 `PESSIMISTIC_WRITE`(PostgreSQL에서는 `FOR NO KEY UPDATE`)와
	 * 충돌하므로 그 트랜잭션이 끝나기를 기다리고, `FOR SHARE`끼리는 서로 막지 않는다. 하는 일은 일정 쓰기를 해제와 한 줄로 세우는 것이다
	 * (`FOR KEY SHARE`는 `FOR NO KEY UPDATE`와 충돌하지 않아 기다리지 않는다). 사용자 행을 먼저 잡는 순서(users → … → spaces)는 그대로 지킨다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun lockShare(userIds: Collection<UUID>) {
		if (userIds.isNotEmpty()) users.lockShare(userIds.distinct())
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
