package com.duriseo.server.push.application

import com.duriseo.server.anniversary.application.AnniversaryCreated
import com.duriseo.server.anniversary.application.AnniversaryDeleted
import com.duriseo.server.anniversary.application.AnniversaryUpdated
import com.duriseo.server.couple.application.CoupleLinked
import com.duriseo.server.couple.application.CoupleUnlinked
import com.duriseo.server.event.application.EventCreated
import com.duriseo.server.event.application.EventDeleted
import com.duriseo.server.event.application.EventUpdated
import com.duriseo.server.memo.application.MemoCreated
import com.duriseo.server.memo.application.MemoDeleted
import com.duriseo.server.memo.application.MemoUpdated
import com.duriseo.server.space.application.SpaceUpdated
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * 단위 1~5의 도메인 이벤트를 커밋 뒤에 받아 동기화 신호와 푸시를 보낸다 (API.md 6장, 7장, ARCHITECTURE 4장).
 * 롤백된 트랜잭션의 이벤트는 오지 않아 신호도 없다 (NFR-SYNC-01). 실제 전송과 오류 삼키기는 [NotificationDispatcher]가 한다.
 *
 * - 일정·기념일·메모: 공간의 다른 구성원에게 `EVENT`·`ANNIVERSARY`·`MEMO` 신호. 이벤트에 알림 행이 있으면 그 상대에게 푸시와 `NOTIFICATION` 신호.
 *   즐겨찾기는 이벤트가 없어 신호도 없다 (D-134).
 * - `SpaceUpdated`(처음 만난 날·색, 커플 공간의 닉네임 변경): `SPACE`/`UPDATED` 하나. `ANNIVERSARY` 신호는 따로 보내지 않는다.
 * - `CoupleLinked`: 두 사람 모두에게 `SPACE`/`LINKED`(`id` 없음)와 각자의 알림.
 * - `CoupleUnlinked`: 상대에게 `SPACE`/`UNLINKED`와 알림. 탈퇴로 인한 해제도 이 이벤트가 보낸다 — `UserWithdrawn`은 같은 상대를 담고
 *   있지만 알림 ID가 없어 이쪽만 듣고, `UserWithdrawn`에는 듣는 쪽을 두지 않는다 (같은 상대에게 두 번 가지 않게).
 */
@Component
class PushEventListener(private val dispatcher: NotificationDispatcher) {

	// ── 일정 ────────────────────────────────────────────

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun onEventCreated(event: EventCreated) {
		dispatcher.syncToOthers(event.spaceId, event.createdByUserId, SyncResource.EVENT, event.eventId, SyncAction.CREATED)
		event.notificationId?.let { dispatcher.dispatch(it) }
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun onEventUpdated(event: EventUpdated) {
		dispatcher.syncToOthers(event.spaceId, event.changedByUserId, SyncResource.EVENT, event.eventId, SyncAction.UPDATED)
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun onEventDeleted(event: EventDeleted) {
		dispatcher.syncToOthers(event.spaceId, event.deletedByUserId, SyncResource.EVENT, event.eventId, SyncAction.DELETED)
	}

	// ── 직접 기념일 ──────────────────────────────────────

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun onAnniversaryCreated(event: AnniversaryCreated) {
		dispatcher.syncToOthers(event.spaceId, event.createdByUserId, SyncResource.ANNIVERSARY, event.anniversaryId, SyncAction.CREATED)
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun onAnniversaryUpdated(event: AnniversaryUpdated) {
		dispatcher.syncToOthers(event.spaceId, event.changedByUserId, SyncResource.ANNIVERSARY, event.anniversaryId, SyncAction.UPDATED)
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun onAnniversaryDeleted(event: AnniversaryDeleted) {
		dispatcher.syncToOthers(event.spaceId, event.deletedByUserId, SyncResource.ANNIVERSARY, event.anniversaryId, SyncAction.DELETED)
	}

	// ── 메모 ────────────────────────────────────────────

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun onMemoCreated(event: MemoCreated) {
		dispatcher.syncToOthers(event.spaceId, event.createdByUserId, SyncResource.MEMO, event.memoId, SyncAction.CREATED)
		event.notificationId?.let { dispatcher.dispatch(it) }
	}

	/** 수정·고정·해제 모두 `MEMO`/`UPDATED`. 미고정 → 고정이면 상대의 `MEMO_PINNED` 알림도 보낸다 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun onMemoUpdated(event: MemoUpdated) {
		dispatcher.syncToOthers(event.spaceId, event.changedByUserId, SyncResource.MEMO, event.memoId, SyncAction.UPDATED)
		event.notificationId?.let { dispatcher.dispatch(it) }
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun onMemoDeleted(event: MemoDeleted) {
		dispatcher.syncToOthers(event.spaceId, event.deletedByUserId, SyncResource.MEMO, event.memoId, SyncAction.DELETED)
	}

	// ── 공간·커플 ────────────────────────────────────────

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun onSpaceUpdated(event: SpaceUpdated) {
		dispatcher.syncToOthers(event.spaceId, event.changedByUserId, SyncResource.SPACE, event.spaceId, SyncAction.UPDATED)
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun onCoupleLinked(event: CoupleLinked) {
		dispatcher.syncToUsers(listOf(event.creatorUserId, event.joinerUserId), SyncResource.SPACE, null, SyncAction.LINKED)
		event.notificationIds.values.forEach { dispatcher.dispatch(it) }
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun onCoupleUnlinked(event: CoupleUnlinked) {
		dispatcher.syncToUsers(listOf(event.partnerUserId), SyncResource.SPACE, event.spaceId, SyncAction.UNLINKED)
		dispatcher.dispatch(event.notificationId)
	}
}
