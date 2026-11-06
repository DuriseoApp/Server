package com.duriseo.server.anniversary.api

import com.duriseo.server.anniversary.application.AnniversaryQueryService
import com.duriseo.server.anniversary.application.AnniversaryService
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.ListResponse
import com.duriseo.server.common.api.PageResponse
import com.duriseo.server.common.web.AuthenticatedUser
import com.duriseo.server.common.web.CurrentUser
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 기념일 API (API.md 3.3 기념일). 권한 검사(R-01·R-05)는 서비스가 `SpaceAccessPolicy`로 한다.
 * 쓰기 본문은 "필드 없음"과 `null`을 구분하고 R-01·R-05(404·403)가 값 검사(400)보다 먼저 나가야 해서 `Map`으로 받고 필수로 두지 않는다.
 * 쿼리(`from`·`to`·`cursor`·`size`)의 검사도 서비스가 한다: 없거나 틀려도 서비스가 400 `VALIDATION_FAILED`를 만들어야 해서 문자열 선택으로 받는다.
 */
@RestController
class AnniversaryController(
	private val anniversaryService: AnniversaryService,
	private val queryService: AnniversaryQueryService,
) {

	/** 캘린더용 날짜 범위의 자동 + 직접 기념일 (FR-CAL-01, FR-ANNIV-04). 페이지 없음 */
	@GetMapping("/anniversaries")
	fun list(
		@CurrentUser requester: AuthenticatedUser,
		@RequestParam("from", required = false) from: String?,
		@RequestParam("to", required = false) to: String?,
	): ListResponse<AnniversaryOccurrenceResponse> =
		ListResponse(queryService.listBetween(requester.userId, from, to).map { AnniversaryOccurrenceResponse.from(it) })

	/** 기념일 목록(SCR-ANNIV-01)용 다가오는 기념일 (FR-ANNIV-04) */
	@GetMapping("/anniversaries/upcoming")
	fun upcoming(
		@CurrentUser requester: AuthenticatedUser,
		@RequestParam("cursor", required = false) cursor: String?,
		@RequestParam("size", required = false) size: String?,
	): PageResponse<AnniversaryOccurrenceResponse> {
		val page = queryService.upcomingPage(requester.userId, cursor, size)
		return PageResponse(page.items.map { AnniversaryOccurrenceResponse.from(it) }, page.nextCursor)
	}

	/** "지난 기념일" (FR-ANNIV-04 AC3, D-71) */
	@GetMapping("/anniversaries/past")
	fun past(
		@CurrentUser requester: AuthenticatedUser,
		@RequestParam("cursor", required = false) cursor: String?,
		@RequestParam("size", required = false) size: String?,
	): PastPageResponse = PastPageResponse.from(queryService.past(requester.userId, cursor, size))

	/** 직접 기념일 만들기 (FR-ANNIV-03 AC1). 201과 `Anniversary` */
	@PostMapping("/anniversaries")
	fun create(
		@CurrentUser requester: AuthenticatedUser,
		@RequestBody(required = false) body: Map<String, Any?>?,
	): ResponseEntity<AnniversaryResponse> =
		ResponseEntity.status(HttpStatus.CREATED)
			.body(AnniversaryResponse.from(anniversaryService.create(requester.userId, body ?: emptyMap())))

	/** 직접 기념일 수정 (FR-ANNIV-03). 부분 수정, 200과 바뀐 `Anniversary`. 작성자만 (R-05) */
	@PatchMapping("/anniversaries/{id}")
	fun update(
		@CurrentUser requester: AuthenticatedUser,
		@PathVariable("id") id: String,
		@RequestBody(required = false) body: Map<String, Any?>?,
	): AnniversaryResponse =
		AnniversaryResponse.from(anniversaryService.update(requester.userId, anniversaryIdOf(id), body ?: emptyMap()))

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
