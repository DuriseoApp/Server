package com.duriseo.server.couple.application

import java.util.UUID

/**
 * 커플 연결이 해제됐다(`DELETE /couple` 또는 탈퇴로 인한 해제). 해제 트랜잭션 안에서 발행하고, 듣는 쪽은
 * `@TransactionalEventListener(AFTER_COMMIT)`로 받는다 (ARCHITECTURE 4장). 단위 2에는 듣는 쪽이 없고, 단위 6이 커밋 뒤에
 * 상대 기기에 해제 푸시와 동기화 신호 `SPACE`/`UNLINKED`(앱 초기화 신호), 알림 행이 생긴 상대 기기에 `NOTIFICATION`을 보낸다
 * (API.md 4.5 5단계, 6장, 7장).
 *
 * - [unlinkedByUserId]: 해제한 사람(탈퇴로 인한 해제면 탈퇴하는 사람), [partnerUserId]: 알림을 받는 상대
 * - [byWithdrawal]: 탈퇴로 인한 해제면 true (Kakao 연결 해제 알림으로 인한 탈퇴 포함). 탈퇴 해제는 `UserWithdrawn`도 같은 상대를
 *   `partnerUserId`로 담아 발행한다. 단위 6은 둘 중 한쪽에서만 보내 같은 상대에게 신호가 두 번 가지 않게 한다.
 * - [notificationId]: 상대에게 만든 `COUPLE_UNLINKED` 알림 ID
 */
data class CoupleUnlinked(
	val spaceId: UUID,
	val unlinkedByUserId: UUID,
	val partnerUserId: UUID,
	val byWithdrawal: Boolean,
	val notificationId: UUID,
)
