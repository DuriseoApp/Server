package com.duriseo.server.couple.application

import com.duriseo.server.common.access.SpaceAccessPolicy
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.RetryDetails
import com.duriseo.server.common.persistence.UuidV7
import com.duriseo.server.common.time.nowInSeconds
import com.duriseo.server.couple.domain.InviteCodeRepository
import com.duriseo.server.user.application.UserService
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 초대코드 만들기·읽기 (FR-COUPLE-01, API.md 4.4 1단계). 권한은 R-09(회원이고 개인 공간)이며 `SpaceAccessPolicy`가 검사한다.
 * 서버에 자동 재발급 작업은 없다: 만료되면 `GET /invite-codes/current`가 404라서 앱이 `POST /invite-codes`를 다시 부른다.
 */
@Service
class InviteCodeService(
	private val policy: SpaceAccessPolicy,
	private val inviteCodes: InviteCodeRepository,
	private val userService: UserService,
	private val generator: InviteCodeGenerator,
	private val em: EntityManager,
	private val clock: Clock,
) {

	/**
	 * `POST /invite-codes`: 생성·재요청. 요청자의 사용자 행을 잠가 같은 사용자의 겹친 요청을 한 줄로 세운다(두 번째는 429).
	 * 잠근 뒤에 R-09와 60초 간격을 확인한다. 간격이 지났으면 같은 트랜잭션에서 이전 미사용 코드 모두에 `revoked_at`을 기록하고
	 * 새 코드를 만든다. 거부되면 코드가 바뀌지 않는다.
	 */
	@Transactional
	fun issue(userId: UUID): InviteCodeView {
		userService.lockForUpdate(userId)
		policy.requireInviteEligible(userId)

		val now = clock.nowInSeconds()
		val latestCreatedAt = inviteCodes.findLatestCreatedAt(userId)
		if (latestCreatedAt != null) {
			val reissueAvailableAt = latestCreatedAt.plus(REISSUE_INTERVAL)
			if (reissueAvailableAt.isAfter(now)) {
				throw ApiException(
					ErrorCode.INVITE_REISSUE_TOO_SOON,
					"reissue is available at $reissueAvailableAt",
					RetryDetails(RetryAfter.seconds(reissueAvailableAt, now)),
				)
			}
		}

		inviteCodes.revokeUnusedOf(userId, now)
		val code = insertNewCode(userId, now)
		return InviteCodeView(code, now.plus(VALID_FOR), now.plus(REISSUE_INTERVAL))
	}

	/** `GET /invite-codes/current`: 내 유효한 코드. 없으면(만든 적 없음, 만료, 무효, 사용됨) 404 `NOT_FOUND` */
	@Transactional(readOnly = true)
	fun current(userId: UUID): InviteCodeView {
		policy.requireInviteEligible(userId)
		val now = clock.nowInSeconds()
		val valid = inviteCodes.findValidOf(userId, now) ?: throw ApiException(ErrorCode.NOT_FOUND, "no valid invite code")
		return InviteCodeView(valid.code, valid.expiresAt, valid.createdAt.plus(REISSUE_INTERVAL))
	}

	/** 탈퇴하는 사용자의 유효한 코드(사용·무효화되지 않은 코드)를 지운다 (ERD 4.3 4단계). 영속성 컨텍스트를 비운다 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun deleteValidOf(userId: UUID) {
		inviteCodes.deleteValidOf(userId)
	}

	/**
	 * 새 코드를 넣는다. 이미 있는 코드(사용·만료·무효 포함, `uq_invite_codes_code`)와 겹치면 다른 코드로 다시 만든다.
	 * 유일 제약 위반은 PostgreSQL 트랜잭션을 실패 상태로 만들어 같은 트랜잭션에서 다시 넣을 수 없으므로
	 * `INSERT … ON CONFLICT (code) DO NOTHING`으로 한 문장씩 시도한다.
	 */
	private fun insertNewCode(userId: UUID, now: Instant): String {
		repeat(MAX_CODE_ATTEMPTS) {
			val code = generator.generate()
			val inserted = em.createNativeQuery(
				"insert into invite_codes (id, code, creator_user_id, expires_at, created_at) " +
					"values (:id, :code, :creatorUserId, :expiresAt, :createdAt) on conflict (code) do nothing",
			)
				.setParameter("id", UuidV7.generate(now.toEpochMilli()))
				.setParameter("code", code)
				.setParameter("creatorUserId", userId)
				.setParameter("expiresAt", now.plus(VALID_FOR))
				.setParameter("createdAt", now)
				.executeUpdate()
			if (inserted == 1) return code
		}
		error("could not create a unique invite code")
	}

	private companion object {
		/** 코드 유효 시간 (D-05) */
		val VALID_FOR: Duration = Duration.ofMinutes(10)

		/** 재요청 간격 (FR-COUPLE-01.AC3) */
		val REISSUE_INTERVAL: Duration = Duration.ofSeconds(60)

		const val MAX_CODE_ATTEMPTS = 10
	}
}
