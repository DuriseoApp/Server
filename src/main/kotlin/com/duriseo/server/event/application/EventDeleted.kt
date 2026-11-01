package com.duriseo.server.event.application

import java.util.UUID

/**
 * 일정이 삭제됐다(`DELETE /events/{id}` 성공). 삭제한 트랜잭션 안에서 발행한다. 단위 3에는 듣는 쪽이 없고, 단위 6이 커밋 뒤에
 * 공간의 다른 구성원에게 동기화 신호 `EVENT`/`DELETED`를 보낸다 (API.md 6장).
 */
data class EventDeleted(val spaceId: UUID, val eventId: UUID, val deletedByUserId: UUID)
