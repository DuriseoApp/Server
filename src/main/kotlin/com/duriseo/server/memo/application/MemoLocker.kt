package com.duriseo.server.memo.application

import com.duriseo.server.common.access.SpaceAccessPolicy
import com.duriseo.server.memo.domain.Memo
import com.duriseo.server.memo.domain.MemoRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 요청자의 활성 공간 메모 하나를 잠그고 읽는다 (R-01). `PATCH`·`DELETE /memos/{id}`, 고정·해제, 즐겨찾기가 쓴다.
 * 메모 행만 `FOR UPDATE`로 잠그고 공간 행은 잠그지 않는다 (`AnniversaryLocker`와 같다). 같은 메모의 동시 수정·삭제·고정을 한 줄로 세우고,
 * 가져오기가 `space_id`를 옮기는 중의 요청도 잠근 값으로 R-01을 다시 확인한다.
 */
@Component
class MemoLocker(
	private val policy: SpaceAccessPolicy,
	private val memoRepository: MemoRepository,
) {

	/**
	 * R-01(없거나 다른 공간이면 404) → 그 메모 행을 `FOR UPDATE`로 잠금 → 잠근 값으로 R-01을 다시 확인한다.
	 * 처음 R-01은 엔티티를 읽지 않고 공간 ID만 읽어, 다른 공간의 메모 행을 잠그지 않는다. R-03은 부른 쪽이 본다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun lockInActiveSpace(requesterId: UUID, memoId: UUID): Memo {
		policy.requireInActiveSpace(requesterId, memoRepository.findSpaceIdById(memoId))
		val memo = memoRepository.findByIdForUpdate(memoId)
		policy.requireInActiveSpace(requesterId, memo?.spaceId)
		return checkNotNull(memo)
	}
}
