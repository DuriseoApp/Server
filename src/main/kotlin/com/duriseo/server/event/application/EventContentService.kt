package com.duriseo.server.event.application

import com.duriseo.server.event.domain.EventReminderRepository
import com.duriseo.server.event.domain.EventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 다른 도메인이 일정과 일정 알림을 공간·사용자 단위로 다룰 때 쓰는 문이다: 커플 연결의 "혼자 쓴 기록이 있는지"·가져오기·해제와
 * 탈퇴 익명화가 단위 2에서는 테이블을 직접 읽고 지웠고, 단위 3부터 여기를 부른다
 * (ARCHITECTURE 2장 "다른 도메인은 서비스로만"). 일정의 권한·값 규칙은 여기에 없다: 부른 쪽이 이미 대상을 정한 흐름이다.
 * 부른 쪽의 트랜잭션 안에서 쓴다.
 */
@Service
class EventContentService(
	private val eventRepository: EventRepository,
	private val reminderRepository: EventReminderRepository,
) {

	/** [spaceIds] 공간들의 일정 수 (혼자 쓴 기록이 있는지, 가져오기 대기 공간의 `importCounts`) */
	@Transactional(readOnly = true)
	fun countIn(spaceIds: Collection<UUID>): Int =
		if (spaceIds.isEmpty()) 0 else eventRepository.countBySpaceIdIn(spaceIds).toInt()

	/** 가져오기: [fromSpaceId]의 일정을 [toSpaceId]로 옮긴다 (`space_id`만 바꾼다. 작성자·시각·알림은 그대로, API.md 4.4 9단계). 옮긴 수를 돌려준다 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun moveAll(fromSpaceId: UUID, toSpaceId: UUID): Int = eventRepository.moveSpace(fromSpaceId, toSpaceId)

	/** 연결 해제: 그 공간 일정의 일정 알림을 모두 지운다 (API.md 4.5 3단계: 더 이상 볼 수 없는 일정의 알림이 가지 않도록) */
	@Transactional(propagation = Propagation.MANDATORY)
	fun deleteRemindersOfSpace(spaceId: UUID): Int = reminderRepository.deleteOfSpace(spaceId)

	/** 탈퇴 익명화: 사용자가 건 일정 알림을 모두 지운다 (ERD 4.3 4단계) */
	@Transactional(propagation = Propagation.MANDATORY)
	fun deleteRemindersOfUser(userId: UUID): Int = reminderRepository.deleteOfUser(userId)
}
