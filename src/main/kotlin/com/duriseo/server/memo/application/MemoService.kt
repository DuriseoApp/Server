package com.duriseo.server.memo.application

import com.duriseo.server.common.access.SpaceAccessPolicy
import com.duriseo.server.common.time.nowInSeconds
import com.duriseo.server.memo.domain.Memo
import com.duriseo.server.memo.domain.MemoFavoriteRepository
import com.duriseo.server.memo.domain.MemoRepository
import com.duriseo.server.notification.application.NotificationService
import com.duriseo.server.space.application.SpaceService
import com.duriseo.server.user.application.UserService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

/**
 * 메모 쓰기 (API.md 3.4, FR-MEMO-02·03·05·06, FR-NOTI-04, R-01·R-03·R-04·R-06·R-15). 권한은 모두 [SpaceAccessPolicy]가 검사하고
 * 다른 공간의 메모는 403이 아니라 404다 (DB-05).
 *
 * **잠금:** 일정·기념일과 같이 공간 행을 잠그지 않는다 (잠금 순서 users → invite_codes → spaces → 콘텐츠 행, ARCHITECTURE 4장).
 * - `POST`는 행을 넣기 전에 요청자의 `users` 행을 `FOR SHARE`로 잡고 활성 공간·상대를 **잡은 뒤에** 읽는다 ([create]). 연결 해제·탈퇴는
 *   두 사람의 사용자 행을 모두 잠그므로 요청자 행 하나로 한 줄이 서고, 해제가 지운 공간에 메모·알림 행이 남지 않는다.
 * - `PATCH`·`DELETE`는 메모 행만 `FOR UPDATE`로 잠그고 R-01·R-03을 잠근 값으로 다시 확인한다 ([MemoLocker]).
 * - 고정은 알림 행을 만들 수 있어 `POST`와 같이 요청자 행을 먼저 `FOR SHARE`로 잡고 메모 행을 잠근다. 두 사람이 동시에 고정해도
 *   메모 행 잠금 뒤에 `pinned_at`을 다시 읽으므로 전환은 한 번이고 알림도 하나다 (D-134).
 * - 즐겨찾기는 메모 행을 잠그고 `INSERT … ON CONFLICT DO NOTHING`이다.
 *
 * 값 검사는 [MemoWriteRules]가 한다. 검사 순서: `POST`는 R-01 → 값 검사 → 날짜 범위, `PATCH`는 R-01 → R-03 → 값 검사 → 날짜 범위,
 * `DELETE`는 R-01 → R-03 (API.md 3.4 "메모 쓰기 규칙"). 변경은 커밋 뒤 동기화 신호를 위해 [MemoCreated]·[MemoUpdated]·[MemoDeleted]를
 * 발행한다 (발송은 단위 6). 즐겨찾기는 이벤트가 없다 (D-134).
 */
@Service
class MemoService(
	private val policy: SpaceAccessPolicy,
	private val memoRepository: MemoRepository,
	private val favoriteRepository: MemoFavoriteRepository,
	private val userService: UserService,
	private val spaceService: SpaceService,
	private val notifications: NotificationService,
	private val locker: MemoLocker,
	private val events: ApplicationEventPublisher,
	private val clock: Clock,
	transactionManager: PlatformTransactionManager,
) {

	private val tx = TransactionTemplate(transactionManager)

	/**
	 * `POST /memos` (FR-MEMO-02, FR-NOTI-04, R-01 → 값 검사 → R-15). 요청자의 활성 공간에 만든다. 작성자는 요청자다.
	 * 커플 공간에서 `notifyPartner`가 true면 같은 트랜잭션에서 상대의 `PARTNER_CREATED` 알림 행을 만든다. 상대의 `partner_created`
	 * 설정과 관계없이 행은 만들고 설정은 푸시만 막는다 (단위 6). 검사가 거부하면 트랜잭션이 롤백돼 아무것도 남지 않는다.
	 */
	fun create(requesterId: UUID, body: Map<String, Any?>): MemoView =
		checkNotNull(
			tx.execute {
				userService.lockShare(listOf(requesterId))
				val spaceId = checkNotNull(policy.activeSpaceIdOf(requesterId)) { "user has no active space" }
				val partnerId = spaceService.activePartnerIdOf(requesterId)
				val draft = MemoWriteRules.parseCreate(body)

				val now = clock.nowInSeconds()
				val memo = memoRepository.save(Memo(spaceId, requesterId, draft.title, draft.body, draft.memoDate, now))
				val notified = if (draft.notifyPartner && partnerId != null) {
					notifyOf(requesterId, partnerId) { nickname ->
						notifications.createPartnerMemoCreated(partnerId, nickname, memo.title, memo.body, spaceId, memo.requireId(), now)
					}
				} else {
					null
				}
				events.publishEvent(MemoCreated(spaceId, memo.requireId(), requesterId, notified?.first, notified?.second))
				MemoView.of(memo, isFavorite = false)
			},
		)

	/**
	 * `PATCH /memos/{id}` (FR-MEMO-03, R-01 → R-03 → 값 검사 → R-15). 부분 수정이다. 메모 행을 잠근 뒤 R-03을 잠근 값으로 확인한다.
	 * 저장된 값과 같은 값만 보낸 요청(빈 본문 포함)은 아무것도 바꾸지 않는다: `updated_at` 그대로, [MemoUpdated]도 없다 (D-123).
	 * 수정은 상대에게 알리지 않는다 (알림 행 없음, FR-NOTI-04 AC3).
	 */
	@Transactional
	fun update(requesterId: UUID, memoId: UUID, body: Map<String, Any?>): MemoView {
		val memo = locker.lockInActiveSpace(requesterId, memoId)
		policy.requireMemoAuthor(requesterId, memo.authorId)
		val patch = MemoWriteRules.parsePatch(body, memo.title, memo.body)
		val titleChanged = patch.title != null && patch.title.value != memo.title
		val bodyChanged = patch.body != null && patch.body.value != memo.body
		val dateChanged = patch.memoDate != null && patch.memoDate.value != memo.memoDate
		if (titleChanged || bodyChanged || dateChanged) {
			if (titleChanged) memo.title = checkNotNull(patch.title).value
			if (bodyChanged) memo.body = checkNotNull(patch.body).value
			if (dateChanged) memo.memoDate = checkNotNull(patch.memoDate).value
			memo.updatedAt = clock.nowInSeconds()
			events.publishEvent(MemoUpdated(memo.spaceId, memoId, requesterId))
		}
		return MemoView.of(memo, isFavoriteOf(requesterId, memoId))
	}

	/** `DELETE /memos/{id}` (FR-MEMO-03, R-01 → R-03). 즐겨찾기 행은 DB의 ON DELETE CASCADE가 함께 지운다 */
	@Transactional
	fun delete(requesterId: UUID, memoId: UUID) {
		val memo = locker.lockInActiveSpace(requesterId, memoId)
		policy.requireMemoAuthor(requesterId, memo.authorId)
		memoRepository.delete(memo)
		events.publishEvent(MemoDeleted(memo.spaceId, memoId, requesterId))
	}

	/**
	 * `PUT /memos/{id}/pin` (FR-MEMO-05, R-01·R-04, D-134, D-135). 구성원이면 누구든 아무 메모나 고정한다. 멱등이다: 이미 고정이면
	 * `pinned_at`·`pinned_by`·알림 모두 그대로이고 이벤트도 없다. 미고정 → 고정으로 바뀔 때마다 커플 공간이면 고정하지 않은 쪽(자기가 쓴
	 * 메모여도)에 `MEMO_PINNED` 알림 행을 만든다. 고정은 내용 수정이 아니므로 `updated_at`은 바꾸지 않는다 (확인 필요 3).
	 */
	@Transactional
	fun pin(requesterId: UUID, memoId: UUID) {
		userService.lockShare(listOf(requesterId))
		val memo = locker.lockInActiveSpace(requesterId, memoId)
		if (memo.pinnedAt != null) return
		val now = clock.nowInSeconds()
		memo.pinnedAt = now
		memo.pinnedBy = requesterId
		val partnerId = spaceService.activePartnerIdOf(requesterId)
		val notified = partnerId?.let {
			notifyOf(requesterId, it) { nickname ->
				notifications.createMemoPinned(it, nickname, memo.title, memo.body, memo.spaceId, memoId, now)
			}
		}
		events.publishEvent(MemoUpdated(memo.spaceId, memoId, requesterId, notified?.first, notified?.second))
	}

	/** `DELETE /memos/{id}/pin` (FR-MEMO-05, R-01·R-04, D-134). 멱등이다: 고정이 아니면 아무것도 바꾸지 않고 이벤트도 없다. 해제는 알리지 않는다 */
	@Transactional
	fun unpin(requesterId: UUID, memoId: UUID) {
		val memo = locker.lockInActiveSpace(requesterId, memoId)
		if (memo.pinnedAt == null) return
		memo.pinnedAt = null
		memo.pinnedBy = null
		events.publishEvent(MemoUpdated(memo.spaceId, memoId, requesterId))
	}

	/**
	 * `PUT`·`DELETE /memos/{id}/favorite` (FR-MEMO-06, R-01·R-06, D-134). 요청자 본인의 행만 만들고 지운다. 멱등이다(`ON CONFLICT DO NOTHING`,
	 * 없는 것을 지워도 오류가 아님). 본인 값이라 이벤트·동기화 신호가 없다.
	 */
	@Transactional
	fun setFavorite(requesterId: UUID, memoId: UUID, favorite: Boolean) {
		locker.lockInActiveSpace(requesterId, memoId)
		if (favorite) {
			favoriteRepository.insertIfAbsent(memoId, requesterId, clock.nowInSeconds())
		} else {
			favoriteRepository.deleteOne(memoId, requesterId)
		}
	}

	private fun isFavoriteOf(userId: UUID, memoId: UUID): Boolean = favoriteRepository.findFavoriteMemoIds(userId, listOf(memoId)).isNotEmpty()

	/**
	 * [actorId]의 닉네임으로 [partnerId]에게 줄 알림 행을 [create]하고 (상대 ID, 알림 ID)를 돌려준다.
	 * 둘 중 하나를 읽을 수 없으면(탈퇴 등) 만들지 않고 null이다.
	 */
	private fun notifyOf(actorId: UUID, partnerId: UUID, create: (String) -> UUID): Pair<UUID, UUID>? {
		val actor = userService.findActive(actorId) ?: return null
		userService.findActive(partnerId) ?: return null
		return partnerId to create(actor.nickname)
	}
}
