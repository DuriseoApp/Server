package com.duriseo.server.anniversary.application

import com.duriseo.server.anniversary.domain.AnniversaryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 다른 도메인이 직접 기념일을 공간 단위로 다룰 때 쓰는 문이다: 커플 연결의 "혼자 쓴 기록이 있는지"·`MeSpace.importCounts`·가져오기가
 * 단위 3까지는 테이블을 직접 읽었고, 단위 4부터 여기를 부른다 (ARCHITECTURE 2장 "다른 도메인은 서비스로만").
 * 직접 기념일 행만 다룬다: 자동 기념일은 저장하지 않으므로 세지도 옮기지도 않는다 (API.md 3.1 `ImportCounts`).
 * 기념일의 권한·값 규칙은 여기에 없다: 부른 쪽이 이미 대상을 정한 흐름이다. 부른 쪽의 트랜잭션 안에서 쓴다.
 */
@Service
class AnniversaryContentService(
	private val anniversaryRepository: AnniversaryRepository,
) {

	/** [spaceIds] 공간들의 직접 기념일 수 (혼자 쓴 기록이 있는지, 가져오기 대기 공간의 `importCounts`) */
	@Transactional(readOnly = true)
	fun countIn(spaceIds: Collection<UUID>): Int =
		if (spaceIds.isEmpty()) 0 else anniversaryRepository.countBySpaceIdIn(spaceIds).toInt()

	/** 가져오기: [fromSpaceId]의 직접 기념일을 [toSpaceId]로 옮긴다 (`space_id`만 바꾼다. 작성자·시각은 그대로, API.md 4.4 9단계). 옮긴 수를 돌려준다 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun moveAll(fromSpaceId: UUID, toSpaceId: UUID): Int = anniversaryRepository.moveSpace(fromSpaceId, toSpaceId)
}
