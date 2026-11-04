package com.duriseo.server.anniversary.application

import com.duriseo.server.anniversary.domain.AnniversaryRepository
import com.duriseo.server.common.access.SpaceAccessPolicy
import com.duriseo.server.user.application.UserService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * 직접 기념일 쓰기 (API.md 3.3 기념일, FR-ANNIV-03, R-01·R-05·R-15). 권한은 모두 [SpaceAccessPolicy]가 검사하고
 * 다른 공간의 기념일은 403이 아니라 404다 (DB-05).
 *
 * **잠금:** 일정과 같이 공간 행을 잠그지 않는다 (잠금 순서 users → invite_codes → spaces, ARCHITECTURE 4장).
 * 수정·삭제는 그 기념일 행만 `FOR UPDATE`로 잠그고 R-01·R-05를 잠근 값으로 다시 확인한다 ([AnniversaryLocker]).
 * 만들기는 행을 넣기 전에 요청자의 `users` 행을 `FOR SHARE`로 먼저 잡고 활성 공간을 **잡은 뒤에** 읽는다 ([inLockedActiveSpace]).
 * 커플 연결·해제·탈퇴는 사용자 행을 `FOR NO KEY UPDATE`로 잡으므로 한 줄로 서고, 연결이 개인 공간을 비었다고 보고 지우는 순간에
 * 기념일이 들어가 사라지거나 FK 위반(500)이 나는 일을 막는다. 일정 `POST`(`EventService.create`)와 달리 상대의 알림 행이 없어
 * 상대 사용자 행은 잡지 않으므로 상대가 바뀌었는지 보는 [com.duriseo.server.user.application.PartnerLockRetry]는 필요 없다:
 * 잠금을 얻은 뒤 읽은 활성 공간이 곧 최신이다(READ COMMITTED).
 *
 * `POST`·`PATCH` 본문 규칙은 PRD v0.10 반영 대기라 아직 없다 (unit-4.md 확인 필요 1).
 */
@Service
class AnniversaryService(
	private val policy: SpaceAccessPolicy,
	private val anniversaryRepository: AnniversaryRepository,
	private val userService: UserService,
	private val locker: AnniversaryLocker,
	private val events: ApplicationEventPublisher,
	transactionManager: PlatformTransactionManager,
) {

	private val tx = TransactionTemplate(transactionManager)

	/**
	 * `DELETE /anniversaries/{id}` (FR-ANNIV-03 AC2, R-01 → R-05). 자동 기념일은 저장하지 않으므로 지울 수 없다:
	 * 가리킬 ID가 없어 임의의 UUID는 없는 기념일과 같은 404다.
	 */
	@Transactional
	fun delete(requesterId: UUID, anniversaryId: UUID) {
		val anniversary = locker.lockInActiveSpace(requesterId, anniversaryId)
		policy.requireAnniversaryAuthor(requesterId, anniversary.authorId)
		anniversaryRepository.delete(anniversary)
		events.publishEvent(AnniversaryDeleted(anniversary.spaceId, anniversaryId, requesterId))
	}

	/**
	 * 새 트랜잭션을 열어 요청자의 `users` 행을 `FOR SHARE`로 잡은 뒤 활성 공간 ID를 읽어 [block]에 넘긴다.
	 * `POST /anniversaries`가 행을 넣을 때 쓴다(본문 규칙은 PRD v0.10 반영 대기). [block]은 이 트랜잭션 안에서 실행된다.
	 */
	fun <T : Any> inLockedActiveSpace(requesterId: UUID, block: (UUID) -> T): T =
		checkNotNull(
			tx.execute {
				userService.lockShare(listOf(requesterId))
				block(checkNotNull(policy.activeSpaceIdOf(requesterId)) { "user has no active space" })
			},
		)
}
