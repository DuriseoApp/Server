package com.duriseo.server.anniversary.application

import java.util.UUID

/**
 * 직접 기념일이 만들어졌다(`POST /anniversaries` 성공). 만든 트랜잭션 안에서 발행하고, 듣는 쪽은
 * `@TransactionalEventListener(AFTER_COMMIT)`로 받는다 (ARCHITECTURE 4장). 단위 4에는 듣는 쪽이 없고, 단위 6이 커밋 뒤에
 * 공간의 다른 구성원에게 동기화 신호 `ANNIVERSARY`/`CREATED`를 보낸다 (API.md 6장). 일정의 `EventCreated`와 같은 방식이다.
 */
data class AnniversaryCreated(val spaceId: UUID, val anniversaryId: UUID, val createdByUserId: UUID)
