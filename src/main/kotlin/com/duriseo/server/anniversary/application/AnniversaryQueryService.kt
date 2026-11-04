package com.duriseo.server.anniversary.application

import com.duriseo.server.anniversary.domain.AnniversaryRepository
import com.duriseo.server.common.access.SpaceAccessPolicy
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.time.UserZones
import com.duriseo.server.space.application.SpaceService
import com.duriseo.server.space.domain.SpaceType
import com.duriseo.server.user.application.UserService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 기념일의 종류 (API.md 3.3). `AUTO`가 `CUSTOM`보다 앞이다 (같은 날의 정렬 순서는 PRD v0.10 반영 대기) */
enum class AnniversaryKind { AUTO, CUSTOM }

/**
 * `GET /anniversaries/upcoming` 항목 (API.md 3.3). 직접 기념일만 [anniversaryId]·[authorId]가 있고 자동은 null이다.
 * [daysLeft]는 요청자 시간대의 오늘이면 0이다.
 * 자동 기념일의 [name]은 아직 정해지지 않아 null이다 (PRD v0.10 반영 대기, 확인 필요 3). 자동 기념일의 종류·횟수는 [auto]에 있다.
 */
data class UpcomingAnniversary(
	val kind: AnniversaryKind,
	val anniversaryId: UUID?,
	val name: String?,
	val date: LocalDate,
	val daysLeft: Long,
	val repeatsYearly: Boolean,
	val authorId: UUID?,
	val auto: AutoAnniversary?,
)

/** `GET /anniversaries/past` 항목 (API.md 3.3) */
data class PastAnniversary(val anniversaryId: UUID, val name: String, val date: LocalDate, val authorId: UUID)

/** "지난 기념일" 전체와 개수. [totalCount]는 조건에 맞는 전체 개수다 (페이지와 관계없음) */
data class PastAnniversaries(val items: List<PastAnniversary>, val totalCount: Long)

/**
 * 기념일 목록 계산 (FR-ANNIV-02·04, R-01). 요청자의 활성 공간 것만 읽으므로 다른 공간의 기념일은 나오지 않는다.
 * 오늘은 요청자 `users.time_zone`의 오늘이다 (API.md 3.3, 서버 시간대는 쓰지 않는다, NFR-TIME-01).
 *
 * 개인 공간과 처음 만난 날이 없는 커플 공간에는 자동 기념일이 없다 (FR-ANNIV-04 AC2·AC4).
 * 커서·`size`를 쓴 페이지 나누기, 캘린더용 범위 조회, 컨트롤러는 PRD v0.10 반영 대기라 아직 없다 (unit-4.md 확인 필요 2·4).
 * 지금은 전체 목록을 돌려준다.
 */
@Service
class AnniversaryQueryService(
	private val policy: SpaceAccessPolicy,
	private val anniversaryRepository: AnniversaryRepository,
	private val spaceService: SpaceService,
	private val userService: UserService,
	private val clock: Clock,
) {

	/**
	 * 오늘(포함)부터 2050-12-31까지의 자동·직접 기념일 발생일을 날짜순으로 돌려준다 (FR-ANNIV-04 AC1, D-75).
	 * 매년 반복하는 기념일은 해마다 한 항목이다. 날짜가 지난 1회성 직접 기념일은 여기 없다("지난 기념일"로 간다).
	 * 같은 날은 자동 → 직접 순서, 자동끼리는 일수 → 주년 순, 직접끼리는 ID 순이다.
	 */
	@Transactional(readOnly = true)
	fun upcoming(requesterId: UUID): List<UpcomingAnniversary> {
		val space = spaceService.activeSpaceView(requesterId)
		val today = todayOf(requesterId)

		val auto = space.startDate
			?.takeIf { space.type == SpaceType.COUPLE }
			?.let { AnniversaryCalculator.autoAnniversariesBetween(it, today, AnniversaryCalculator.MAX_DATE) }
			.orEmpty()
			.map { UpcomingAnniversary(AnniversaryKind.AUTO, null, null, it.date, daysBetween(today, it.date), false, null, it) }

		val custom = anniversaryRepository.findAllBySpaceId(space.spaceId).flatMap { anniversary ->
			AnniversaryCalculator
				.customOccurrencesBetween(anniversary.date, anniversary.repeatsYearly, today, AnniversaryCalculator.MAX_DATE)
				.map { date ->
					UpcomingAnniversary(
						kind = AnniversaryKind.CUSTOM,
						anniversaryId = anniversary.requireId(),
						name = anniversary.name,
						date = date,
						daysLeft = daysBetween(today, date),
						repeatsYearly = anniversary.repeatsYearly,
						authorId = anniversary.authorId,
						auto = null,
					)
				}
		}

		return (auto + custom).sortedWith(
			compareBy<UpcomingAnniversary> { it.date }
				.thenBy { it.kind.ordinal }
				.thenBy { it.auto?.type?.ordinal ?: 0 }
				.thenBy { it.auto?.count ?: 0 }
				.thenBy { it.anniversaryId?.toString().orEmpty() },
		)
	}

	/**
	 * "지난 기념일" (FR-ANNIV-04 AC3, D-71): `repeats_yearly = false`이고 `date`가 오늘(요청자 시간대)보다 앞인 직접 기념일만 날짜 최근 순으로.
	 * 오늘 날짜인 1회성 기념일은 아직 지난 것이 아니라 [upcoming]에 있다.
	 */
	@Transactional(readOnly = true)
	fun past(requesterId: UUID): PastAnniversaries {
		val spaceId = checkNotNull(policy.activeSpaceIdOf(requesterId)) { "user has no active space" }
		val items = anniversaryRepository.findPast(spaceId, todayOf(requesterId))
			.map { PastAnniversary(it.requireId(), it.name, it.date, it.authorId) }
		return PastAnniversaries(items, items.size.toLong())
	}

	/** 요청자 시간대의 오늘. 시간대를 해석할 수 없으면 UTC (`UserZones`) */
	private fun todayOf(userId: UUID): LocalDate {
		val user = userService.findActive(userId) ?: throw ApiException(ErrorCode.SESSION_INVALID, "user not found")
		return clock.instant().atZone(UserZones.parseOrUtc(user.timeZone)).toLocalDate()
	}

	private fun daysBetween(from: LocalDate, to: LocalDate): Long = ChronoUnit.DAYS.between(from, to)
}
