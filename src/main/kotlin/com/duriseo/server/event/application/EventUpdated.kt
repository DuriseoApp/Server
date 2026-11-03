package com.duriseo.server.event.application

import java.util.UUID

/**
 * 일정이 수정됐다(`PATCH /events/{id}` 성공). 수정한 트랜잭션 안에서 발행한다. 단위 3에는 듣는 쪽이 없고, 단위 6이 커밋 뒤에
 * 공간의 다른 구성원에게 동기화 신호 `EVENT`/`UPDATED`를 보낸다 (API.md 6장). 수정은 상대에게 알림을 만들지 않는다.
 * 다른 사용자의 일정 알림이 지워지거나 다시 계산돼도 신호는 이것 하나다. `PUT`·`DELETE /events/{id}/reminder`는 발행하지 않는다.
 */
data class EventUpdated(val spaceId: UUID, val eventId: UUID, val changedByUserId: UUID)
