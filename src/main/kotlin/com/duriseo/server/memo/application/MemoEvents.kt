package com.duriseo.server.memo.application

import java.util.UUID

/**
 * 메모가 만들어졌다(`POST /memos` 성공). 만든 트랜잭션 안에서 발행하고, 듣는 쪽은 `@TransactionalEventListener(AFTER_COMMIT)`로 받는다
 * (ARCHITECTURE 4장). 단위 5에는 듣는 쪽이 없고, 단위 6이 커밋 뒤에 공간의 다른 구성원에게 동기화 신호 `MEMO`/`CREATED`를, 상대 알림이
 * 생겼으면 상대에게 `PARTNER_CREATED` 푸시와 `NOTIFICATION` 신호를 보낸다 (API.md 6장, 7장). 일정의 `EventCreated`와 같은 방식이다.
 *
 * - [partnerUserId], [notificationId]: 상대에게 `PARTNER_CREATED` 알림 행을 만들었으면 그 상대와 알림 ID, 아니면 둘 다 null
 *   (개인 공간이거나 `notifyPartner = false`)
 */
data class MemoCreated(
	val spaceId: UUID,
	val memoId: UUID,
	val createdByUserId: UUID,
	val partnerUserId: UUID?,
	val notificationId: UUID?,
)

/**
 * 메모가 바뀌었다: 값이 바뀐 `PATCH /memos/{id}`, 실제로 전환된 고정·해제 (API.md 6장 `MEMO` `UPDATED`). 바꾼 트랜잭션 안에서 발행한다.
 * 저장값과 같은 값만 보낸 `PATCH`, 이미 그 상태인 고정·해제(멱등)는 발행하지 않는다 (D-123, D-134). 즐겨찾기는 이벤트가 없다.
 *
 * - [partnerUserId], [notificationId]: 미고정 → 고정 전환이 상대에게 `MEMO_PINNED` 알림 행을 만들었으면 그 상대와 알림 ID, 아니면 둘 다 null
 */
data class MemoUpdated(
	val spaceId: UUID,
	val memoId: UUID,
	val changedByUserId: UUID,
	val partnerUserId: UUID? = null,
	val notificationId: UUID? = null,
)

/** 메모가 삭제됐다(`DELETE /memos/{id}` 성공). 삭제한 트랜잭션 안에서 발행한다. 단위 6이 `MEMO`/`DELETED` 신호를 보낸다 (API.md 6장) */
data class MemoDeleted(val spaceId: UUID, val memoId: UUID, val deletedByUserId: UUID)
