package com.duriseo.server.job

import com.duriseo.server.common.time.UserZones
import com.duriseo.server.common.time.nowInSeconds
import com.duriseo.server.event.application.EventReminderTexts
import com.duriseo.server.event.domain.ReminderOption
import com.duriseo.server.notification.application.NotificationService
import com.duriseo.server.push.application.NotificationDispatcher
import com.duriseo.server.user.application.UserService
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 일정 알림 작업 (API.md 7장 "일정 알림", FR-CAL-05, D-90·D-137·D-139, ERD event_reminders, DB-03). 1분마다 돈다.
 *
 * `fire_at <= now`이고 `sent_at IS NULL`인 `event_reminders`마다 알림 주인에게 `notifications`(`EVENT_REMINDER`) 행을 만들고 `sent_at`을 기록한다.
 * 푸시와 `NOTIFICATION` 신호는 **커밋 뒤** [NotificationDispatcher]가 보낸다. `fire_at`이 `now − 10분`보다 오래면 행만 만들고 푸시는 생략한다(D-137).
 *
 * 동시성: 후보 ID는 잠금 없이 읽고, 행마다 **자기 트랜잭션**에서 처리한다. 순서는 사용자 행 `FOR SHARE` → 알림 행 `FOR UPDATE SKIP LOCKED`다
 * (ARCHITECTURE 4장 users → … 순서. 알림 행을 먼저 잡으면 사용자 행을 `FOR UPDATE`로 잡은 채 알림 행을 고치는 `PATCH /me`와 서로 기다린다).
 * 잠금을 잡은 SQL이 `fire_at`·`sent_at` 조건을 다시 확인하므로, 같은 알림이 두 번 돌거나 `PATCH /events`·`PATCH /me`의 다시 계산과 겹쳐도
 * 행과 푸시는 한 번이고 옛 시각으로 보내지 않는다. 한 행의 실패는 로그만 남기고 다음 행으로 넘어간다.
 */
@Component
class EventReminderJob(
	private val em: EntityManager,
	private val userService: UserService,
	private val notificationService: NotificationService,
	private val dispatcher: NotificationDispatcher,
	private val clock: Clock,
	transactionManager: PlatformTransactionManager,
) {

	private val log = LoggerFactory.getLogger(javaClass)
	private val tx = TransactionTemplate(transactionManager)

	/** 스케줄러가 부른다. 예외를 밖으로 던지지 않는다 */
	@Scheduled(fixedDelay = INTERVAL_MILLIS, initialDelay = INTERVAL_MILLIS)
	fun scheduledRun() {
		try {
			run()
		} catch (e: Exception) {
			log.error("event reminder job failed", e)
		}
	}

	/** 지금 보낼 알림을 모두 처리한다. 처리한 알림 수를 돌려준다 (테스트가 직접 부른다) */
	fun run(): Int {
		var processed = 0
		var cursor: Candidate? = null
		while (true) {
			val now = clock.instant()
			val page = candidates(now, cursor)
			if (page.isEmpty()) return processed
			page.forEach { candidate ->
				try {
					if (sendOne(candidate.id)) processed++
				} catch (e: Exception) {
					log.error("event reminder {} failed", candidate.id, e)
				}
			}
			cursor = page.last()
		}
	}

	private data class Candidate(val id: UUID, val fireAt: Instant)

	/** 보낼 때가 된 알림 ID를 (`fire_at`, `id`) 순으로 한 묶음 읽는다. 커서로 넘겨 실패한 행이 뒤 행을 막지 않게 한다 */
	private fun candidates(now: Instant, after: Candidate?): List<Candidate> {
		val query = if (after == null) {
			em.createNativeQuery(
				"select r.id, r.fire_at from event_reminders r where r.sent_at is null and r.fire_at <= :now " +
					"order by r.fire_at, r.id limit :limit",
			)
		} else {
			em.createNativeQuery(
				"select r.id, r.fire_at from event_reminders r where r.sent_at is null and r.fire_at <= :now " +
					"and (r.fire_at, r.id) > (:afterAt, :afterId) order by r.fire_at, r.id limit :limit",
			).setParameter("afterAt", after.fireAt).setParameter("afterId", after.id)
		}
		return query.setParameter("now", now).setParameter("limit", BATCH_SIZE).resultList.map { row ->
			row as Array<*>
			Candidate(row[0] as UUID, toInstant(row[1]))
		}
	}

	private class Locked(
		val userId: UUID,
		val eventId: UUID,
		val spaceId: UUID,
		val title: String,
		val option: ReminderOption,
		val fireAt: Instant,
		val startAt: Instant?,
		val timeZone: String?,
	)

	/** 알림 하나를 처리한다. 이미 보냈거나 다른 쪽이 잡고 있거나 시각이 바뀌었으면 false */
	private fun sendOne(reminderId: UUID): Boolean {
		val ownerId = ownerOf(reminderId) ?: return false
		val outcome = tx.execute<Sent?> {
			userService.lockShare(listOf(ownerId))
			val now = clock.instant()
			val row = lock(reminderId, now) ?: return@execute null
			val sentAt = clock.nowInSeconds()
			em.createNativeQuery("update event_reminders set sent_at = :sentAt, updated_at = :sentAt where id = :id")
				.setParameter("sentAt", sentAt)
				.setParameter("id", reminderId)
				.executeUpdate()
			val body = EventReminderTexts.body(row.option, row.startAt, UserZones.parseOrUtc(row.timeZone))
			val notificationId = notificationService.createEventReminder(row.userId, row.title, body, row.spaceId, row.eventId, sentAt)
			// D-137: 시각이 지난 지 10분이 넘었으면 행만 만들고 푸시는 생략한다
			Sent(notificationId, withPush = !row.fireAt.isBefore(now.minus(LATE_LIMIT)))
		} ?: return false
		// 커밋 뒤에 보낸다. dispatch는 예외를 던지지 않는다
		dispatcher.dispatch(outcome.notificationId, outcome.withPush)
		return true
	}

	private class Sent(val notificationId: UUID, val withPush: Boolean)

	private fun ownerOf(reminderId: UUID): UUID? =
		em.createNativeQuery("select r.user_id from event_reminders r where r.id = :id")
			.setParameter("id", reminderId)
			.resultList
			.firstOrNull() as UUID?

	/**
	 * 알림 행을 잠그고(`FOR UPDATE OF r SKIP LOCKED`) 그 시점의 값을 읽는다. 조건(`sent_at IS NULL`, `fire_at <= now`)을 이 SQL이 다시 확인한다.
	 * 일정과 사용자는 읽기만 한다. 행이 없으면 null (이미 보냈거나, 다시 계산으로 시각이 미래가 됐거나, 지워졌거나, 다른 쪽이 잡고 있다)
	 */
	private fun lock(reminderId: UUID, now: Instant): Locked? {
		val row = em.createNativeQuery(
			"select r.user_id, r.event_id, e.space_id, e.title, r.option, r.fire_at, e.start_at, u.time_zone " +
				"from event_reminders r join events e on e.id = r.event_id join users u on u.id = r.user_id " +
				"where r.id = :id and r.sent_at is null and r.fire_at <= :now " +
				"for update of r skip locked",
		)
			.setParameter("id", reminderId)
			.setParameter("now", now)
			.resultList
			.firstOrNull() as Array<*>? ?: return null
		return Locked(
			userId = row[0] as UUID,
			eventId = row[1] as UUID,
			spaceId = row[2] as UUID,
			title = row[3] as String,
			option = ReminderOption.valueOf(row[4] as String),
			fireAt = toInstant(row[5]),
			startAt = row[6]?.let { toInstant(it) },
			timeZone = row[7] as String?,
		)
	}

	private fun toInstant(value: Any?): Instant = when (value) {
		is Instant -> value
		is Timestamp -> value.toInstant()
		is java.time.OffsetDateTime -> value.toInstant()
		else -> error("unexpected timestamp type ${value?.javaClass?.simpleName}")
	}

	private companion object {
		const val INTERVAL_MILLIS = 60_000L
		const val BATCH_SIZE = 100
		val LATE_LIMIT: Duration = Duration.ofMinutes(10)
	}
}
