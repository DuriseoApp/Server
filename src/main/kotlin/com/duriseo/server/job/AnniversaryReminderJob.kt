package com.duriseo.server.job

import com.duriseo.server.anniversary.application.AnniversaryCalculator
import com.duriseo.server.common.time.UserZones
import com.duriseo.server.common.time.nowInSeconds
import com.duriseo.server.notification.application.NotificationService
import com.duriseo.server.notification.application.NotificationTexts
import com.duriseo.server.push.application.NotificationDispatcher
import com.duriseo.server.user.application.UserService
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Date
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 기념일 알림 작업 (API.md 7장 "기념일 알림", PRD D-32·D-55·D-105·D-106·D-138, ERD DB-18). 매시 정각에 돈다.
 *
 * 사용자 현지 시(hour)가 9인 사용자(30분 단위 시간대도 그 시각에 9시대다)의 활성 공간에서, 현지 오늘이 기념일(자동·직접)이면
 * 기념일마다 `notifications`(`ANNIVERSARY`) 행을 만든다. 받는 사람은 공간의 구성원 각자다(커플이면 둘 다, 각자의 시간대로). 자동 기념일은 커플 공간에
 * 처음 만난 날이 있을 때만 있고 `target_id`는 null이다. 푸시(`anniversary` 설정·유효 세션)와 `NOTIFICATION` 신호는 커밋 뒤 [NotificationDispatcher]가 보낸다.
 *
 * 중복 방지는 컬럼 없이 사용자 현지 오늘 구간에 같은 (user, type, title) 행이 있으면 건너뛴다(DB-18). 같은 시각에 다시 돌거나 서버가 재시작돼도 한 번만 간다.
 * 사용자마다 자기 트랜잭션이고 사용자 행을 먼저 잠근다(ARCHITECTURE 4장). 한 사용자의 실패는 로그만 남기고 다음 사용자로 넘어간다.
 */
@Component
class AnniversaryReminderJob(
	private val em: EntityManager,
	private val userService: UserService,
	private val notificationService: NotificationService,
	private val dispatcher: NotificationDispatcher,
	private val clock: Clock,
	transactionManager: PlatformTransactionManager,
) {

	private val log = LoggerFactory.getLogger(javaClass)
	private val tx = TransactionTemplate(transactionManager)

	/** 스케줄러가 부른다. 서버 UTC 정각마다 돈다(사용자 시간대 판정은 [run]이 한다). 예외를 밖으로 던지지 않는다 */
	@Scheduled(cron = "0 0 * * * *", zone = "UTC")
	fun scheduledRun() {
		try {
			run()
		} catch (e: Exception) {
			log.error("anniversary job failed", e)
		}
	}

	/** 지금 현지 시가 9인 사용자에게 기념일 알림을 만든다. 만든 알림 수를 돌려준다 (테스트가 직접 부른다) */
	fun run(): Int {
		val zones = zonesAtNineOClock()
		if (zones.isEmpty()) return 0
		var created = 0
		var after = NIL_UUID
		while (true) {
			val userIds = candidateUsers(zones, after)
			if (userIds.isEmpty()) return created
			userIds.forEach { userId ->
				try {
					created += processUser(userId)
				} catch (e: Exception) {
					log.error("anniversary notification for user {} failed", userId, e)
				}
			}
			after = userIds.last()
		}
	}

	/** 지금 현지 시가 9인 `users.time_zone` 값들. 해석할 수 없는 값은 UTC로 본다 (`UserZones`) */
	private fun zonesAtNineOClock(): Set<String> {
		val now = clock.instant()
		return em.createNativeQuery("select distinct u.time_zone from users u where u.deleted_at is null")
			.resultList
			.map { it as String }
			.filter { now.atZone(UserZones.parseOrUtc(it)).hour == NOTIFY_HOUR }
			.toSet()
	}

	private fun candidateUsers(zones: Set<String>, after: UUID): List<UUID> =
		em.createNativeQuery(
			"select u.id from users u where u.deleted_at is null and u.time_zone in (:zones) and u.id > :after " +
				"and exists (select 1 from space_members sm where sm.user_id = u.id and sm.left_at is null) " +
				"order by u.id limit :limit",
		)
			.setParameter("zones", zones)
			.setParameter("after", after)
			.setParameter("limit", BATCH_SIZE)
			.resultList
			.map { it as UUID }

	private class Today(val spaceId: UUID, val startDate: LocalDate?, val zoneId: String)

	private class Draft(val name: String, val body: String, val anniversaryId: UUID?)

	/** 사용자 한 명의 오늘 기념일 알림을 만들고 커밋한 뒤 보낸다. 만든 알림 수를 돌려준다 */
	private fun processUser(userId: UUID): Int {
		val notificationIds = tx.execute<List<UUID>> {
			// 잠금 순서: 사용자 행이 먼저다. 이 사용자의 다른 실행·탈퇴·해제와 한 줄로 선다
			userService.lockForUpdate(userId)
			val now = clock.nowInSeconds()
			val me = readActiveSpace(userId) ?: return@execute emptyList()
			val zone = UserZones.parseOrUtc(me.zoneId)
			val zoned = now.atZone(zone)
			if (zoned.hour != NOTIFY_HOUR) return@execute emptyList()
			val today = zoned.toLocalDate()
			val dayStart = today.atStartOfDay(zone).toInstant()
			val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant()

			val ids = mutableListOf<UUID>()
			val titles = mutableSetOf<String>()
			todaysAnniversaries(me, today).forEach { draft ->
				val title = NotificationTexts.anniversaryTitle(draft.name)
				// DB-18: 같은 날 같은 제목이 이미 있으면 건너뛴다 (이번 실행에서 방금 만든 것 포함)
				if (title in titles || alreadySent(userId, title, dayStart, dayEnd)) return@forEach
				titles += title
				ids += notificationService.createAnniversary(userId, draft.name, draft.body, me.spaceId, draft.anniversaryId, now)
			}
			ids
		}.orEmpty()
		// 커밋 뒤에 보낸다. dispatch는 예외를 던지지 않는다
		notificationIds.forEach { dispatcher.dispatch(it) }
		return notificationIds.size
	}

	/** 사용자의 활성 공간과 시간대. 잠근 뒤 다시 읽는다(그 사이 탈퇴·해제됐을 수 있다). 없으면 null */
	private fun readActiveSpace(userId: UUID): Today? {
		val row = em.createNativeQuery(
			"select s.id, s.start_date, u.time_zone from users u " +
				"join space_members sm on sm.user_id = u.id and sm.left_at is null " +
				"join spaces s on s.id = sm.space_id and s.status = 'ACTIVE' " +
				"where u.id = :userId and u.deleted_at is null",
		)
			.setParameter("userId", userId)
			.resultList
			.firstOrNull() as Array<*>? ?: return null
		val startDate = when (val value = row[1]) {
			null -> null
			is LocalDate -> value
			is Date -> value.toLocalDate()
			else -> error("unexpected date type ${value.javaClass.simpleName}")
		}
		return Today(row[0] as UUID, startDate, row[2] as String)
	}

	/** 오늘(현지)이 발생일인 기념일: 자동 일수 → 자동 주년 → 직접(`id` 오름차순), API.md 3.3 "같은 날 순서" */
	private fun todaysAnniversaries(space: Today, today: LocalDate): List<Draft> {
		val startDate = space.startDate
		val auto = if (startDate == null) {
			emptyList()
		} else {
			AnniversaryCalculator.autoAnniversariesBetween(startDate, today, today)
				.map { Draft(it.name, NotificationTexts.autoAnniversaryBody(ChronoUnit.DAYS.between(startDate, today) + 1), null) }
		}
		val custom = em.createNativeQuery(
			"select a.id, a.name, a.date, a.repeats_yearly from anniversaries a where a.space_id = :spaceId order by a.id",
		)
			.setParameter("spaceId", space.spaceId)
			.resultList
			.mapNotNull { row ->
				row as Array<*>
				val date = when (val value = row[2]) {
					is LocalDate -> value
					is Date -> value.toLocalDate()
					else -> error("unexpected date type ${value?.javaClass?.simpleName}")
				}
				val repeatsYearly = row[3] as Boolean
				if (AnniversaryCalculator.customOccurrencesBetween(date, repeatsYearly, today, today).isEmpty()) return@mapNotNull null
				// D-55: 매년 반복이고 올해가 등록한 해보다 뒤일 때만 "[N]번째 맞는 날이에요"
				val nth = if (repeatsYearly && today.year > date.year) today.year - date.year + 1 else null
				Draft(row[1] as String, NotificationTexts.customAnniversaryBody(nth), row[0] as UUID)
			}
		return auto + custom
	}

	private fun alreadySent(userId: UUID, title: String, from: java.time.Instant, to: java.time.Instant): Boolean =
		(em.createNativeQuery(
			"select count(*) from notifications n where n.user_id = :userId and n.type = 'ANNIVERSARY' and n.title = :title " +
				"and n.created_at >= :from and n.created_at < :to",
		)
			.setParameter("userId", userId)
			.setParameter("title", title)
			.setParameter("from", from)
			.setParameter("to", to)
			.singleResult as Number).toLong() > 0

	private companion object {
		const val NOTIFY_HOUR = 9
		const val BATCH_SIZE = 100
		val NIL_UUID: UUID = UUID(0L, 0L)
	}
}
