package com.duriseo.server.job

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.config.DuriseoProperties
import com.duriseo.server.config.DuriseoProperties.StorageType
import com.duriseo.server.storage.ProfileImageKeys
import com.duriseo.server.storage.ProfileImageStorage
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
 * 6. 세션 없는 사용자의 기기 — 7. 30일 지난 알림 — 8. 프로필 사진 파일 정리 (저장소 목록 중 `users.profile_image_key`에 없고 마지막 수정이 24시간 지난 객체만 삭제, NFR-FILE-01)
 *
 * 단계마다 따로 트랜잭션이다([CleanupStore]의 메서드 하나가 하나). 한 단계가 실패해도 로그만 남기고 다음 단계로 간다.
 * 게스트 정리는 사용자마다 따로 트랜잭션이라 한 사용자의 실패가 다른 사용자를 막지 않는다.
 */
@Component
class CleanupJob(
	private val store: CleanupStore,
	private val profileImages: ProfileImageStorage,
	private val properties: DuriseoProperties,
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
		step("8 profile image files") { cleanProfileImages() }
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

	/**
	 * 저장소 목록에서 DB가 가리키지 않고 마지막 수정이 24시간 지난 사진 파일만 지운다 (ERD 4.4, API.md 8장). 교체·삭제·탈퇴가 커밋 뒤 지우지 못한 파일을 회수한다.
	 * 참조 집합을 먼저 읽고 목록을 훑는다: 그 뒤 올라온 파일은 마지막 수정이 최근이라 24시간 조건이 지킨다(업로드는 저장 뒤 키를 갱신한다).
	 * 파일마다 실패는 로그만 남기고 다음으로 간다.
	 */
	private fun cleanProfileImages() {
		// 저장소가 S3면 prod 앱만 돈다: 다른 환경이 운영 버킷을 가리켜도 운영 사진을 지우지 않게 하는 보호 장치다
		if (properties.storage.type == StorageType.S3 && !properties.storage.cleanupEnabled) {
			log.info("profile image cleanup is skipped: duriseo.storage.cleanup-enabled is false for the S3 storage")
			return
		}
		val inUse = store.profileImageObjectKeysInUse()
		val cutoff = clock.instant().minus(PROFILE_IMAGE_GRACE)
		profileImages.list(ProfileImageKeys.PREFIX) { obj ->
			if (obj.key !in inUse && obj.lastModified.isBefore(cutoff)) {
				try {
					profileImages.delete(obj.key)
				} catch (e: Exception) {
					log.warn("unreferenced profile image {} was not deleted ({})", obj.key, e.javaClass.simpleName)
				}
			}
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
		val PROFILE_IMAGE_GRACE: Duration = Duration.ofHours(24)
	}
}
