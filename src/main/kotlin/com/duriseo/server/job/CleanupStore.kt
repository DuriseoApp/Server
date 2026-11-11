package com.duriseo.server.job

import com.duriseo.server.user.application.WithdrawalService
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 매일 정리 작업(ERD 4.4)의 SQL. 메서드 하나가 트랜잭션 하나다(부르는 쪽 [CleanupJob]은 트랜잭션 밖이다). 지우는 메서드는 한 번에 [limit]행까지만 지우고
 * 지운 행 수를 돌려준다. 부르는 쪽이 [limit]보다 적게 지울 때까지 되풀이한다(긴 트랜잭션·큰 잠금을 피한다).
 *
 * 다른 도메인 테이블(users, spaces, invite_codes, auth_sessions, devices, notifications)을 지우기만 하므로 `push/PushStore`처럼 네이티브 쿼리로 다룬다.
 * 지우는 조건은 모두 SQL 안에 있어, 조회와 삭제 사이에 상태가 바뀌어도(예: 복구로 `purge_at`이 비워짐) 바뀐 행을 지우지 않는다.
 * 사용자 행을 잠그는 것은 게스트 정리의 `WithdrawalService.eraseInTransaction`뿐이고 그 안에서 사용자 → 공간 순서를 지킨다.
 */
@Component
class CleanupStore(
	private val em: EntityManager,
	private val withdrawalService: WithdrawalService,
) {

	// ── 1. 게스트 정리 ─────────────────────────────────

	/** 유효한 세션이 없는 게스트(`deleted_at IS NULL`)의 ID를 [after] 다음부터 ID 순으로 [limit]개 읽는다 */
	@Transactional(readOnly = true)
	fun guestIdsWithoutValidSession(now: Instant, after: UUID, limit: Int): List<UUID> =
		em.createNativeQuery(
			"select u.id from users u where u.account_type = 'GUEST' and u.deleted_at is null and u.id > :after " +
				"and not exists ($VALID_SESSION_OF_USER) order by u.id limit :limit",
		)
			.setParameter("now", now)
			.setParameter("after", after)
			.setParameter("limit", limit)
			.resultList
			.map { it as UUID }

	/**
	 * 게스트 한 명을 탈퇴와 같이 처리한다 (`WithdrawalService.eraseInTransaction`, Kakao 연결 끊기 없음). 그 사이 유효한 세션이 생겼거나
	 * 이미 지워졌으면 아무것도 하지 않는다. 처리했으면 true. 사용자마다 따로 트랜잭션이다.
	 */
	@Transactional
	fun eraseGuest(userId: UUID, now: Instant): Boolean {
		// 사용자 행을 잠근 뒤(users → … 순서, `eraseInTransaction`이 같은 잠금을 다시 잡는다) 대상인지 다시 확인한다.
		// 잠그기 전의 확인은 그 사이 로그인이 끝난 게스트를 지울 수 있다
		val locked = em.createNativeQuery("select u.id from users u where u.id = :userId and u.account_type = 'GUEST' and u.deleted_at is null for no key update")
			.setParameter("userId", userId)
			.resultList
		if (locked.isEmpty()) return false
		val stillTarget = (em.createNativeQuery(
			"select count(*) from users u where u.id = :userId and u.account_type = 'GUEST' and u.deleted_at is null " +
				"and not exists ($VALID_SESSION_OF_USER)",
		)
			.setParameter("userId", userId)
			.setParameter("now", now)
			.singleResult as Number).toLong() > 0
		if (!stillTarget) return false
		withdrawalService.eraseInTransaction(userId, unlinkFromKakao = false)
		return true
	}

	// ── 2. purge_at 지난 공간 ──────────────────────────

	/** `purge_at`이 지난 공간을 지운다. 소속·콘텐츠·공간 알림은 DB가 CASCADE로 지운다 (ERD 4.4, 4.5) */
	@Transactional
	fun deleteSpacesPastPurge(now: Instant, limit: Int): Int = update(
		"delete from spaces where purge_at is not null and purge_at <= :now and id in " +
			"(select s.id from spaces s where s.purge_at is not null and s.purge_at <= :now order by s.purge_at, s.id limit :limit)",
		"now" to now, "limit" to limit,
	)

	// ── 3. 참조가 없는 탈퇴 사용자 ──────────────────────

	/**
	 * `deleted_at`이 있고 어떤 공간에서도 참조되지 않는 사용자 행을 지운다. 보관 중인 공간의 소속(`space_members`, 떠난 소속 포함)과
	 * 콘텐츠 작성자·수정자·고정한 사람(`ON DELETE RESTRICT`)이 남아 있으면 지우지 않는다.
	 */
	@Transactional
	fun deleteUnreferencedWithdrawnUsers(limit: Int): Int = update(
		"delete from users where deleted_at is not null and id in (" +
			"select u.id from users u where u.deleted_at is not null " +
			"and not exists (select 1 from space_members sm where sm.user_id = u.id) " +
			"and not exists (select 1 from events e where e.author_id = u.id or e.updated_by = u.id) " +
			"and not exists (select 1 from anniversaries a where a.author_id = u.id) " +
			"and not exists (select 1 from memos m where m.author_id = u.id or m.pinned_by = u.id) " +
			"order by u.id limit :limit)",
		"limit" to limit,
	)

	// ── 4. 초대코드 ────────────────────────────────────

	@Transactional
	fun deleteInviteCodeAttemptsBefore(cutoff: Instant, limit: Int): Int = update(
		"delete from invite_code_attempts where id in (select a.id from invite_code_attempts a where a.attempted_at < :cutoff limit :limit)",
		"cutoff" to cutoff, "limit" to limit,
	)

	/**
	 * 사용·만료·무효가 된 지 [cutoff] 전인 초대코드를 지운다. 코드가 끝난 때는 사용 시각, 무효 시각, 만료 시각 중 가장 이른 것이다.
	 * 유효한 코드(아직 안 쓰였고 무효가 아니며 만료 전)는 끝난 때가 미래라 지우지 않는다.
	 */
	@Transactional
	fun deleteEndedInviteCodesBefore(cutoff: Instant, limit: Int): Int = update(
		"delete from invite_codes where id in (select c.id from invite_codes c where " +
			"least(coalesce(c.used_at, cast('infinity' as timestamptz)), coalesce(c.revoked_at, cast('infinity' as timestamptz)), c.expires_at) < :cutoff " +
			"limit :limit)",
		"cutoff" to cutoff, "limit" to limit,
	)

	// ── 5. 세션 ────────────────────────────────────────

	/** 만료된 세션과 무효화된 세션을 지운다. `REPLACED`는 `expires_at`이 지날 때까지 남긴다 (ERD DB-11) */
	@Transactional
	fun deleteDeadSessions(now: Instant, limit: Int): Int = update(
		"delete from auth_sessions where id in (select s.id from auth_sessions s where s.expires_at <= :now " +
			"or (s.revoked_at is not null and coalesce(s.revoked_reason, '') <> 'REPLACED') limit :limit)",
		"now" to now, "limit" to limit,
	)

	// ── 6. 기기 ────────────────────────────────────────

	/** 유효한 세션이 하나도 없는 사용자의 `devices`를 지운다 (ERD DB-14). 5단계 뒤에 불러야 방금 만료돼 지워진 세션의 기기도 함께 지워진다 */
	@Transactional
	fun deleteDevicesWithoutValidSession(now: Instant, limit: Int): Int = update(
		"delete from devices where id in (select d.id from devices d where not exists " +
			"(select 1 from auth_sessions s where s.user_id = d.user_id and s.revoked_at is null and s.expires_at > :now) limit :limit) " +
			// 고른 뒤 로그인이 새 세션을 만들었으면 지우지 않는다: 바깥 DELETE에서도 같은 조건을 다시 본다
			"and not exists (select 1 from auth_sessions s2 where s2.user_id = devices.user_id and s2.revoked_at is null and s2.expires_at > :now)",
		"now" to now, "limit" to limit,
	)

	// ── 7. 알림 ────────────────────────────────────────

	/** `created_at`이 [cutoff](= now − 30일) 이전인 알림을 지운다. 조회의 30일 경계(`created_at > cutoff`)와 같은 기준이다 (D-141, DB-19) */
	@Transactional
	fun deleteNotificationsUpTo(cutoff: Instant, limit: Int): Int = update(
		"delete from notifications where id in (select n.id from notifications n where n.created_at <= :cutoff limit :limit)",
		"cutoff" to cutoff, "limit" to limit,
	)

	private fun update(sql: String, vararg params: Pair<String, Any>): Int {
		val query = em.createNativeQuery(sql)
		params.forEach { (name, value) -> query.setParameter(name, value) }
		return query.executeUpdate()
	}

	private companion object {
		/** `:now` 기준으로 그 사용자에게 유효한 세션이 있는지 (`revoked_at IS NULL AND expires_at > now`, ERD 4.4) */
		const val VALID_SESSION_OF_USER =
			"select 1 from auth_sessions s where s.user_id = u.id and s.revoked_at is null and s.expires_at > :now"
	}
}
