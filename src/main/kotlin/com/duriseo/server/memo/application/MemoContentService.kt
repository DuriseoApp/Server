package com.duriseo.server.memo.application

import com.duriseo.server.memo.domain.MemoFavoriteRepository
import com.duriseo.server.memo.domain.MemoRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 다른 도메인이 메모를 공간·사용자 단위로 다룰 때 쓰는 문이다: 커플 연결의 "혼자 쓴 기록이 있는지"·`MeSpace.importCounts`·가져오기와
 * 탈퇴 정리가 단위 4까지는 테이블을 직접 읽고 지웠고, 단위 5부터 여기를 부른다
 * (ARCHITECTURE 2장 "다른 도메인은 서비스로만"). 메모의 권한·값 규칙은 여기에 없다: 부른 쪽이 이미 대상을 정한 흐름이다.
 * 부른 쪽의 트랜잭션 안에서 쓴다.
 */
@Service
class MemoContentService(
	private val memoRepository: MemoRepository,
	private val favoriteRepository: MemoFavoriteRepository,
) {

	/** [spaceIds] 공간들의 메모 수 (혼자 쓴 기록이 있는지, 가져오기 대기 공간의 `importCounts`) */
	@Transactional(readOnly = true)
	fun countIn(spaceIds: Collection<UUID>): Int =
		if (spaceIds.isEmpty()) 0 else memoRepository.countBySpaceIdIn(spaceIds).toInt()

	/**
	 * 가져오기: [fromSpaceId]의 메모를 [toSpaceId]로 옮긴다 (`space_id`만 바꾼다. 작성자·시각·고정 상태·즐겨찾기는 그대로, API.md 4.4 9단계).
	 * 옮긴 수를 돌려준다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun moveAll(fromSpaceId: UUID, toSpaceId: UUID): Int = memoRepository.moveSpace(fromSpaceId, toSpaceId)

	/** 탈퇴하는 사용자의 즐겨찾기를 모두 지운다. 메모는 남는다 (ERD 4.3 4단계) */
	@Transactional(propagation = Propagation.MANDATORY)
	fun deleteFavoritesOf(userId: UUID): Int = favoriteRepository.deleteAllByUser(userId)
}
