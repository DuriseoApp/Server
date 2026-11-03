package com.duriseo.server.event.application

import java.util.UUID

/**
 * 일정이 만들어졌다. 만든 트랜잭션 안에서 발행하고, 듣는 쪽은 `@TransactionalEventListener(AFTER_COMMIT)`로 받는다 (ARCHITECTURE 4장).
 * 단위 3에는 듣는 쪽이 없고, 단위 6이 커밋 뒤에 공간의 다른 구성원에게 동기화 신호 `EVENT`/`CREATED`를, 상대 알림이 생겼으면
 * 상대에게 `PARTNER_CREATED` 푸시(설정과 유효 세션을 본다)와 `NOTIFICATION` 신호를 보낸다 (API.md 6장, 7장).
 *
 * - [createdByUserId]: 일정을 만든 사람
 * - [partnerUserId], [notificationId]: 상대에게 `PARTNER_CREATED` 알림 행을 만들었으면 그 상대와 알림 ID, 아니면 둘 다 null
 *   (개인 공간이거나 `notifyPartner = false`)
 */
data class EventCreated(
	val spaceId: UUID,
	val eventId: UUID,
	val createdByUserId: UUID,
	val partnerUserId: UUID?,
	val notificationId: UUID?,
)
