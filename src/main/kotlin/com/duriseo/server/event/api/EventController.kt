package com.duriseo.server.event.api

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.ListResponse
import com.duriseo.server.common.web.AuthenticatedUser
import com.duriseo.server.common.web.CurrentUser
import com.duriseo.server.event.application.EventReminderService
import com.duriseo.server.event.application.EventService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 일정 API (API.md 3.3). 권한 검사(R-01·R-02·R-10·R-13)는 서비스가 `SpaceAccessPolicy`로 한다.
 * 쓰기 본문은 "필드 없음"과 `null`을 구분하고 R-01·R-02·R-13·R-10(404·403·400 `INVALID_TARGET`)이 값 검사(400)보다 먼저 나가야 해서
 * `Map`으로 받고 필수로 두지 않는다. 일정 알림(`PUT`·`DELETE /events/{id}/reminder`)은 요청자 자신의 알림만 다룬다 (R-07).
 */
@RestController
class EventController(
	private val eventService: EventService,
	private val reminderService: EventReminderService,
) {

	/** 날짜 범위의 일정 (FR-CAL-01 AC2). 쿼리 검사는 서비스가 한다: 없어도(400 `REQUIRED`) 서비스가 응답해야 해서 선택으로 받는다 */
	@GetMapping("/events")
	fun list(
		@CurrentUser requester: AuthenticatedUser,
		@RequestParam("from", required = false) from: String?,
		@RequestParam("to", required = false) to: String?,
	): ListResponse<EventResponse> =
		ListResponse(eventService.list(requester.userId, from, to).map { EventResponse.from(it) })

	/** 일정 만들기 (FR-CAL-02). 201과 `Event` (`reminder`는 늘 null) */
	@PostMapping("/events")
	fun create(
		@CurrentUser requester: AuthenticatedUser,
		@RequestBody(required = false) body: Map<String, Any?>?,
	): ResponseEntity<EventResponse> =
		ResponseEntity.status(HttpStatus.CREATED)
			.body(EventResponse.from(eventService.create(requester.userId, body ?: emptyMap())))

	/** 일정 상세 (R-01, R-07) */
	@GetMapping("/events/{id}")
	fun get(@CurrentUser requester: AuthenticatedUser, @PathVariable("id") id: String): EventResponse =
		EventResponse.from(eventService.get(requester.userId, eventIdOf(id)))

	/** 일정 수정 (FR-CAL-03). 부분 수정, 200과 바뀐 `Event` */
	@PatchMapping("/events/{id}")
	fun update(
		@CurrentUser requester: AuthenticatedUser,
		@PathVariable("id") id: String,
		@RequestBody(required = false) body: Map<String, Any?>?,
	): EventResponse =
		EventResponse.from(eventService.update(requester.userId, eventIdOf(id), body ?: emptyMap()))

	/** 일정 삭제 (FR-CAL-03). 204 */
	@DeleteMapping("/events/{id}")
	fun delete(@CurrentUser requester: AuthenticatedUser, @PathVariable("id") id: String): ResponseEntity<Void> {
		eventService.delete(requester.userId, eventIdOf(id))
		return ResponseEntity.noContent().build()
	}

	/** 내 알림 설정 (FR-CAL-05). 200과 바뀐 `reminder`가 든 `Event`. 같은 요청을 다시 보내도 같은 결과다 */
	@PutMapping("/events/{id}/reminder")
	fun putReminder(
		@CurrentUser requester: AuthenticatedUser,
		@PathVariable("id") id: String,
		@RequestBody(required = false) body: Map<String, Any?>?,
	): EventResponse =
		EventResponse.from(reminderService.put(requester.userId, eventIdOf(id), body ?: emptyMap()))

	/** 내 알림 끄기 (FR-CAL-05). 알림이 없어도 204 */
	@DeleteMapping("/events/{id}/reminder")
	fun deleteReminder(@CurrentUser requester: AuthenticatedUser, @PathVariable("id") id: String): ResponseEntity<Void> {
		reminderService.delete(requester.userId, eventIdOf(id))
		return ResponseEntity.noContent().build()
	}

	/** UUID 형식이 아닌 ID는 없는 일정과 같은 404다. 존재 여부를 드러내지 않는다 (DB-05) */
	private fun eventIdOf(raw: String): UUID =
		try {
			UUID.fromString(raw)
		} catch (e: IllegalArgumentException) {
			throw ApiException(ErrorCode.NOT_FOUND, "not found")
		}
}
