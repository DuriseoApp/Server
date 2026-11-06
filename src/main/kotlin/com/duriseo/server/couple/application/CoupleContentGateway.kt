package com.duriseo.server.couple.application

import com.duriseo.server.anniversary.application.AnniversaryContentService
import com.duriseo.server.event.application.EventContentService
import com.duriseo.server.space.application.ImportCounts
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 공간의 기록(`events`, `anniversaries`, `memos`)을 다룬다: 혼자 쓴 기록이 있는지 보고(API.md 4.4 5단계, ERD 4.1),
 * 가져오기 때 커플 공간으로 옮기고(4.4 9단계), 해제 때 일정 알림을 지운다(4.5 3단계).
 * 일정은 단위 3부터 `EventContentService`에, 직접 기념일은 단위 4부터 `AnniversaryContentService`에 맡긴다.
 * 메모 엔티티는 단위 5에서 생기므로 그때까지 테이블을 직접 읽고, 단위 5가 도메인 서비스로 옮기면 이 클래스는 없어진다
 * (ARCHITECTURE 2장의 "다른 도메인은 서비스로만"에 대한 임시 예외). 부른 쪽의 트랜잭션 안에서 읽는다.
 */
@Component
class CoupleContentGateway(
	private val em: EntityManager,
	private val eventContent: EventContentService,
	private val anniversaryContent: AnniversaryContentService,
) {

	fun hasContent(spaceId: UUID): Boolean {
		if (eventContent.countIn(listOf(spaceId)) > 0) return true
		if (anniversaryContent.countIn(listOf(spaceId)) > 0) return true
		val total = em.createNativeQuery("select count(*) from memos where space_id = :spaceId")
			.setParameter("spaceId", spaceId)
			.singleResult as Number
		return total.toLong() > 0
	}

	/**
	 * 가져오기 대기 중인 개인 공간 [spaceIds]의 `events`, `anniversaries`, `memos` 행 수 (`MeSpace.importCounts`, API.md 3.1, D-93).
	 * 요청 때 세고 저장하지 않는다. 일정·직접 기념일은 각 서비스가 세고(자동 기념일은 저장하지 않으므로 세지 않는다), 메모는 단위 5에서 옮긴다.
	 */
	fun countsOf(spaceIds: Collection<UUID>): ImportCounts {
		if (spaceIds.isEmpty()) return ImportCounts(0, 0, 0)
		val memos = em.createNativeQuery("select count(*) from memos where space_id in (:spaceIds)")
			.setParameter("spaceIds", spaceIds.toList())
			.singleResult as Number
		return ImportCounts(eventContent.countIn(spaceIds), anniversaryContent.countIn(spaceIds), memos.toInt())
	}

	/**
	 * `import: true`: [fromSpaceId]의 `events`, `anniversaries`, `memos`를 [toSpaceId]로 옮긴다 (`space_id`만 바꾼다. 작성자·시각은 그대로).
	 * 옮긴 줄 수를 돌려준다 (API.md 4.4 9단계, FR-COUPLE-03.AC1).
	 */
	fun moveAll(fromSpaceId: UUID, toSpaceId: UUID): Int =
		eventContent.moveAll(fromSpaceId, toSpaceId) +
			anniversaryContent.moveAll(fromSpaceId, toSpaceId) +
			em.createNativeQuery("update memos set space_id = :toSpaceId where space_id = :fromSpaceId")
				.setParameter("toSpaceId", toSpaceId)
				.setParameter("fromSpaceId", fromSpaceId)
				.executeUpdate()

	/** 그 공간 일정의 `event_reminders`를 모두 지운다 (연결 해제, API.md 4.5 3단계: 더 이상 볼 수 없는 일정의 알림이 가지 않도록) */
	fun deleteEventReminders(spaceId: UUID): Int = eventContent.deleteRemindersOfSpace(spaceId)
}
