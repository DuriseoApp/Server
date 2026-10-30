package com.duriseo.server.couple.application

import java.util.UUID

/**
 * 두 사람이 커플로 연결됐다(복구 포함). 연결 트랜잭션 안에서 발행하고, 듣는 쪽은 `@TransactionalEventListener(AFTER_COMMIT)`로 받는다
 * (ARCHITECTURE 4장). 단위 2에는 듣는 쪽이 없고, 단위 6이 커밋 뒤에 두 사람에게 연결 푸시와 동기화 신호
 * `SPACE`/`LINKED`(`id` 없음), 알림 행이 생긴 본인 기기에 `NOTIFICATION`을 보낸다 (API.md 4.4 6~7단계, 6장, 7장).
 *
 * - [creatorUserId]: 코드를 만든 사람 A, [joinerUserId]: 코드를 입력한 사람 B
 * - [notificationIds]: 사용자 ID → 그 사용자에게 만든 `COUPLE_LINKED` 알림 ID
 */
data class CoupleLinked(
	val spaceId: UUID,
	val creatorUserId: UUID,
	val joinerUserId: UUID,
	val restored: Boolean,
	val notificationIds: Map<UUID, UUID>,
)
