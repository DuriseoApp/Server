package com.duriseo.server.job

import com.duriseo.server.common.api.ApiException
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 매일 03:00 KST 정리 작업 (ERD 4.4, API.md 7장 "알림 목록 정리"·"로그인 만료 기기 정리", DB-03·DB-10·DB-11·DB-14·DB-19).
 *
 * 단계는 이 순서로 돈다. 순서에 뜻이 있다: 게스트 정리는 세션 정리보다 먼저(DB-10), 기기 정리는 세션 정리 뒤(DB-14),
 * 공간 삭제는 사용자 행 삭제보다 먼저(공간이 지워져야 사용자 참조가 없어진다).
 *
 * 1. 게스트 정리 — 2. `purge_at` 지난 공간 — 3. 참조 없는 탈퇴 사용자 행 — 4. 오래된 초대코드·시도 기록 — 5. 세션 —
 * 6. 세션 없는 사용자의 기기 — 7. 30일 지난 알림 — 8. 프로필 사진 파일 정리(**단위 7**, 사진 저장소와 함께 한 단계로 더한다)
 *
 * 단계마다 따로 트랜잭션이다([CleanupStore]의 메서드 하나가 하나). 한 단계가 실패해도 로그만 남기고 다음 단계로 간다.
 * 게스트 정리는 사용자마다 따로 트랜잭션이라 한 사용자의 실패가 다른 사용자를 막지 않는다.
 */
@Component
class CleanupJob(
	private val store: CleanupStore,
	private val clock: Clock,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	/** 스케줄러가 부른다. 예외를 밖으로 던지지 않는다 */
	@Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
	fun scheduledRun() {
		try {
			run()
		} catch (e: Exception) {
			log.error("cleanup job failed", e)
		}
	}

	/** 모든 단계를 순서대로 한 번 돈다 (테스트가 직접 부른다). 단계의 실패는 로그만 남기고 다음 단계로 간다 */
	fun run() {
		step("1 guests") { cleanGuests() }
		step("2 spaces past purge_at") { repeatBatches { store.deleteSpacesPastPurge(clock.instant(), BATCH_SIZE) } }
		step("3 unreferenced withdrawn users") { repeatBatches { store.deleteUnreferencedWithdrawnUsers(BATCH_SIZE) } }
		step("4 invite codes and attempts") {
			val now = clock.instant()
			repeatBatches { store.deleteInviteCodeAttemptsBefore(now.minus(ATTEMPT_RETENTION), BATCH_SIZE) }
			repeatBatches { store.deleteEndedInviteCodesBefore(now.minus(INVITE_CODE_RETENTION), BATCH_SIZE) }
		}
		step("5 sessions") { repeatBatches { store.deleteDeadSessions(clock.instant(), BATCH_SIZE) } }
		step("6 devices without a valid session") { repeatBatches { store.deleteDevicesWithoutValidSession(clock.instant(), BATCH_SIZE) } }
		step("7 notifications older than 30 days") {
			repeatBatches { store.deleteNotificationsUpTo(clock.instant().minus(NOTIFICATION_RETENTION), BATCH_SIZE) }
		}
		// 8. 프로필 사진 파일 정리 (NFR-FILE-01, ERD 4.4 마지막 항목): 단위 7에서 사진 저장소(`storage/`)와 함께 여기에 한 단계로 더한다.
	}

	/** 게스트마다 따로 트랜잭션이다. 이미 지워졌거나 그 사이 세션이 생긴 사용자는 건너뛰고, 실패한 사용자는 로그만 남긴다 */
	private fun cleanGuests() {
		val now = clock.instant()
		var after = NIL_UUID
		while (true) {
			val ids = store.guestIdsWithoutValidSession(now, after, BATCH_SIZE)
			if (ids.isEmpty()) return
			ids.forEach { userId ->
				try {
					store.eraseGuest(userId, now)
				} catch (e: ApiException) {
					// 그 사이 다른 흐름이 먼저 지웠다 (이미 탈퇴함)
					log.info("guest {} was already withdrawn ({})", userId, e.code)
				} catch (e: Exception) {
					log.error("guest cleanup for user {} failed", userId, e)
				}
			}
			after = ids.last()
		}
	}

	/** [deleteBatch]가 한 묶음보다 적게 지울 때까지 되풀이한다 */
	private fun repeatBatches(deleteBatch: () -> Int) {
		while (deleteBatch() >= BATCH_SIZE) {
			// 다음 묶음
		}
	}

	private fun step(name: String, block: () -> Unit) {
		try {
			block()
		} catch (e: Exception) {
			log.error("cleanup step {} failed; continuing with the next step", name, e)
		}
	}

	private companion object {
		const val BATCH_SIZE = 500
		val NIL_UUID: UUID = UUID(0L, 0L)
		val ATTEMPT_RETENTION: Duration = Duration.ofDays(7)
		val INVITE_CODE_RETENTION: Duration = Duration.ofDays(30)
		val NOTIFICATION_RETENTION: Duration = Duration.ofDays(30)
	}
}
