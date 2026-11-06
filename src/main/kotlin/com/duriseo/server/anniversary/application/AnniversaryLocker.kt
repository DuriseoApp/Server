package com.duriseo.server.anniversary.application

import com.duriseo.server.anniversary.domain.Anniversary
import com.duriseo.server.anniversary.domain.AnniversaryRepository
import com.duriseo.server.common.access.SpaceAccessPolicy
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 요청자의 활성 공간 직접 기념일 하나를 잠그고 읽는다 (R-01). `PATCH`·`DELETE /anniversaries/{id}`가 쓴다.
 * 기념일 행만 `FOR UPDATE`로 잠그고 공간·사용자 행은 잠그지 않는다 (일정의 `EventLocker`와 같다).
 * 같은 기념일의 동시 수정·삭제를 한 줄로 세우고, 가져오기가 `space_id`를 옮기는 중의 요청도 잠근 값으로 R-01을 다시 확인한다.
 */
@Component
class AnniversaryLocker(
	private val policy: SpaceAccessPolicy,
	private val anniversaryRepository: AnniversaryRepository,
) {

	/**
	 * R-01(없거나 다른 공간이면 404) → 그 기념일 행을 `FOR UPDATE`로 잠금 → 잠근 값으로 R-01을 다시 확인한다.
	 * 처음 R-01은 엔티티를 읽지 않고 공간 ID만 읽어, 다른 공간의 기념일 행을 잠그지 않는다. R-05는 부른 쪽이 본다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun lockInActiveSpace(requesterId: UUID, anniversaryId: UUID): Anniversary {
		policy.requireInActiveSpace(requesterId, anniversaryRepository.findSpaceIdById(anniversaryId))
		val anniversary = anniversaryRepository.findByIdForUpdate(anniversaryId)
		policy.requireInActiveSpace(requesterId, anniversary?.spaceId)
		return checkNotNull(anniversary)
	}
}
