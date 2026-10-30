package com.duriseo.server.couple.application

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 공간에 혼자 쓴 기록(`events`, `anniversaries`, `memos`)이 있는지 본다 (API.md 4.4 5단계, ERD 4.1).
 * 일정·기념일·메모 엔티티는 단위 3·4·5에서 생기므로 그때까지 테이블을 직접 읽는다. 각 단위가 도메인 서비스로 옮긴다
 * (ARCHITECTURE 2장의 "다른 도메인은 서비스로만"에 대한 임시 예외). 부른 쪽의 트랜잭션 안에서 읽는다.
 */
@Component
class CoupleContentGateway(private val em: EntityManager) {

	fun hasContent(spaceId: UUID): Boolean {
		val total = em.createNativeQuery(
			"select (select count(*) from events where space_id = :spaceId) " +
				"+ (select count(*) from anniversaries where space_id = :spaceId) " +
				"+ (select count(*) from memos where space_id = :spaceId)",
		)
			.setParameter("spaceId", spaceId)
			.singleResult as Number
		return total.toLong() > 0
	}
}
