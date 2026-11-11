package com.duriseo.server.cleanup

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.job.CleanupJob
import com.duriseo.server.support.NotificationTestSupport
import com.duriseo.server.support.TestClockConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/**
 * 매일 정리 작업 (ERD 4.4, API.md 7장, DB-10·DB-11·DB-14·DB-19). 스케줄러는 꺼져 있고(`duriseo.jobs.enabled=false`) 시계를 옮기며 작업 메서드를 직접 부른다.
 * 시계는 `2026-10-26T00:00:00Z`로 시작한다. 시계를 1시간 넘게 옮기면 access 토큰이 만료되므로 HTTP 요청은 옮기기 전에 끝낸다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class CleanupJobTest : NotificationTestSupport() {

	@Autowired
	private lateinit var job: CleanupJob

	private fun daysAgo(days: Long): Instant = clock.instant().minus(Duration.ofDays(days))

	private fun userExists(id: UUID) = count("select count(*) from users where id = ?", id) == 1

	private fun spaceExists(id: UUID) = count("select count(*) from spaces where id = ?", id) == 1

	/** 세션 행 하나를 마음대로 넣는다. [id]를 돌려준다 */
	private fun insertSession(
		userId: UUID,
		expiresAt: Instant,
		revokedAt: Instant? = null,
		reason: String? = null,
	): UUID {
		val id = UUID.randomUUID()
		jdbc.update(
			"insert into auth_sessions (id, user_id, refresh_token_hash, expires_at, last_used_at, revoked_at, revoked_reason, created_at) " +
				"values (?, ?, ?, ?, ?, ?, ?, ?)",
			id, userId, sha256Hex(id.toString()), ts(expiresAt), ts(clock.instant()), revokedAt?.let { ts(it) }, reason, ts(clock.instant()),
		)
		return id
	}

	private fun sessionExists(id: UUID) = count("select count(*) from auth_sessions where id = ?", id) == 1

	private fun insertDevice(userId: UUID, token: String): UUID {
		val id = UUID.randomUUID()
		jdbc.update("insert into devices (id, user_id, fcm_token, updated_at) values (?, ?, ?, ?)", id, userId, token, ts(clock.instant()))
		return id
	}

	private fun deviceExists(id: UUID) = count("select count(*) from devices where id = ?", id) == 1

	/** 초대코드 한 건 (사용자당 유효한 코드는 하나라 만든 사람을 코드마다 새로 만든다) */
	private fun insertInviteCode(
		code: String,
		expiresAt: Instant,
		usedAt: Instant? = null,
		revokedAt: Instant? = null,
	): UUID {
		val id = UUID.randomUUID()
		jdbc.update(
			"insert into invite_codes (id, code, creator_user_id, expires_at, used_at, revoked_at, created_at) values (?, ?, ?, ?, ?, ?, ?)",
			id, code, insertUser("MEMBER"), ts(expiresAt), usedAt?.let { ts(it) }, revokedAt?.let { ts(it) }, ts(clock.instant()),
		)
		return id
	}

	private fun inviteCodeExists(id: UUID) = count("select count(*) from invite_codes where id = ?", id) == 1

	@Test
	fun `ERD 4_4 유효 세션이 없는 게스트는 탈퇴처럼 정리된다`() {
		val stale = startGuest()
		val fresh = startGuest()
		registerDevice(stale, TOKEN_A)
		val staleSpace = jdbc.queryForObject("select space_id from space_members where user_id = ?", UUID::class.java, stale.userId)!!
		val freshSpace = jdbc.queryForObject("select space_id from space_members where user_id = ?", UUID::class.java, fresh.userId)!!
		insertEvent(staleSpace, stale.userId)
		// 마지막 사용 뒤 365일이 지난 게스트(세션 만료)만 대상이다. 세션 행은 아직 남아 있다 (세션 정리보다 먼저 돈다, DB-10)
		jdbc.update("update auth_sessions set expires_at = ? where user_id = ?", ts(daysAgo(1)), stale.userId)

		job.run()

		assertEquals(false, spaceExists(staleSpace))
		assertEquals(0, count("select count(*) from events where space_id = ?", staleSpace))
		assertEquals(0, count("select count(*) from devices where user_id = ?", stale.userId))
		assertEquals(0, count("select count(*) from auth_sessions where user_id = ?", stale.userId))
		// 탈퇴처럼 익명화한 뒤 참조가 없어져 같은 실행의 3단계가 사용자 행까지 지운다
		assertEquals(false, userExists(stale.userId))
		// 유효한 세션이 있는 게스트는 그대로다
		assertEquals(true, userExists(fresh.userId))
		assertEquals(true, spaceExists(freshSpace))
		assertEquals(1, count("select count(*) from auth_sessions where user_id = ?", fresh.userId))
	}

	@Test
	fun `ERD 4_4 회원은 세션이 없어도 게스트 정리 대상이 아니다`() {
		val member = startMember("회원")
		jdbc.update("update auth_sessions set expires_at = ? where user_id = ?", ts(daysAgo(1)), member.userId)

		job.run()

		// 세션은 지워지지만 사용자와 공간은 남는다
		assertEquals(true, userExists(member.userId))
		assertEquals(0, count("select count(*) from auth_sessions where user_id = ?", member.userId))
		assertEquals(1, count("select count(*) from space_members where user_id = ?", member.userId))
	}

	@Test
	fun `ERD 4_4 purge_at이 지난 공간을 지운다`() {
		val a = insertUser("MEMBER")
		val b = insertUser("MEMBER")
		val expired = insertSpace(unlinked = true)
		val pending = insertSpace(unlinked = true)
		listOf(expired, pending).forEach { space ->
			insertMember(space, a, left = true)
			insertMember(space, b, left = true)
			insertEvent(space, a)
			insertMemo(space, b)
		}
		jdbc.update("update spaces set purge_at = ? where id = ?", ts(daysAgo(1)), expired)
		val active = insertSpace(type = "COUPLE")

		job.run()

		assertEquals(false, spaceExists(expired))
		assertEquals(0, count("select count(*) from events where space_id = ?", expired))
		assertEquals(0, count("select count(*) from memos where space_id = ?", expired))
		assertEquals(0, count("select count(*) from space_members where space_id = ?", expired))
		assertEquals(true, spaceExists(pending))
		assertEquals(1, count("select count(*) from events where space_id = ?", pending))
		assertEquals(true, spaceExists(active))
	}

	@Test
	fun `ERD 4_4 참조가 없는 탈퇴 사용자 행을 지운다`() {
		val alone = insertUser("MEMBER", deleted = true)
		val keptByRetainedSpace = insertUser("MEMBER", deleted = true)
		val removedWithSpace = insertUser("MEMBER", deleted = true)
		val active = insertUser("MEMBER")
		val retained = insertSpace(unlinked = true)
		insertMember(retained, keptByRetainedSpace, left = true)
		insertMember(retained, active, left = true)
		insertEvent(retained, keptByRetainedSpace)
		// 이 공간은 purge_at이 지나 2단계에서 지워지고, 같은 실행의 3단계가 그 뒤 참조가 없어진 사용자 행을 지운다
		val purged = insertSpace(unlinked = true)
		insertMember(purged, removedWithSpace, left = true)
		insertEvent(purged, removedWithSpace)
		jdbc.update("update spaces set purge_at = ? where id = ?", ts(daysAgo(1)), purged)

		job.run()

		assertEquals(false, userExists(alone))
		assertEquals(false, userExists(removedWithSpace))
		// 보관 중인 공간의 소속·작성자로 참조되는 사용자와 탈퇴하지 않은 사용자는 남는다
		assertEquals(true, userExists(keptByRetainedSpace))
		assertEquals(true, userExists(active))
		assertEquals(1, count("select count(*) from space_members where space_id = ?", retained))
	}

	@Test
	fun `ERD 4_4 오래된 초대코드와 시도 기록을 지운다`() {
		val user = insertUser("MEMBER")
		val oldAttempt = UUID.randomUUID()
		val recentAttempt = UUID.randomUUID()
		jdbc.update("insert into invite_code_attempts (id, user_id, succeeded, attempted_at) values (?, ?, false, ?)", oldAttempt, user, ts(daysAgo(8)))
		jdbc.update("insert into invite_code_attempts (id, user_id, succeeded, attempted_at) values (?, ?, false, ?)", recentAttempt, user, ts(daysAgo(6)))

		val used31 = insertInviteCode("AAAAA1", expiresAt = daysAgo(31).plusSeconds(600), usedAt = daysAgo(31))
		val used29 = insertInviteCode("AAAAA2", expiresAt = daysAgo(29).plusSeconds(600), usedAt = daysAgo(29))
		val expired31 = insertInviteCode("AAAAA3", expiresAt = daysAgo(31))
		val expired29 = insertInviteCode("AAAAA4", expiresAt = daysAgo(29))
		val revoked31 = insertInviteCode("AAAAA5", expiresAt = clock.instant().plus(Duration.ofDays(1)), revokedAt = daysAgo(31))
		val revoked29 = insertInviteCode("AAAAA6", expiresAt = clock.instant().plus(Duration.ofDays(1)), revokedAt = daysAgo(29))
		// 30일 전에 사용됐지만 만료가 아직 먼 코드도 사용된 지 30일이 지났으면 지운다
		val usedEarlyLongExpiry = insertInviteCode("AAAAA7", expiresAt = clock.instant().plus(Duration.ofDays(5)), usedAt = daysAgo(40))
		val valid = insertInviteCode("AAAAA8", expiresAt = clock.instant().plus(Duration.ofMinutes(30)))

		job.run()

		assertEquals(0, count("select count(*) from invite_code_attempts where id = ?", oldAttempt))
		assertEquals(1, count("select count(*) from invite_code_attempts where id = ?", recentAttempt))
		listOf(used31, expired31, revoked31, usedEarlyLongExpiry).forEach { assertEquals(false, inviteCodeExists(it)) }
		listOf(used29, expired29, revoked29, valid).forEach { assertEquals(true, inviteCodeExists(it)) }
	}

	@Test
	fun `ERD 4_4 REPLACED 세션은 만료 전까지 남긴다`() {
		val user = insertUser("MEMBER")
		val now = clock.instant()
		val replacedLive = insertSession(user, now.plus(Duration.ofDays(3)), revokedAt = now.minusSeconds(60), reason = "REPLACED")
		val replacedExpired = insertSession(user, now.minusSeconds(1), revokedAt = now.minus(Duration.ofDays(40)), reason = "REPLACED")
		val loggedOut = insertSession(user, now.plus(Duration.ofDays(3)), revokedAt = now.minusSeconds(60), reason = "LOGOUT")
		val withdrawn = insertSession(user, now.plus(Duration.ofDays(3)), revokedAt = now.minusSeconds(60), reason = "WITHDRAWN")
		val expired = insertSession(user, now.minusSeconds(1))
		val valid = insertSession(user, now.plus(Duration.ofDays(3)))

		job.run()

		assertEquals(true, sessionExists(replacedLive))
		assertEquals(true, sessionExists(valid))
		listOf(replacedExpired, loggedOut, withdrawn, expired).forEach { assertEquals(false, sessionExists(it)) }
	}

	@Test
	fun `ERD 4_4 세션이 없는 사용자의 기기를 지운다`() {
		val now = clock.instant()
		val noSessions = insertUser("MEMBER")
		val justExpired = insertUser("MEMBER")
		val onlyReplaced = insertUser("MEMBER")
		val withValid = insertUser("MEMBER")
		insertSession(justExpired, now.minusSeconds(1))
		insertSession(onlyReplaced, now.plus(Duration.ofDays(3)), revokedAt = now.minusSeconds(60), reason = "REPLACED")
		insertSession(withValid, now.plus(Duration.ofDays(3)))
		val d1 = insertDevice(noSessions, "token-1-fake")
		// 방금 만료돼 같은 실행의 5단계에서 지워진 세션의 기기도 6단계에서 지워진다 (DB-14)
		val d2 = insertDevice(justExpired, "token-2-fake")
		// 무효화된(REPLACED) 세션은 유효가 아니다
		val d3 = insertDevice(onlyReplaced, "token-3-fake")
		val d4 = insertDevice(withValid, "token-4-fake")

		job.run()

		listOf(d1, d2, d3).forEach { assertEquals(false, deviceExists(it)) }
		assertEquals(true, deviceExists(d4))
	}

	@Test
	fun `ERD 4_4 30일 지난 알림을 지운다`() {
		val user = insertUser("MEMBER")
		val old = insertNotificationRow(user, createdAt = daysAgo(31))
		// 조회의 경계는 created_at > now − 30일이라 정확히 30일 된 알림은 이미 보이지 않는다. 같은 기준으로 지운다
		val exactly30 = insertNotificationRow(user, createdAt = daysAgo(30))
		val recent = insertNotificationRow(user, createdAt = daysAgo(29))

		job.run()

		assertEquals(0, count("select count(*) from notifications where id in (?, ?)", old, exactly30))
		assertEquals(1, count("select count(*) from notifications where id = ?", recent))
	}

	@Test
	fun `ERD 4_4 한 단계가 실패해도 다음 단계는 돈다`() {
		val user = insertUser("MEMBER")
		val expiredSpace = insertSpace(unlinked = true)
		insertMember(expiredSpace, user, left = true)
		jdbc.update("update spaces set purge_at = ? where id = ?", ts(daysAgo(1)), expiredSpace)
		val oldNotification = insertNotificationRow(user, createdAt = daysAgo(40))
		val expiredSession = insertSession(user, clock.instant().minusSeconds(1))
		// 2단계(공간 삭제)를 강제로 실패시킨다
		jdbc.execute("create function test_fail_space_delete() returns trigger language plpgsql as 'begin raise exception ''blocked''; end'")
		jdbc.execute("create trigger test_fail_space_delete before delete on spaces for each row execute function test_fail_space_delete()")
		try {
			job.run()
		} finally {
			jdbc.execute("drop trigger test_fail_space_delete on spaces")
			jdbc.execute("drop function test_fail_space_delete()")
		}

		assertEquals(true, spaceExists(expiredSpace))
		assertEquals(0, count("select count(*) from notifications where id = ?", oldNotification))
		assertEquals(false, sessionExists(expiredSession))
	}
}
