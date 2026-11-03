package com.duriseo.server.event.application

import com.duriseo.server.common.access.SpaceAccessPolicy
import com.duriseo.server.event.domain.Event
import com.duriseo.server.event.domain.EventRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 요청자의 활성 공간 일정 하나를 잠그고 읽는다 (R-01). `PATCH`·`DELETE /events/{id}`와 `PUT`·`DELETE /events/{id}/reminder`가 쓴다.
 * 일정 행만 `FOR UPDATE`로 잠그고 공간·사용자 행은 잠그지 않는다 (`EventService`의 "잠금"). 같은 일정을 고치는 요청끼리,
 * 그리고 일정 시작이 바뀌며 알림을 다시 계산하는 요청과 알림을 정하는 요청이 한 줄로 서게 한다.
 */
@Component
class EventLocker(
	private val policy: SpaceAccessPolicy,
	private val eventRepository: EventRepository,
) {

	/**
	 * R-01(없거나 다른 공간이면 404) → 그 일정 행을 `FOR UPDATE`로 잠금 → 잠근 값으로 R-01을 다시 확인한다.
	 * 처음 R-01은 일정 엔티티를 읽지 않고 공간 ID만 읽어, 다른 공간의 일정 행을 잠그지 않는다. R-02 등 그 밖의 규칙은 부른 쪽이 본다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun lockInActiveSpace(requesterId: UUID, eventId: UUID): Event {
		policy.requireInActiveSpace(requesterId, eventRepository.findSpaceIdById(eventId))
		val event = eventRepository.findByIdForUpdate(eventId)
		policy.requireInActiveSpace(requesterId, event?.spaceId)
		return checkNotNull(event)
	}
}
