package com.duriseo.server.anniversary.application

import java.util.UUID

/**
 * 직접 기념일이 삭제됐다(`DELETE /anniversaries/{id}` 성공). 삭제한 트랜잭션 안에서 발행한다. 단위 4에는 듣는 쪽이 없고, 단위 6이 커밋 뒤에
 * 공간의 다른 구성원에게 동기화 신호 `ANNIVERSARY`/`DELETED`를 보낸다 (API.md 6장). 일정의 `EventDeleted`와 같은 방식이다.
 */
data class AnniversaryDeleted(val spaceId: UUID, val anniversaryId: UUID, val deletedByUserId: UUID)
