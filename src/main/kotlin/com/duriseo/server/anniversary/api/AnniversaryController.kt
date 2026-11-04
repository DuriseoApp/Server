package com.duriseo.server.anniversary.api

import com.duriseo.server.anniversary.application.AnniversaryService
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.web.AuthenticatedUser
import com.duriseo.server.common.web.CurrentUser
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 직접 기념일 API (API.md 3.3 기념일). 권한 검사(R-01·R-05)는 서비스가 `SpaceAccessPolicy`로 한다.
 *
 * 지금은 `DELETE /anniversaries/{id}`만 있다. `POST`·`PATCH`(요청·응답 모양)와 `GET /anniversaries`, `/upcoming`, `/past`
 * (캘린더용 항목 모양, 커서·`size`)는 PRD v0.10 반영 뒤에 더한다 (unit-4.md 확인 필요 1·2·4).
 */
@RestController
class AnniversaryController(
	private val anniversaryService: AnniversaryService,
) {

	/** 직접 기념일 삭제 (FR-ANNIV-03 AC2). 204. 작성자만 (R-05) */
	@DeleteMapping("/anniversaries/{id}")
	fun delete(@CurrentUser requester: AuthenticatedUser, @PathVariable("id") id: String): ResponseEntity<Void> {
		anniversaryService.delete(requester.userId, anniversaryIdOf(id))
		return ResponseEntity.noContent().build()
	}

	/** UUID 형식이 아닌 ID는 없는 기념일과 같은 404다. 존재 여부를 드러내지 않는다 (DB-05) */
	private fun anniversaryIdOf(raw: String): UUID =
		try {
			UUID.fromString(raw)
		} catch (e: IllegalArgumentException) {
			throw ApiException(ErrorCode.NOT_FOUND, "not found")
		}
}
