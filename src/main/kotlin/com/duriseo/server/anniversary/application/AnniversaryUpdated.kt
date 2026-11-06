package com.duriseo.server.anniversary.application

import java.util.UUID

/**
 * 직접 기념일이 수정됐다(`PATCH /anniversaries/{id}`로 값이 바뀜). 수정한 트랜잭션 안에서 발행한다. 단위 4에는 듣는 쪽이 없고,
 * 단위 6이 커밋 뒤에 공간의 다른 구성원에게 동기화 신호 `ANNIVERSARY`/`UPDATED`를 보낸다 (API.md 6장). 일정의 `EventUpdated`와 같은 방식이다.
 */
data class AnniversaryUpdated(val spaceId: UUID, val anniversaryId: UUID, val changedByUserId: UUID)
