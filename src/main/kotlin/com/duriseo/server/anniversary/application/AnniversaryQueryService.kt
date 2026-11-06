package com.duriseo.server.anniversary.application

import com.duriseo.server.anniversary.domain.Anniversary
import com.duriseo.server.anniversary.domain.AnniversaryRepository
import com.duriseo.server.common.access.SpaceAccessPolicy
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.FieldError
import com.duriseo.server.common.api.FieldErrorDetails
import com.duriseo.server.common.api.FieldErrorReason
import com.duriseo.server.common.time.UserZones
import com.duriseo.server.space.application.ActiveSpaceView
import com.duriseo.server.space.application.SpaceService
import com.duriseo.server.space.domain.SpaceType
import com.duriseo.server.user.application.UserService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 기념일의 종류 (API.md 3.3). 응답의 `kind` */
enum class AnniversaryKind { AUTO, CUSTOM }

/**
 * `AnniversaryOccurrence` (API.md 3.3): 발생일 한 건. 캘린더용 `GET /anniversaries`와 `GET /anniversaries/upcoming`이 같은 모양이다.
 * 직접 기념일만 [anniversaryId]·[authorId]가 있고 자동은 null이다. [daysLeft]는 요청자 시간대의 오늘이면 0이다.
 * 자동 기념일의 종류·횟수는 [auto]에 있다. [name]은 자동이 "100일"·"1주년"(D-105), 직접은 기념일의 이름이다.
 */
data class AnniversaryOccurrence(
	val kind: AnniversaryKind,
	val anniversaryId: UUID?,
	val name: String,
	val date: LocalDate,
	val daysLeft: Long,
	val repeatsYearly: Boolean,
	val authorId: UUID?,
	val auto: AutoAnniversary?,
) {
	/** 같은 날 순번: 자동 일수 0, 자동 주년 1, 직접 2 (D-106, Q-15) */
	val rank: Int get() = auto?.rank ?: AnniversaryCursor.CUSTOM_RANK
}

/** `GET /anniversaries/upcoming` 한 페이지 */
data class UpcomingPage(val items: List<AnniversaryOccurrence>, val nextCursor: String?)

/** `GET /anniversaries/past` 항목 (API.md 3.3) */
data class PastAnniversary(val anniversaryId: UUID, val name: String, val date: LocalDate, val authorId: UUID)

/** "지난 기념일" 한 페이지. [totalCount]는 조건에 맞는 전체 개수다 (페이지와 관계없음, D-71) */
data class PastPage(val items: List<PastAnniversary>, val totalCount: Long, val nextCursor: String?)

/**
 * 기념일 목록 계산 (FR-ANNIV-02·04, R-01, R-15). 요청자의 활성 공간 것만 읽으므로 다른 공간의 기념일은 나오지 않는다.
 * 오늘은 요청자 `users.time_zone`의 오늘이다 (API.md 3.3, 서버 시간대는 쓰지 않는다, NFR-TIME-01).
 *
 * 개인 공간과 처음 만난 날이 없는 커플 공간에는 자동 기념일이 없다 (FR-ANNIV-04 AC2·AC4).
 * 같은 날 순서는 ① 자동 일수 ② 자동 주년 ③ 직접(`anniversaryId` 오름차순)이다 (D-106, Q-15 기본값). 직접끼리의 ID 비교는
 * PostgreSQL `uuid` 비교(부호 없는 바이트 순)와 같다 ([compareIds]).
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
	 * `GET /anniversaries?from=&to=` (캘린더용, FR-CAL-01, R-01, R-15). 범위 안의 자동 + 직접 기념일 발생일을 날짜순으로 돌려준다.
	 * [rawFrom]·[rawTo]는 쿼리 문자열 그대로다. 검사(필수·형식·순서·3개월)는 요청받은 값 그대로 먼저 하고,
	 * 그 뒤 1950-01-01 ~ 2050-12-31로 잘라낸다. 잘라낸 구간이 비면 빈 목록이다. `daysLeft`는 지난 날짜면 음수다.
	 */
	@Transactional(readOnly = true)
	fun listBetween(requesterId: UUID, rawFrom: String?, rawTo: String?): List<AnniversaryOccurrence> {
		val (from, to) = parseRange(rawFrom, rawTo)
		val space = spaceService.activeSpaceView(requesterId)
		val clampedFrom = if (from.isBefore(AnniversaryCalculator.MIN_DATE)) AnniversaryCalculator.MIN_DATE else from
		val clampedTo = if (to.isAfter(AnniversaryCalculator.MAX_DATE)) AnniversaryCalculator.MAX_DATE else to
		if (clampedFrom.isAfter(clampedTo)) return emptyList()
		return occurrences(requesterId, space, todayOf(requesterId), clampedFrom, clampedTo)
	}

	/**
	 * 오늘(포함)부터 2050-12-31까지의 자동·직접 기념일 발생일 전체를 정렬한 목록 (FR-ANNIV-04 AC1, D-75). 페이지를 나누지 않는다.
	 * 매년 반복하는 기념일은 해마다 한 항목이다. 날짜가 지난 1회성 직접 기념일은 여기 없다("지난 기념일"로 간다).
	 */
	@Transactional(readOnly = true)
	fun upcoming(requesterId: UUID): List<AnniversaryOccurrence> {
		val today = todayOf(requesterId)
		return occurrences(requesterId, spaceService.activeSpaceView(requesterId), today, today, AnniversaryCalculator.MAX_DATE)
	}

	/**
	 * `GET /anniversaries/upcoming?cursor=&size=` (FR-ANNIV-04 AC1·AC5, D-75, R-01). 정렬 키는 (`date`, 순번, `anniversaryId`)이고
	 * 커서는 앞 페이지 마지막 항목의 키다: 다음 페이지는 그 키보다 큰 항목이라 같은 날 항목이 페이지 경계에서 빠지거나 겹치지 않는다.
	 * 전체를 메모리에서 만들어 정렬한 뒤 커서 뒤부터 자른다(공간당 직접 기념일 수가 작다는 가정, 문서에 상한 없음).
	 */
	@Transactional(readOnly = true)
	fun upcomingPage(requesterId: UUID, rawCursor: String?, rawSize: String?): UpcomingPage {
		val query = AnniversaryCursor.parseUpcoming(rawCursor, rawSize)
		val rest = upcoming(requesterId).let { all ->
			val cursor = query.cursor ?: return@let all
			all.dropWhile { compareKeys(it.date, it.rank, it.anniversaryId, cursor.date, cursor.rank, cursor.anniversaryId) <= 0 }
		}
		val items = rest.take(query.size)
		val next = if (rest.size > items.size) items.last().let { AnniversaryCursor.encode(UpcomingCursorKey(it.date, it.rank, it.anniversaryId)) } else null
		return UpcomingPage(items, next)
	}

	/**
	 * `GET /anniversaries/past?cursor=&size=` ("지난 기념일", FR-ANNIV-04 AC3, D-71, R-01): `repeats_yearly = false`이고 `date`가
	 * 오늘(요청자 시간대)보다 앞인 직접 기념일만, `date` 내림차순 → `anniversaryId` 내림차순으로. 커서 (`date`, `anniversaryId`)는
	 * 같은 방향으로 "이 값보다 작은 것"을 비교한다. 오늘 날짜인 1회성 기념일은 아직 지난 것이 아니라 [upcoming]에 있다.
	 */
	@Transactional(readOnly = true)
	fun past(requesterId: UUID, rawCursor: String? = null, rawSize: String? = null): PastPage {
		val query = AnniversaryCursor.parsePast(rawCursor, rawSize)
		// 활성 공간이 없는 사용자는 `todayOf`의 사용자 없음과 같이 401 SESSION_INVALID로 처리한다 (500이 나지 않게)
		val spaceId = policy.activeSpaceIdOf(requesterId) ?: throw ApiException(ErrorCode.SESSION_INVALID, "user has no active space")
		val today = todayOf(requesterId)
		val limit = query.size + 1
		val cursor = query.cursor
		val found = if (cursor == null) {
			anniversaryRepository.findPastFirstPage(spaceId, today, limit)
		} else {
			anniversaryRepository.findPastAfter(spaceId, today, cursor.date, cursor.anniversaryId, limit)
		}
		val page = found.take(query.size).map { it.toPast() }
		val next = if (found.size > page.size) page.last().let { AnniversaryCursor.encode(PastCursorKey(it.date, it.anniversaryId)) } else null
		return PastPage(page, anniversaryRepository.countPast(spaceId, today), next)
	}

	// ── 계산 ────────────────────────────────────────────

	/** [from]~[to](양 끝 포함)의 자동 + 직접 발생일을 정렬해 돌려준다. [from]·[to]는 이미 1950~2050 안이다 */
	private fun occurrences(requesterId: UUID, space: ActiveSpaceView, today: LocalDate, from: LocalDate, to: LocalDate): List<AnniversaryOccurrence> {
		val auto = space.startDate
			?.takeIf { space.type == SpaceType.COUPLE }
			?.let { AnniversaryCalculator.autoAnniversariesBetween(it, from, to) }
			.orEmpty()
			.map { AnniversaryOccurrence(AnniversaryKind.AUTO, null, it.name, it.date, daysBetween(today, it.date), false, null, it) }

		val custom = anniversaryRepository.findAllBySpaceId(space.spaceId).flatMap { anniversary ->
			AnniversaryCalculator.customOccurrencesBetween(anniversary.date, anniversary.repeatsYearly, from, to).map { date ->
				AnniversaryOccurrence(
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
		return (auto + custom).sortedWith { a, b -> compareKeys(a.date, a.rank, a.anniversaryId, b.date, b.rank, b.anniversaryId) }
	}

	/** (`date`, 순번, `anniversaryId`) 순서. 자동은 `anniversaryId`가 없어 순번으로 갈린다 */
	private fun compareKeys(dateA: LocalDate, rankA: Int, idA: UUID?, dateB: LocalDate, rankB: Int, idB: UUID?): Int {
		val byDate = dateA.compareTo(dateB)
		if (byDate != 0) return byDate
		if (rankA != rankB) return rankA.compareTo(rankB)
		return compareIds(idA, idB)
	}

	private fun Anniversary.toPast() = PastAnniversary(requireId(), name, date, authorId)

	/** 요청자 시간대의 오늘. 시간대를 해석할 수 없으면 UTC (`UserZones`) */
	private fun todayOf(userId: UUID): LocalDate {
		val user = userService.findActive(userId) ?: throw ApiException(ErrorCode.SESSION_INVALID, "user not found")
		return clock.instant().atZone(UserZones.parseOrUtc(user.timeZone)).toLocalDate()
	}

	private fun daysBetween(from: LocalDate, to: LocalDate): Long = ChronoUnit.DAYS.between(from, to)

	// ── GET /anniversaries 쿼리 ─────────────────────────

	/**
	 * `from`·`to`는 모두 필수 `YYYY-MM-DD`이고 `to`를 포함한다. 없으면 `REQUIRED`, 날짜 형식이 아니면 `INVALID`,
	 * `from` > `to`이거나 `to` > `from` + 3개월(달력 월로 더함)이면 (`to`, `INVALID`). 틀린 필드를 모두 담는다 (일정 조회와 같다).
	 */
	private fun parseRange(rawFrom: String?, rawTo: String?): Pair<LocalDate, LocalDate> {
		val errors = mutableListOf<FieldError>()
		val from = parseQueryDate("from", rawFrom, errors)
		val to = parseQueryDate("to", rawTo, errors)
		if (from != null && to != null) {
			if (from.isAfter(to) || to.isAfter(from.plusMonths(RANGE_MONTHS))) errors += FieldError("to", FieldErrorReason.INVALID)
		}
		if (errors.isNotEmpty()) {
			throw ApiException(
				ErrorCode.VALIDATION_FAILED,
				errors.joinToString { "${it.field}: ${it.reason}" },
				FieldErrorDetails(errors),
			)
		}
		return checkNotNull(from) to checkNotNull(to)
	}

	private fun parseQueryDate(field: String, raw: String?, errors: MutableList<FieldError>): LocalDate? {
		if (raw == null) {
			errors += FieldError(field, FieldErrorReason.REQUIRED)
			return null
		}
		if (!QUERY_DATE_FORMAT.matches(raw)) {
			errors += FieldError(field, FieldErrorReason.INVALID)
			return null
		}
		return try {
			LocalDate.parse(raw)
		} catch (e: DateTimeParseException) {
			errors += FieldError(field, FieldErrorReason.INVALID)
			null
		}
	}

	private companion object {
		/** 조회 범위 한도: `to`는 `from` + 3개월(달력 월)까지 (API.md 3.3 "조회 규칙") */
		const val RANGE_MONTHS = 3L

		val QUERY_DATE_FORMAT = Regex("""\d{4}-\d{2}-\d{2}""")
	}
}

/**
 * 직접 기념일 ID 비교: PostgreSQL `uuid` 비교(부호 없는 바이트 순)와 같다 (API.md 3.3 리드 보충). `java.util.UUID.compareTo`는
 * 부호 있는 비교라 쓰지 않는다. null(자동 기념일)이 앞이다.
 */
internal fun compareIds(a: UUID?, b: UUID?): Int {
	if (a == null || b == null) return if (a == null && b == null) 0 else if (a == null) -1 else 1
	val high = java.lang.Long.compareUnsigned(a.mostSignificantBits, b.mostSignificantBits)
	return if (high != 0) high else java.lang.Long.compareUnsigned(a.leastSignificantBits, b.leastSignificantBits)
}
