package com.duriseo.server.couple.application

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 초대코드로 연결하는 입구 (`POST /couple/join`, FR-COUPLE-02). 트랜잭션은 [CoupleLinker]가 열고 이 클래스는 열지 않는다:
 * 실패 시도를 기록한 트랜잭션이 커밋된 뒤에 오류를 던지고, 트랜잭션 밖에서 제약 위반을 오류 코드로 바꾸기 위해서다.
 */
@Service
class CoupleJoinService(private val linker: CoupleLinker) {

	fun join(requesterId: UUID, rawCode: Any?): CoupleJoinView {
		val outcome = try {
			linker.attempt(requesterId, rawCode)
		} catch (e: DataIntegrityViolationException) {
			// 잠금이 막아 주는 경쟁이라 보통은 오지 않는다. "한 사람은 한 커플"을 DB가 마지막으로 지킨 경우다 (CONVENTIONS)
			if (e.violates(ACTIVE_MEMBER_UNIQUE)) throw ApiException(ErrorCode.ALREADY_COUPLED, "already in a couple space")
			throw e
		}
		return when (outcome) {
			is JoinOutcome.Linked -> outcome.view
			is JoinOutcome.Rejected -> throw outcome.error
		}
	}

	/** 제약 이름은 마이그레이션이 정한 것이라 바꾸지 않는다 */
	private fun Throwable.violates(constraint: String): Boolean =
		generateSequence(this) { it.cause?.takeIf { cause -> cause !== it } }
			.any { it.message?.contains(constraint) == true }

	private companion object {
		const val ACTIVE_MEMBER_UNIQUE = "uq_space_members_active_user"
	}
}
