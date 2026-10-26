package com.duriseo.server.common.access

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.support.IntegrationTestSupport
import com.duriseo.server.support.TestClockConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * API.md 2장 R- 규칙을 `SpaceAccessPolicy` 한 곳에서 확인한다 (NFR-SEC-02). 규칙마다 허용·거부 테스트를 둔다.
 * R-01·R-09·R-11은 단위 1에 쓰는 경로가 없어 정책 컴포넌트를 직접 부른다 (경로 테스트는 그 경로의 단위에서 더한다).
 * R-14의 경로 테스트(`/auth/link`, `/auth/switch`)는 `AuthLinkTest`·`AuthSwitchTest`가, R-17은 `LogoutTest`가 경로로 확인한다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class SpaceAccessPolicyTest : IntegrationTestSupport() {

	@Autowired
	private lateinit var policy: SpaceAccessPolicy

	private fun assertApiError(code: ErrorCode, block: () -> Unit): ApiException {
		val error = assertFailsWith<ApiException> { block() }
		assertEquals(code, error.code)
		return error
	}

	/** 사용자 한 명과 그 사용자의 활성 개인 공간 */
	private fun userWithPersonalSpace(accountType: String = "MEMBER"): Pair<UUID, UUID> {
		val userId = insertUser(accountType)
		val spaceId = insertSpace("PERSONAL")
		insertMember(spaceId, userId)
		return userId to spaceId
	}

	// ── R-01 ────────────────────────────────────────────

	@Test
	fun `R-01 요청자의 활성 공간 리소스는 허용한다`() {
		val (userId, spaceId) = userWithPersonalSpace()

		assertEquals(spaceId, policy.requireInActiveSpace(userId, spaceId))
	}

	@Test
	fun `R-01 다른 공간의 리소스는 404 NOT_FOUND`() {
		val (userId, _) = userWithPersonalSpace()
		val (_, otherSpaceId) = userWithPersonalSpace()

		assertApiError(ErrorCode.NOT_FOUND) { policy.requireInActiveSpace(userId, otherSpaceId) }
		assertEquals(404, ErrorCode.NOT_FOUND.status.value())
	}

	@Test
	fun `R-01 떠난 공간(left_at 있음)의 리소스는 404 NOT_FOUND`() {
		val userId = insertUser("MEMBER")
		val leftSpaceId = insertSpace("COUPLE", unlinked = true)
		insertMember(leftSpaceId, userId, left = true)
		val activeSpaceId = insertSpace("PERSONAL")
		insertMember(activeSpaceId, userId)

		assertApiError(ErrorCode.NOT_FOUND) { policy.requireInActiveSpace(userId, leftSpaceId) }
		// 활성 공간은 여전히 허용한다
		assertEquals(activeSpaceId, policy.requireInActiveSpace(userId, activeSpaceId))
	}

	@Test
	fun `R-01 활성 공간이 하나도 없으면 어떤 공간의 리소스도 404 NOT_FOUND`() {
		val userId = insertUser("MEMBER")
		val leftSpaceId = insertSpace("PERSONAL")
		insertMember(leftSpaceId, userId, left = true)

		assertApiError(ErrorCode.NOT_FOUND) { policy.requireInActiveSpace(userId, leftSpaceId) }
	}

	@Test
	fun `R-01 없는 리소스와 다른 공간의 리소스는 같은 404 응답이다`() {
		val (userId, _) = userWithPersonalSpace()
		val (_, otherSpaceId) = userWithPersonalSpace()

		val missing = assertApiError(ErrorCode.NOT_FOUND) { policy.requireInActiveSpace(userId, null) }
		val foreign = assertApiError(ErrorCode.NOT_FOUND) { policy.requireInActiveSpace(userId, otherSpaceId) }

		// 존재 여부를 응답으로 알 수 없다: 코드·메시지·details가 같다
		assertEquals(missing.code, foreign.code)
		assertEquals(missing.message, foreign.message)
		assertEquals(missing.details, foreign.details)
	}

	// ── R-09 ────────────────────────────────────────────

	@Test
	fun `R-09 개인 공간의 회원은 허용한다`() {
		val (userId, _) = userWithPersonalSpace("MEMBER")

		policy.requireInviteEligible(userId)
	}

	@Test
	fun `R-09 게스트는 403 GUEST_NOT_ALLOWED`() {
		val (userId, _) = userWithPersonalSpace("GUEST")

		assertApiError(ErrorCode.GUEST_NOT_ALLOWED) { policy.requireInviteEligible(userId) }
	}

	@Test
	fun `R-09 커플 공간의 회원은 409 ALREADY_COUPLED`() {
		val userId = insertUser("MEMBER")
		val partnerId = insertUser("MEMBER")
		val coupleSpaceId = insertSpace("COUPLE")
		insertMember(coupleSpaceId, userId)
		insertMember(coupleSpaceId, partnerId, "pick-blue")

		assertApiError(ErrorCode.ALREADY_COUPLED) { policy.requireInviteEligible(userId) }
	}

	@Test
	fun `R-09 게스트 검사가 커플 공간 검사보다 먼저다`() {
		val (userId, _) = userWithPersonalSpace("GUEST")

		assertEquals(403, assertApiError(ErrorCode.GUEST_NOT_ALLOWED) { policy.requireInviteEligible(userId) }.code.status.value())
	}

	// ── R-11 ────────────────────────────────────────────

	@Test
	fun `R-11 본인은 자기 프로필 사진에 접근할 수 있다`() {
		val (userId, _) = userWithPersonalSpace()

		policy.requireProfileImageAccess(userId, userId)
	}

	@Test
	fun `R-11 같은 활성 공간의 상대는 프로필 사진에 접근할 수 있다`() {
		val userId = insertUser("MEMBER")
		val partnerId = insertUser("MEMBER")
		val coupleSpaceId = insertSpace("COUPLE")
		insertMember(coupleSpaceId, userId)
		insertMember(coupleSpaceId, partnerId, "pick-blue")

		policy.requireProfileImageAccess(userId, partnerId)
		policy.requireProfileImageAccess(partnerId, userId)
	}

	@Test
	fun `R-11 다른 공간의 사용자는 404 NOT_FOUND`() {
		val (userId, _) = userWithPersonalSpace()
		val (otherUserId, _) = userWithPersonalSpace()

		assertApiError(ErrorCode.NOT_FOUND) { policy.requireProfileImageAccess(userId, otherUserId) }
	}

	@Test
	fun `R-11 연결이 해제된 이전 상대는 404 NOT_FOUND`() {
		val userId = insertUser("MEMBER")
		val formerPartnerId = insertUser("MEMBER")
		// 연결이 해제돼 둘 다 커플 공간을 떠났고(left_at), 각자 새 개인 공간을 가졌다
		val unlinkedSpaceId = insertSpace("COUPLE", unlinked = true)
		insertMember(unlinkedSpaceId, userId, left = true)
		insertMember(unlinkedSpaceId, formerPartnerId, "pick-blue", left = true)
		insertMember(insertSpace("PERSONAL"), userId)
		insertMember(insertSpace("PERSONAL"), formerPartnerId)

		assertApiError(ErrorCode.NOT_FOUND) { policy.requireProfileImageAccess(userId, formerPartnerId) }
		assertApiError(ErrorCode.NOT_FOUND) { policy.requireProfileImageAccess(formerPartnerId, userId) }
	}

	@Test
	fun `R-11 없는 사용자와 탈퇴한 사용자는 404 NOT_FOUND`() {
		val (userId, _) = userWithPersonalSpace()
		val withdrawnId = insertUser("MEMBER", deleted = true)
		// 탈퇴 전에 같은 공간이었더라도 탈퇴한 사용자는 접근할 수 없다
		val sharedSpaceId = insertSpace("COUPLE")
		insertMember(sharedSpaceId, withdrawnId)

		assertApiError(ErrorCode.NOT_FOUND) { policy.requireProfileImageAccess(userId, UUID.randomUUID()) }
		assertApiError(ErrorCode.NOT_FOUND) { policy.requireProfileImageAccess(userId, withdrawnId) }
		assertApiError(ErrorCode.NOT_FOUND) { policy.requireProfileImageAccess(withdrawnId, withdrawnId) }
	}

	// ── R-14 ────────────────────────────────────────────

	@Test
	fun `R-14 게스트는 게스트 전용 경로를 부를 수 있다`() {
		val (userId, _) = userWithPersonalSpace("GUEST")

		policy.requireGuest(userId)
	}

	@Test
	fun `R-14 회원이 게스트 전용 경로를 부르면 403 GUEST_ONLY`() {
		val (userId, _) = userWithPersonalSpace("MEMBER")

		assertApiError(ErrorCode.GUEST_ONLY) { policy.requireGuest(userId) }
		assertEquals(403, ErrorCode.GUEST_ONLY.status.value())
	}

	@Test
	fun `R-14 연동한 직후에는 같은 사용자도 GUEST_ONLY로 거부한다 (계정 종류는 DB에서 읽는다)`() {
		val (userId, _) = userWithPersonalSpace("GUEST")
		policy.requireGuest(userId)

		makeMember(userId) // 연동

		assertApiError(ErrorCode.GUEST_ONLY) { policy.requireGuest(userId) }
	}

	// ── R-17 ────────────────────────────────────────────

	@Test
	fun `R-17 회원은 회원 전용 경로를 부를 수 있다`() {
		val (userId, _) = userWithPersonalSpace("MEMBER")

		policy.requireMember(userId)
	}

	@Test
	fun `R-17 게스트가 회원 전용 경로를 부르면 403 GUEST_NOT_ALLOWED`() {
		val (userId, _) = userWithPersonalSpace("GUEST")

		assertApiError(ErrorCode.GUEST_NOT_ALLOWED) { policy.requireMember(userId) }
	}

	@Test
	fun `R-17 연동한 직후에는 같은 사용자도 회원 전용 경로를 부를 수 있다`() {
		val (userId, _) = userWithPersonalSpace("GUEST")
		assertApiError(ErrorCode.GUEST_NOT_ALLOWED) { policy.requireMember(userId) }

		makeMember(userId)

		policy.requireMember(userId)
	}

	@Test
	fun `계정 종류를 읽을 사용자가 없거나 탈퇴했으면 SESSION_INVALID`() {
		assertApiError(ErrorCode.SESSION_INVALID) { policy.requireGuest(UUID.randomUUID()) }
		assertApiError(ErrorCode.SESSION_INVALID) { policy.requireMember(insertUser("MEMBER", deleted = true)) }
	}
}
