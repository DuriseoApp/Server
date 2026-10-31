package com.duriseo.server.couple.application

import com.duriseo.server.common.api.ApiException
import java.util.UUID

/** API.md 3.2 `CoupleJoinResult`의 내용 */
data class CoupleJoinView(val spaceId: UUID, val restored: Boolean, val importPending: Boolean)

/**
 * 연결 시도의 결과. 거부 중 "실패로 세는" 것(API.md 4.4 2단계 표의 5~10번)은 예외를 던지지 않고 [Rejected]로 돌려준다.
 * 그래야 시도 기록(`succeeded = false`)이 같은 트랜잭션에서 커밋되고, 바깥 서비스가 커밋 뒤에 오류를 던진다.
 */
sealed interface JoinOutcome {
	data class Linked(val view: CoupleJoinView) : JoinOutcome
	data class Rejected(val error: ApiException) : JoinOutcome
}
