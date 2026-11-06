package com.duriseo.server.anniversary.application

import com.duriseo.server.anniversary.domain.Anniversary
import com.duriseo.server.anniversary.domain.AnniversaryRepository
import com.duriseo.server.common.access.SpaceAccessPolicy
import com.duriseo.server.common.time.nowInSeconds
import com.duriseo.server.user.application.UserService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
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
 * 값 검사는 [AnniversaryWriteRules]가 한다. 검사 순서: `POST`는 R-01 → 값 검사 → 날짜 범위, `PATCH`는 R-01 → R-05 → 값 검사 → 날짜 범위,
 * `DELETE`는 R-01 → R-05 (API.md 3.3 "검사 순서"). 변경은 커밋 뒤 동기화 신호를 위해 [AnniversaryCreated]·[AnniversaryUpdated]·
 * [AnniversaryDeleted]를 발행한다 (발송은 단위 6).
 */
@Service
class AnniversaryService(
	private val policy: SpaceAccessPolicy,
	private val anniversaryRepository: AnniversaryRepository,
	private val userService: UserService,
	private val locker: AnniversaryLocker,
	private val events: ApplicationEventPublisher,
	private val clock: Clock,
	transactionManager: PlatformTransactionManager,
) {

	private val tx = TransactionTemplate(transactionManager)

	/**
	 * `POST /anniversaries` (FR-ANNIV-03 AC1, R-01 → 값 검사 → R-15). 요청자의 활성 공간에 만든다. 작성자는 요청자다.
	 * 사용자 행을 잠근 [inLockedActiveSpace] 안에서 값을 검사하고 넣는다: 검사가 거부하면 트랜잭션이 롤백돼 아무것도 남지 않는다.
	 */
	fun create(requesterId: UUID, body: Map<String, Any?>): AnniversaryView =
		inLockedActiveSpace(requesterId) { spaceId ->
			val draft = AnniversaryWriteRules.parseCreate(body)
			val anniversary = anniversaryRepository.save(
				Anniversary(spaceId, requesterId, draft.name, draft.date, draft.repeatsYearly, clock.nowInSeconds()),
			)
			events.publishEvent(AnniversaryCreated(spaceId, anniversary.requireId(), requesterId))
			AnniversaryView.of(anniversary)
		}

	/**
	 * `PATCH /anniversaries/{id}` (FR-ANNIV-03, R-01 → R-05 → 값 검사 → R-15). 부분 수정이다. 기념일 행을 잠근 뒤 R-05를 잠근 값으로 확인한다.
	 * 저장된 값과 같은 값만 보낸 요청(빈 본문 포함)은 아무것도 바꾸지 않는다: `updated_at` 그대로, [AnniversaryUpdated]도 없다
	 * (일정 `PATCH`와 같은 방식. 문서에 없는 구현 선택, unit-4.md "2부에서 정한 것").
	 */
	@Transactional
	fun update(requesterId: UUID, anniversaryId: UUID, body: Map<String, Any?>): AnniversaryView {
		val anniversary = locker.lockInActiveSpace(requesterId, anniversaryId)
		policy.requireAnniversaryAuthor(requesterId, anniversary.authorId)
		val patch = AnniversaryWriteRules.parsePatch(body)
		val nameChanged = patch.name != null && patch.name != anniversary.name
		val dateChanged = patch.date != null && patch.date != anniversary.date
		val repeatsChanged = patch.repeatsYearly != null && patch.repeatsYearly != anniversary.repeatsYearly
		if (!nameChanged && !dateChanged && !repeatsChanged) return AnniversaryView.of(anniversary)

		if (nameChanged) anniversary.name = checkNotNull(patch.name)
		if (dateChanged) anniversary.date = checkNotNull(patch.date)
		if (repeatsChanged) anniversary.repeatsYearly = checkNotNull(patch.repeatsYearly)
		anniversary.updatedAt = clock.nowInSeconds()
		events.publishEvent(AnniversaryUpdated(anniversary.spaceId, anniversaryId, requesterId))
		return AnniversaryView.of(anniversary)
	}

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
	 * `POST /anniversaries`가 행을 넣을 때 쓴다. [block]은 이 트랜잭션 안에서 실행된다.
	 */
	fun <T : Any> inLockedActiveSpace(requesterId: UUID, block: (UUID) -> T): T =
		checkNotNull(
			tx.execute {
				userService.lockShare(listOf(requesterId))
				block(checkNotNull(policy.activeSpaceIdOf(requesterId)) { "user has no active space" })
			},
		)
}
