package com.duriseo.server.notification.api

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.web.AuthenticatedUser
import com.duriseo.server.common.web.CurrentUser
import com.duriseo.server.notification.application.NotificationQueryService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 알림 목록 API (API.md 3.5). 본인 것만 보인다 (R-12, 서비스가 `SpaceAccessPolicy`로 검사한다). `cursor`·`size`는 서비스가 검사해 문자열로 받는다 */
@RestController
class NotificationController(private val queryService: NotificationQueryService) {

	@GetMapping("/notifications")
	fun list(
		@CurrentUser requester: AuthenticatedUser,
		@RequestParam("cursor", required = false) cursor: String?,
		@RequestParam("size", required = false) size: String?,
	): NotificationPageResponse = NotificationPageResponse.from(queryService.list(requester.userId, cursor, size))

	@GetMapping("/notifications/unread-count")
	fun unreadCount(@CurrentUser requester: AuthenticatedUser): UnreadCountResponse =
		UnreadCountResponse(queryService.unreadCount(requester.userId))

	/** 읽음 처리. 204, 멱등 (D-142) */
	@PutMapping("/notifications/{id}/read")
	fun read(@CurrentUser requester: AuthenticatedUser, @PathVariable("id") id: String): ResponseEntity<Void> {
		queryService.markRead(requester.userId, notificationIdOf(id))
		return ResponseEntity.noContent().build()
	}

	/** UUID 형식이 아닌 ID는 없는 알림과 같은 404다 (R-12) */
	private fun notificationIdOf(raw: String): UUID =
		try {
			UUID.fromString(raw)
		} catch (e: IllegalArgumentException) {
			throw ApiException(ErrorCode.NOT_FOUND, "not found")
		}
}
