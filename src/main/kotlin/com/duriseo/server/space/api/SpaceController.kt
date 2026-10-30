package com.duriseo.server.space.api

import com.duriseo.server.common.web.AuthenticatedUser
import com.duriseo.server.common.web.CurrentUser
import com.duriseo.server.space.application.SpaceDetailService
import com.duriseo.server.space.application.SpaceSettingsService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * 공간 API (API.md 3.2). 경로에 공간 ID가 없고 요청자의 활성 공간이 대상이다 (R-01).
 * 변경 경로의 본문은 "필드 없음"과 `null`을 구분하려고 `Map`으로 받고, 본문이 비어도 R-08(409)이 먼저 나가도록 필수로 두지 않는다.
 */
@RestController
class SpaceController(
	private val detailService: SpaceDetailService,
	private val settingsService: SpaceSettingsService,
) {

	/** 현재 공간 상세 (FR-SET-01). 게스트도 부를 수 있다 */
	@GetMapping("/space")
	fun get(@CurrentUser requester: AuthenticatedUser): SpaceResponse =
		SpaceResponse.from(detailService.get(requester.userId))

	/** 처음 만난 날 변경 (R-08, R-15, D-84). 커플 공간의 구성원만 */
	@PatchMapping("/space")
	fun updateStartDate(
		@CurrentUser requester: AuthenticatedUser,
		@RequestBody(required = false) body: Map<String, Any?>?,
	): SpaceResponse =
		SpaceResponse.from(settingsService.updateStartDate(requester.userId, body?.get("startDate")))

	/** 사람 색·'함께' 색 변경 (R-08, FR-CAL-04). 커플 공간의 구성원, 개인 공간에서는 자기 색만 */
	@PatchMapping("/space/colors")
	fun updateColors(
		@CurrentUser requester: AuthenticatedUser,
		@RequestBody(required = false) body: Map<String, Any?>?,
	): SpaceResponse =
		SpaceResponse.from(settingsService.updateColors(requester.userId, UpdateColorsRequest.parse(body ?: emptyMap())))
}
