package com.duriseo.server.couple.application

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 공간의 기록(`events`, `anniversaries`, `memos`)을 다룬다: 혼자 쓴 기록이 있는지 보고(API.md 4.4 5단계, ERD 4.1),
 * 가져오기 때 커플 공간으로 옮기고(4.4 9단계), 해제 때 일정 알림을 지운다(4.5 3단계).
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

	/**
	 * `import: true`: [fromSpaceId]의 `events`, `anniversaries`, `memos`를 [toSpaceId]로 옮긴다 (`space_id`만 바꾼다. 작성자·시각은 그대로).
	 * 옮긴 줄 수를 돌려준다 (API.md 4.4 9단계, FR-COUPLE-03.AC1).
	 */
	fun moveAll(fromSpaceId: UUID, toSpaceId: UUID): Int =
		listOf("events", "anniversaries", "memos").sumOf { table ->
			em.createNativeQuery("update $table set space_id = :toSpaceId where space_id = :fromSpaceId")
				.setParameter("toSpaceId", toSpaceId)
				.setParameter("fromSpaceId", fromSpaceId)
				.executeUpdate()
		}

	/** 그 공간 일정의 `event_reminders`를 모두 지운다 (연결 해제, API.md 4.5 3단계: 더 이상 볼 수 없는 일정의 알림이 가지 않도록) */
	fun deleteEventReminders(spaceId: UUID): Int =
		em.createNativeQuery("delete from event_reminders where event_id in (select id from events where space_id = :spaceId)")
			.setParameter("spaceId", spaceId)
			.executeUpdate()
}
