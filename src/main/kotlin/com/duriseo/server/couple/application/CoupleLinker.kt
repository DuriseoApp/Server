package com.duriseo.server.couple.application

import com.duriseo.server.common.access.SpaceAccessPolicy
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.FieldError
import com.duriseo.server.common.api.FieldErrorDetails
import com.duriseo.server.common.api.FieldErrorReason
import com.duriseo.server.common.api.RetryDetails
import com.duriseo.server.common.time.nowInSeconds
import com.duriseo.server.couple.domain.InviteCodeAttempt
import com.duriseo.server.couple.domain.InviteCodeAttemptRepository
import com.duriseo.server.couple.domain.InviteCodeRepository
import com.duriseo.server.notification.application.NotificationService
import com.duriseo.server.space.application.SpaceService
import com.duriseo.server.user.application.UserService
import com.duriseo.server.user.domain.User
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * `POST /couple/join`의 트랜잭션 한 덩어리 (API.md 4.4 2~7단계, ERD 4.1, FR-COUPLE-02·03·06, FR-CAL-04.AC3).
 * 바깥에서 부르는 입구는 [CoupleJoinService]다. 이 클래스를 직접 부르지 않는다.
 *
 * **잠금 순서는 users(UUID 오름차순) → invite_codes → spaces다.** 코드를 만든 사람 A는 코드를 잠금 없이 읽어 알아내고,
 * A와 요청자 B를 UUID 순서로 잠근 뒤 코드 행을 `FOR UPDATE`로 다시 읽어 검사한다. 코드 행을 먼저 잠그면
 * 재요청(사용자 → 코드)과 교착한다. 요청자 행을 잠근 뒤에 오입력 횟수를 세므로 같은 사용자의 겹친 입력은 순서가 정해진다.
 *
 * **실패 시도 기록을 `REQUIRES_NEW`로 하지 않는 이유.** 바깥 트랜잭션이 요청자 `users` 행을 `FOR UPDATE`로 잡고 있는데,
 * `invite_code_attempts.user_id`의 외래 키 검사는 그 행에 `FOR KEY SHARE`를 요구해서 둘이 서로를 기다린다(자기 교착).
 * 그래서 "실패로 세는 거부"는 예외를 던지지 않고 [JoinOutcome.Rejected]를 돌려준다: 아무것도 바꾸기 전에 시도 행만 넣고
 * 커밋한 뒤 [CoupleJoinService]가 오류를 던진다. 응답이 오류여도 기록이 남고, 횟수를 세는 잠금도 그대로 이어진다.
 */
@Service
class CoupleLinker(
	private val policy: SpaceAccessPolicy,
	private val inviteCodes: InviteCodeRepository,
	private val attempts: InviteCodeAttemptRepository,
	private val userService: UserService,
	private val spaceService: SpaceService,
	private val content: CoupleContentGateway,
	private val notifications: NotificationService,
	private val events: ApplicationEventPublisher,
	private val clock: Clock,
) {

	@Transactional
	fun attempt(requesterId: UUID, rawCode: Any?): JoinOutcome {
		// 1~3번: R-09와 code 누락은 시도로 남기지 않는 거부라 잠그기 전에 먼저 걸러 낸다 (잠근 뒤 R-09를 한 번 더 본다)
		policy.requireInviteEligible(requesterId)
		val code = normalizedCode(rawCode)
		val formatValid = InviteCodeFormat.isValid(code)
		val creatorId = if (formatValid) inviteCodes.findCreatorIdByCode(code) else null

		val users = lockUsersInOrder(listOfNotNull(requesterId, creatorId))
		policy.requireInviteEligible(requesterId)
		val now = clock.nowInSeconds()

		// 4번: 오입력 제한 (이 429 자체는 시도로 남기지 않는다)
		rejectIfRateLimited(requesterId, now)
		// 5번: 형식이 틀린 코드, 6번: 없는 코드. 잠금 순서를 지키려고 처음 읽을 때 없던 코드는 그대로 없는 코드로 본다
		if (!formatValid) return reject(requesterId, now, ErrorCode.INVITE_NOT_FOUND, "invalid invite code format")
		if (creatorId == null) return reject(requesterId, now, ErrorCode.INVITE_NOT_FOUND, "invite code does not exist")
		val invite = inviteCodes.findByCodeForUpdate(code)
			?: return reject(requesterId, now, ErrorCode.INVITE_NOT_FOUND, "invite code does not exist")

		// 7~10번: 코드 행을 잠근 뒤 검사한다. 9번(만든 사람이 연결 중)이 10번(만료·무효)보다 먼저다 (DB-16)
		if (invite.creatorUserId == requesterId) return reject(requesterId, now, ErrorCode.INVITE_SELF, "invite code is your own")
		if (invite.usedAt != null) return reject(requesterId, now, ErrorCode.INVITE_USED, "invite code is already used")
		if (spaceService.isInCoupleSpace(creatorId)) {
			return reject(requesterId, now, ErrorCode.INVITE_UNAVAILABLE, "invite code creator is in a couple space")
		}
		if (invite.revokedAt != null || !invite.expiresAt.isAfter(now)) {
			return reject(requesterId, now, ErrorCode.INVITE_EXPIRED, "invite code is expired or revoked")
		}

		return JoinOutcome.Linked(link(invite.requireId(), users.getValue(creatorId), users.getValue(requesterId), now))
	}

	/** 3번: 공백만 있거나 없으면 400 `VALIDATION_FAILED`(`code`, `REQUIRED`). 문자열이 아니면 `INVALID` */
	private fun normalizedCode(raw: Any?): String {
		if (raw == null) throw codeValidationFailed(FieldErrorReason.REQUIRED)
		if (raw !is String) throw codeValidationFailed(FieldErrorReason.INVALID)
		val normalized = InviteCodeFormat.normalize(raw)
		if (normalized.isEmpty()) throw codeValidationFailed(FieldErrorReason.REQUIRED)
		return normalized
	}

	private fun codeValidationFailed(reason: FieldErrorReason) = ApiException(
		ErrorCode.VALIDATION_FAILED,
		"code: $reason",
		FieldErrorDetails(listOf(FieldError("code", reason))),
	)

	/** 사용자 행을 UUID 오름차순으로 잠근다. 같은 사용자가 겹치면 한 번만 잠근다 */
	private fun lockUsersInOrder(userIds: List<UUID>): Map<UUID, User> =
		userIds.distinct().sorted().associateWith { userService.lockForUpdate(it) }

	/** 4번: 최근 1시간(지금부터 거꾸로) 실패가 10번 이상이면 429. 10번째로 최근 실패 + 1시간까지 남은 초를 알려 준다 */
	private fun rejectIfRateLimited(userId: UUID, now: Instant) {
		val tenthMostRecentFailure = attempts
			.findFailureTimesSince(userId, now.minus(ATTEMPT_WINDOW), PageRequest.of(MAX_FAILURES - 1, 1))
			.firstOrNull() ?: return
		throw ApiException(
			ErrorCode.INVITE_RATE_LIMITED,
			"too many failed invite code attempts",
			RetryDetails(RetryAfter.seconds(tenthMostRecentFailure.plus(ATTEMPT_WINDOW), now)),
		)
	}

	/** 5~10번 거부: 실패 시도를 기록하고 오류를 돌려준다 (트랜잭션은 커밋된다. 거부 전에는 아무것도 바꾸지 않았다) */
	private fun reject(userId: UUID, now: Instant, code: ErrorCode, message: String): JoinOutcome.Rejected {
		attempts.save(InviteCodeAttempt(userId, false, now))
		return JoinOutcome.Rejected(ApiException(code, message))
	}

	/** 표를 통과한 뒤의 연결 (API.md 4.4 4~6단계). [creator]는 코드를 만든 A, [joiner]는 입력한 B */
	private fun link(inviteId: UUID, creator: User, joiner: User, now: Instant): CoupleJoinView {
		// 아래 처리 도중 영속성 컨텍스트가 비워질 수 있어(일괄 UPDATE) 필요한 값은 먼저 꺼내 둔다
		val creatorId = creator.requireId()
		val joinerId = joiner.requireId()
		val creatorNickname = creator.nickname
		val joinerNickname = joiner.nickname

		// 4단계 복구 확인 (users → invite_codes 다음에 spaces 행을 잠근다)
		val restorableSpaceId = spaceService.findRestorableCoupleSpace(creatorId, joinerId, now)

		// 5단계 개인 공간 처리 (D-78): 기록이 있으면 소속에 left_at만 기록(가져오기 대기), 없으면 공간을 지운다.
		// 커플 소속이 생기기 전에 끝내야 uq_space_members_active_user를 지킨다
		settlePersonalSpace(creatorId, now)
		settlePersonalSpace(joinerId, now)

		val spaceId = if (restorableSpaceId != null) {
			spaceService.restoreCoupleSpace(restorableSpaceId, now)
			restorableSpaceId
		} else {
			spaceService.createCoupleSpace(creatorId, joinerId, now)
		}

		// 5단계 코드 사용 처리와 두 사람의 다른 유효한 코드 무효화 (DB-16). 입력한 코드를 먼저 사용 처리하면 무효화 대상에서 빠진다
		inviteCodes.markUsed(inviteId, joinerId, now)
		inviteCodes.revokeValidOfUsers(listOf(creatorId, joinerId), now)
		attempts.save(InviteCodeAttempt(joinerId, true, now))

		// 6단계 알림 행: 받는 사람 기준의 상대 닉네임
		val notificationIds = mapOf(
			creatorId to notifications.createCoupleLinked(creatorId, joinerNickname, now),
			joinerId to notifications.createCoupleLinked(joinerId, creatorNickname, now),
		)
		events.publishEvent(CoupleLinked(spaceId, creatorId, joinerId, restorableSpaceId != null, notificationIds))
		return CoupleJoinView(spaceId, restorableSpaceId != null, spaceService.hasPendingImport(joinerId))
	}

	private fun settlePersonalSpace(userId: UUID, now: Instant) {
		val personalSpaceId = checkNotNull(policy.activeSpaceIdOf(userId)) { "user has no active space" }
		if (content.hasContent(personalSpaceId)) {
			spaceService.leaveSpace(userId, personalSpaceId, now)
		} else {
			spaceService.deleteSpace(personalSpaceId)
		}
	}

	private companion object {
		/** 오입력 제한: 최근 1시간에 실패 10번 (NFR-SEC-03, D-83) */
		const val MAX_FAILURES = 10
		val ATTEMPT_WINDOW: Duration = Duration.ofHours(1)
	}
}
