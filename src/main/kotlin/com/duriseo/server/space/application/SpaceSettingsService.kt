package com.duriseo.server.space.application

import com.duriseo.server.common.access.SpaceAccessPolicy
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.FieldError
import com.duriseo.server.common.api.FieldErrorDetails
import com.duriseo.server.common.api.FieldErrorReason
import com.duriseo.server.common.time.nowInSeconds
import com.duriseo.server.space.domain.PersonColors
import com.duriseo.server.space.domain.SpaceMemberRepository
import com.duriseo.server.space.domain.SpaceRepository
import com.duriseo.server.user.application.UserService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * 처음 만난 날(`PATCH /space`)과 사람 색(`PATCH /space/colors`) 변경 (FR-COUPLE-04, FR-CAL-04, API.md 3.2 "공간 변경 규칙").
 * 두 사람이 동시에 바꿀 수 있어 활성 공간 행을 잠근다. 둘의 동시 변경은 마지막 저장이 이긴다 (NFR-SYNC-03).
 * 검사 순서는 API.md가 정한 대로다. 권한(R-08)은 `SpaceAccessPolicy`가 하고, 공간 행을 잠근 뒤 한 번 더 확인한다.
 */
@Service
class SpaceSettingsService(
	private val policy: SpaceAccessPolicy,
	private val spaces: SpaceRepository,
	private val members: SpaceMemberRepository,
	private val userService: UserService,
	private val detailService: SpaceDetailService,
	private val events: ApplicationEventPublisher,
	private val clock: Clock,
) {

	/**
	 * 처음 만난 날 변경. 검사 순서: ① R-08 개인 공간이면 409 `NOT_COUPLE` → ② `startDate`가 없거나 null이면 `REQUIRED`,
	 * 날짜 형식이 아니면 `INVALID` → ③ 요청자 시간대 기준 오늘보다 뒤면 `VALIDATION_FAILED`(`INVALID`) →
	 * ④ 1950-01-01보다 앞이면 `DATE_OUT_OF_RANGE`(`OUT_OF_RANGE`). 지우기(null)는 없다 (D-84).
	 * [rawStartDate]는 JSON 값 그대로다.
	 */
	@Transactional
	fun updateStartDate(userId: UUID, rawStartDate: Any?): SpaceDetail {
		val spaceId = policy.requireCoupleSpace(userId)
		val startDate = validatedStartDate(userId, rawStartDate)

		lockCoupleSpace(userId, spaceId).let { space ->
			space.startDate = startDate
			space.updatedAt = clock.nowInSeconds()
		}
		events.publishEvent(SpaceUpdated(spaceId, userId))
		return detailService.get(userId)
	}

	/**
	 * 사람 색·'함께' 색 변경. 검사 순서 (API.md 3.2): 1. 필드 누락·null(요청 파싱에서 이미 끝남) →
	 * 2. R-08 개인 공간이면서 `together`가 있거나 자기 밖 `userId`가 있으면 409 `NOT_COUPLE` →
	 * 3. 값 검사 400 `VALIDATION_FAILED`(`INVALID`): 팔레트 밖 색, 활성 구성원이 아닌 `userId`, 같은 `userId` 두 번.
	 */
	@Transactional
	fun updateColors(userId: UUID, change: ColorChange): SpaceDetail {
		val requestedUserIds = change.members.map { parseUuid(it.userId) }
		val spaceId = policy.requireColorChangeAllowed(userId, requestedUserIds, change.togetherPresent)

		val space = spaces.findByIdForUpdate(spaceId)
		// 잠그는 사이 연결이 해제됐거나 공간이 지워졌으면 같은 규칙을 다시 적용한다
		if (space == null || policy.requireColorChangeAllowed(userId, requestedUserIds, change.togetherPresent) != spaceId) {
			throw ApiException(ErrorCode.NOT_COUPLE, "space changed while updating colors")
		}

		val activeMembers = members.findAllBySpaceIdAndLeftAtIsNull(spaceId).associateBy { it.userId }
		val errors = mutableListOf<FieldError>()
		val seen = mutableSetOf<UUID>()
		val memberColors = mutableListOf<Pair<UUID, String>>()
		change.members.forEachIndexed { position, item ->
			val memberUserId = requestedUserIds[position]
			// 활성 구성원이 아니거나(다른 공간 사용자도 같은 응답이다, DB-05) 해석할 수 없는 값이거나 같은 사용자가 두 번이면 INVALID
			if (memberUserId == null || memberUserId !in activeMembers || !seen.add(memberUserId)) {
				errors += FieldError("members[${item.index}].userId", FieldErrorReason.INVALID)
			}
			val color = item.color as? String
			if (color == null || !PersonColors.isPaletteKey(color)) {
				errors += FieldError("members[${item.index}].color", FieldErrorReason.INVALID)
			} else if (memberUserId != null) {
				memberColors += memberUserId to color
			}
		}
		var together: String? = null
		if (change.togetherPresent) {
			together = change.together as? String
			if (together == null || !PersonColors.isPaletteKey(together)) {
				errors += FieldError("together", FieldErrorReason.INVALID)
			}
		}
		if (errors.isNotEmpty()) throw validationFailed(errors)

		memberColors.forEach { (memberUserId, color) -> activeMembers.getValue(memberUserId).color = color }
		if (change.togetherPresent) space.togetherColor = together
		space.updatedAt = clock.nowInSeconds()
		events.publishEvent(SpaceUpdated(spaceId, userId))
		return detailService.get(userId)
	}

	/** 공간 행을 잠그고, 그 사이 연결이 해제되지 않았는지(R-08) 다시 확인한다 */
	private fun lockCoupleSpace(userId: UUID, spaceId: UUID) =
		spaces.findByIdForUpdate(spaceId)
			?.takeIf { policy.requireCoupleSpace(userId) == spaceId }
			?: throw ApiException(ErrorCode.NOT_COUPLE, "space changed while updating")

	private fun validatedStartDate(userId: UUID, raw: Any?): LocalDate {
		val date = when (raw) {
			null -> throw validationFailed(listOf(FieldError(START_DATE, FieldErrorReason.REQUIRED)))
			is String -> try {
				LocalDate.parse(raw)
			} catch (e: DateTimeParseException) {
				throw validationFailed(listOf(FieldError(START_DATE, FieldErrorReason.INVALID)))
			}
			else -> throw validationFailed(listOf(FieldError(START_DATE, FieldErrorReason.INVALID)))
		}
		val user = checkNotNull(userService.findActive(userId)) { "requester is missing" }
		// 서버 시간대가 아니라 요청자의 시간대가 기준이다 (NFR-TIME-01). Clock.withZone은 테스트 시계가 무시하므로 직접 변환한다
		val today = clock.instant().atZone(zoneOf(user.timeZone)).toLocalDate()
		if (date.isAfter(today)) throw validationFailed(listOf(FieldError(START_DATE, FieldErrorReason.INVALID)))
		if (date.isBefore(MIN_START_DATE)) {
			throw ApiException(
				ErrorCode.DATE_OUT_OF_RANGE,
				"$START_DATE: ${FieldErrorReason.OUT_OF_RANGE}",
				FieldErrorDetails(listOf(FieldError(START_DATE, FieldErrorReason.OUT_OF_RANGE))),
			)
		}
		return date
	}

	private fun zoneOf(timeZone: String): ZoneId =
		try {
			ZoneId.of(timeZone)
		} catch (e: DateTimeException) {
			ZoneId.of("UTC")
		}

	private fun parseUuid(raw: Any): UUID? =
		(raw as? String)?.let { text ->
			try {
				UUID.fromString(text)
			} catch (e: IllegalArgumentException) {
				null
			}
		}

	private fun validationFailed(errors: List<FieldError>) = ApiException(
		ErrorCode.VALIDATION_FAILED,
		errors.joinToString { "${it.field}: ${it.reason}" },
		FieldErrorDetails(errors),
	)

	private companion object {
		const val START_DATE = "startDate"

		/** 처음 만난 날의 하한 (R-15, D-74) */
		val MIN_START_DATE: LocalDate = LocalDate.of(1950, 1, 1)
	}
}
