package com.duriseo.server.memo.api

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.ListResponse
import com.duriseo.server.common.web.AuthenticatedUser
import com.duriseo.server.common.web.CurrentUser
import com.duriseo.server.memo.application.MemoQueryService
import com.duriseo.server.memo.application.MemoService
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
 * 메모 API (API.md 3.4). 권한 검사(R-01·R-03·R-04·R-06)는 서비스가 `SpaceAccessPolicy`로 한다.
 * 쓰기 본문은 "필드 없음"과 `null`을 구분하고 R-01·R-03(404·403)이 값 검사(400)보다 먼저 나가야 해서 `Map`으로 받고 필수로 두지 않는다.
 * 쿼리(`view`·`cursor`·`size`·`from`·`to`)의 검사도 서비스가 한다: 없거나 틀려도 서비스가 400 `VALIDATION_FAILED`를 만들어야 해서 문자열 선택으로 받는다.
 */
@RestController
class MemoController(
	private val memoService: MemoService,
	private val queryService: MemoQueryService,
) {

	/** 고정되지 않은 메모 목록 (FR-MEMO-01) */
	@GetMapping("/memos")
	fun list(
		@CurrentUser requester: AuthenticatedUser,
		@RequestParam("view", required = false) view: String?,
		@RequestParam("cursor", required = false) cursor: String?,
		@RequestParam("size", required = false) size: String?,
	): MemoPageResponse = MemoPageResponse.from(queryService.list(requester.userId, view, cursor, size))

	/** 고정 메모 전체 (FR-MEMO-05) */
	@GetMapping("/memos/pinned")
	fun pinned(@CurrentUser requester: AuthenticatedUser): ListResponse<MemoResponse> =
		ListResponse(queryService.pinned(requester.userId).map { MemoResponse.from(it) })

	/** 캘린더용 날짜 범위의 메모 (FR-CAL-01 AC2) */
	@GetMapping("/memos/dated")
	fun dated(
		@CurrentUser requester: AuthenticatedUser,
		@RequestParam("from", required = false) from: String?,
		@RequestParam("to", required = false) to: String?,
	): ListResponse<MemoResponse> =
		ListResponse(queryService.dated(requester.userId, from, to).map { MemoResponse.from(it) })

	/** 메모 만들기 (FR-MEMO-02). 201과 `Memo` */
	@PostMapping("/memos")
	fun create(
		@CurrentUser requester: AuthenticatedUser,
		@RequestBody(required = false) body: Map<String, Any?>?,
	): ResponseEntity<MemoResponse> =
		ResponseEntity.status(HttpStatus.CREATED).body(MemoResponse.from(memoService.create(requester.userId, body ?: emptyMap())))

	/** 메모 상세 (FR-MEMO-03). 요청자 기준 `isFavorite` 포함 */
	@GetMapping("/memos/{id}")
	fun get(@CurrentUser requester: AuthenticatedUser, @PathVariable("id") id: String): MemoResponse =
		MemoResponse.from(queryService.get(requester.userId, memoIdOf(id)))

	/** 메모 수정 (FR-MEMO-03). 부분 수정, 200과 바뀐 `Memo`. 작성자만 (R-03) */
	@PatchMapping("/memos/{id}")
	fun update(
		@CurrentUser requester: AuthenticatedUser,
		@PathVariable("id") id: String,
		@RequestBody(required = false) body: Map<String, Any?>?,
	): MemoResponse = MemoResponse.from(memoService.update(requester.userId, memoIdOf(id), body ?: emptyMap()))

	/** 메모 삭제 (FR-MEMO-03). 204. 작성자만 (R-03) */
	@DeleteMapping("/memos/{id}")
	fun delete(@CurrentUser requester: AuthenticatedUser, @PathVariable("id") id: String): ResponseEntity<Void> {
		memoService.delete(requester.userId, memoIdOf(id))
		return ResponseEntity.noContent().build()
	}

	/** 고정 (FR-MEMO-05, R-04). 204, 멱등 */
	@PutMapping("/memos/{id}/pin")
	fun pin(@CurrentUser requester: AuthenticatedUser, @PathVariable("id") id: String): ResponseEntity<Void> {
		memoService.pin(requester.userId, memoIdOf(id))
		return ResponseEntity.noContent().build()
	}

	/** 고정 해제 (FR-MEMO-05, R-04). 204, 멱등 */
	@DeleteMapping("/memos/{id}/pin")
	fun unpin(@CurrentUser requester: AuthenticatedUser, @PathVariable("id") id: String): ResponseEntity<Void> {
		memoService.unpin(requester.userId, memoIdOf(id))
		return ResponseEntity.noContent().build()
	}

	/** 즐겨찾기 (FR-MEMO-06, R-06). 204, 멱등 */
	@PutMapping("/memos/{id}/favorite")
	fun favorite(@CurrentUser requester: AuthenticatedUser, @PathVariable("id") id: String): ResponseEntity<Void> {
		memoService.setFavorite(requester.userId, memoIdOf(id), true)
		return ResponseEntity.noContent().build()
	}

	/** 즐겨찾기 해제 (FR-MEMO-06, R-06). 204, 멱등 */
	@DeleteMapping("/memos/{id}/favorite")
	fun unfavorite(@CurrentUser requester: AuthenticatedUser, @PathVariable("id") id: String): ResponseEntity<Void> {
		memoService.setFavorite(requester.userId, memoIdOf(id), false)
		return ResponseEntity.noContent().build()
	}

	/** UUID 형식이 아닌 ID는 없는 메모와 같은 404다. 존재 여부를 드러내지 않는다 (DB-05) */
	private fun memoIdOf(raw: String): UUID =
		try {
			UUID.fromString(raw)
		} catch (e: IllegalArgumentException) {
			throw ApiException(ErrorCode.NOT_FOUND, "not found")
		}
}
