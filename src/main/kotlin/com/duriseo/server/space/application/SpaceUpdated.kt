package com.duriseo.server.space.application

import java.util.UUID

/**
 * 공간의 처음 만난 날이나 사람 색이 바뀌었다. 바꾼 트랜잭션 안에서 발행한다.
 * 단위 2에는 듣는 쪽이 없고, 단위 6이 커밋 뒤 다른 구성원에게 동기화 신호 `SPACE`/`UPDATED`를 보낸다 (API.md 6장).
 */
data class SpaceUpdated(val spaceId: UUID, val changedByUserId: UUID)
