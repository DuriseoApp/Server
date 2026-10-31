package com.duriseo.server.common.access

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.util.UUID

/** 가져오기 대기 중인 개인 공간 (R-18). [coupleSpaceId]는 요청자의 활성 커플 공간, [personalSpaceIds]는 소속에 `left_at`이 있는 개인 공간 */
data class PendingImport(val coupleSpaceId: UUID, val personalSpaceIds: List<UUID>)

/**
 * 서버 권한 검사 규칙 (API.md 2장 R-)을 구현하는 단 한 곳이다 (NFR-SEC-02, ARCHITECTURE 3장).
 * 서비스가 이 정책을 부르고, 컨트롤러·리포지토리에서 따로 검사하지 않는다.
 * 다른 공간의 리소스는 403이 아니라 404다 (DB-05). 이 규칙의 위반은 리뷰에서 항상 Critical이다.
 *
 * - `common`은 어떤 도메인에도 의존하지 않으므로 엔티티·리포지토리 대신 테이블을 직접 읽는다.
 * - 계정 종류(GUEST·MEMBER)는 토큰이 아니라 요청마다 `users.account_type`에서 읽는다.
 *   게스트 연동 직후 같은 access 토큰으로 판단이 바뀌어야 하기 때문이다 (R-14, R-17).
 * - 검사는 아무것도 바꾸기 전에 한다. 거부하면 변경이 없어야 한다.
 * - 네이티브 쿼리는 JPA 쓰기 지연분을 먼저 flush 하므로 같은 트랜잭션에서 방금 바꾼 값도 본다.
 */
@Component
class SpaceAccessPolicy(private val em: EntityManager) {

	/**
	 * R-01: 콘텐츠 리소스의 `space_id` = 요청자의 활성 공간(`space_members.left_at IS NULL`). 아니면 404 `NOT_FOUND`.
	 * [resourceSpaceId]가 null이면 리소스가 없는 경우다. 없는 리소스와 다른 공간의 리소스는 같은 응답이어야 하므로
	 * 호출한 쪽은 조회 결과가 null이어도 그대로 이 메서드에 넘긴다. 허용되면 활성 공간 ID를 돌려준다.
	 */
	fun requireInActiveSpace(requesterId: UUID, resourceSpaceId: UUID?): UUID {
		val activeSpaceId = activeSpaceIdOf(requesterId)
		if (resourceSpaceId == null || activeSpaceId == null || resourceSpaceId != activeSpaceId) throw notFound()
		return activeSpaceId
	}

	/** 요청자의 활성 공간 ID. 없으면 null */
	fun activeSpaceIdOf(userId: UUID): UUID? =
		scalar(
			"select sm.space_id from space_members sm where sm.user_id = :userId and sm.left_at is null",
			"userId" to userId,
		) as UUID?

	/**
	 * R-09: 초대코드 생성·입력은 `account_type = MEMBER`이고 개인 공간에 있을 때만.
	 * 게스트는 403 `GUEST_NOT_ALLOWED`, 이미 커플 공간에 있으면 409 `ALREADY_COUPLED`.
	 */
	fun requireInviteEligible(userId: UUID) {
		requireMember(userId)
		val activeSpaceType = scalar(
			"select s.type from space_members sm join spaces s on s.id = sm.space_id " +
				"where sm.user_id = :userId and sm.left_at is null",
			"userId" to userId,
		) as String? ?: error("user has no active space")
		if (activeSpaceType != PERSONAL_SPACE_TYPE) throw ApiException(ErrorCode.ALREADY_COUPLED, "already in a couple space")
	}

	/**
	 * R-08(처음 만난 날 `PATCH /space`)과 R-18(연결 해제 `DELETE /couple`): 요청자의 활성 공간이 `COUPLE`이어야 한다.
	 * 개인 공간이면 409 `NOT_COUPLE`. 허용되면 활성 커플 공간 ID를 돌려준다.
	 * 공간 행을 잠근 뒤 한 번 더 불러 그 사이 연결이 해제되지 않았는지 확인한다.
	 */
	fun requireCoupleSpace(userId: UUID): UUID {
		val (spaceId, type) = activeSpaceOf(userId) ?: error("user has no active space")
		if (type != COUPLE_SPACE_TYPE) throw notCouple()
		return spaceId
	}

	/**
	 * R-18(기록 가져오기 `POST /couple/import`): 요청자에게 가져오기 대기 중인 개인 공간(소속에 `left_at`이 있는 PERSONAL)이 있을 것.
	 * 그런 공간은 커플 공간에 있는 동안만 있다 (D-78). 게스트·회원 구분 없이 이 조건만 본다.
	 * 없으면 409 `NO_IMPORT_PENDING`(이미 정했거나 해제와 겹침). 허용되면 활성 커플 공간과 대기 공간을 돌려준다.
	 * 공간 행을 잠근 뒤 한 번 더 불러 그 사이 해제되거나 다른 요청이 먼저 정하지 않았는지 확인한다.
	 */
	fun requireImportPending(userId: UUID): PendingImport {
		val (coupleSpaceId, type) = activeSpaceOf(userId) ?: throw noImportPending()
		if (type != COUPLE_SPACE_TYPE) throw noImportPending()
		val pending = em.createNativeQuery(
			"select sm.space_id from space_members sm join spaces s on s.id = sm.space_id " +
				"where sm.user_id = :userId and sm.left_at is not null and s.type = 'PERSONAL' order by sm.space_id",
		)
			.setParameter("userId", userId)
			.resultList
			.map { it as UUID }
		if (pending.isEmpty()) throw noImportPending()
		return PendingImport(coupleSpaceId, pending)
	}

	/**
	 * R-08(사람 색 `PATCH /space/colors`): 커플 공간의 구성원이면 허용한다. 개인 공간에서는 자기 색만 바꿀 수 있어서
	 * `together`가 있거나 [requestedUserIds]에 자기 밖의 사용자(해석할 수 없는 값은 null)가 있으면 409 `NOT_COUPLE`이다.
	 * 허용되면 활성 공간 ID를 돌려준다.
	 */
	fun requireColorChangeAllowed(userId: UUID, requestedUserIds: Collection<UUID?>, togetherRequested: Boolean): UUID {
		val (spaceId, type) = activeSpaceOf(userId) ?: error("user has no active space")
		if (type != COUPLE_SPACE_TYPE) {
			if (togetherRequested || requestedUserIds.any { it != userId }) throw notCouple()
		}
		return spaceId
	}

	/**
	 * R-11: 프로필 사진 조회는 본인 또는 같은 활성 공간의 구성원만. 아니면 404 `NOT_FOUND`.
	 * 없는 사용자, 탈퇴한 사용자(`deleted_at` 있음), 다른 공간의 사용자, 연결이 해제된 이전 상대는 모두 같은 404다.
	 */
	fun requireProfileImageAccess(requesterId: UUID, targetUserId: UUID) {
		val targetExists = count("select count(*) from users u where u.id = :target and u.deleted_at is null", "target" to targetUserId) > 0
		if (!targetExists) throw notFound()
		if (requesterId == targetUserId) return
		val sharesActiveSpace = count(
			"select count(*) from space_members a join space_members b on a.space_id = b.space_id " +
				"where a.user_id = :requester and b.user_id = :target and a.left_at is null and b.left_at is null",
			"requester" to requesterId,
			"target" to targetUserId,
		) > 0
		if (!sharesActiveSpace) throw notFound()
	}

	/** R-14: `POST /auth/link`, `POST /auth/switch`는 GUEST만. 회원이면 403 `GUEST_ONLY`. 소셜 토큰 검증보다 먼저 부른다 */
	fun requireGuest(userId: UUID) {
		if (accountTypeOf(userId) != GUEST_TYPE) throw ApiException(ErrorCode.GUEST_ONLY, "guests only")
	}

	/** R-17: `POST /auth/logout`은 MEMBER만. 게스트면 403 `GUEST_NOT_ALLOWED` */
	fun requireMember(userId: UUID) {
		if (accountTypeOf(userId) != MEMBER_TYPE) throw ApiException(ErrorCode.GUEST_NOT_ALLOWED, "members only")
	}

	/** 인증을 통과한 사용자의 행이 없거나 탈퇴했으면 그 토큰은 더 이상 쓸 수 없다 */
	private fun accountTypeOf(userId: UUID): String =
		scalar("select u.account_type from users u where u.id = :userId and u.deleted_at is null", "userId" to userId) as String?
			?: throw ApiException(ErrorCode.SESSION_INVALID, "user not found")

	private fun scalar(sql: String, vararg params: Pair<String, Any>): Any? {
		val query = em.createNativeQuery(sql)
		params.forEach { (name, value) -> query.setParameter(name, value) }
		return query.resultList.firstOrNull()
	}

	private fun count(sql: String, vararg params: Pair<String, Any>): Long = (scalar(sql, *params) as Number).toLong()

	/** 활성 공간의 (ID, 종류). 활성 소속이 없으면 null */
	private fun activeSpaceOf(userId: UUID): Pair<UUID, String>? {
		val row = em.createNativeQuery(
			"select sm.space_id, s.type from space_members sm join spaces s on s.id = sm.space_id " +
				"where sm.user_id = :userId and sm.left_at is null",
		)
			.setParameter("userId", userId)
			.resultList
			.firstOrNull() as Array<*>? ?: return null
		return (row[0] as UUID) to (row[1] as String)
	}

	private fun notCouple() = ApiException(ErrorCode.NOT_COUPLE, "not in a couple space")

	private fun noImportPending() = ApiException(ErrorCode.NO_IMPORT_PENDING, "no personal space is waiting to be imported")

	/** 없는 리소스와 다른 공간의 리소스는 같은 응답이어야 하므로 메시지도 하나로 고정한다 */
	private fun notFound() = ApiException(ErrorCode.NOT_FOUND, "not found")

	private companion object {
		const val GUEST_TYPE = "GUEST"
		const val MEMBER_TYPE = "MEMBER"
		const val PERSONAL_SPACE_TYPE = "PERSONAL"
		const val COUPLE_SPACE_TYPE = "COUPLE"
	}
}
