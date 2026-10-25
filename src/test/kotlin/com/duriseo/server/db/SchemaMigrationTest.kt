package com.duriseo.server.db

import com.duriseo.server.TestcontainersConfig
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Flyway V1이 실제 PostgreSQL 16에 적용되고, ERD 3장의 "제약" 열이 DB에서 지켜지는지 확인한다.
 * "서비스"로 표시된 규칙은 여기서 보지 않는다 (각 작업 단위의 서비스 테스트가 본다).
 */
@SpringBootTest
@Import(TestcontainersConfig::class)
class SchemaMigrationTest {

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	@BeforeEach
	fun clean() {
		jdbc.execute("truncate table ${TABLES.joinToString()} cascade")
	}

	@Test
	fun `V1이 적용되어 ERD의 15개 테이블이 있다`() {
		val version = jdbc.queryForObject(
			"select max(version) from flyway_schema_history where success",
			String::class.java,
		)
		assertEquals("1", version)

		val tables = jdbc.queryForList(
			"""
			select table_name from information_schema.tables
			where table_schema = 'public' and table_name <> 'flyway_schema_history'
			""".trimIndent(),
			String::class.java,
		).toSet()
		assertEquals(TABLES.toSet(), tables)
	}

	@Test
	fun `사용자는 활성 공간 하나에만 속한다`() {
		val user = user()
		val personal = space("PERSONAL")
		val couple = space("COUPLE")
		val first = member(personal, user)

		assertViolates("uq_space_members_active_user") { member(couple, user) }

		// 개인 공간 소속에 left_at을 기록하면 커플 공간 소속을 만들 수 있다 (ERD 4.1)
		jdbc.update("update space_members set left_at = now() where id = ?", first)
		member(couple, user)
	}

	@Test
	fun `사용자당 유효한 초대코드는 하나`() {
		val user = user()
		val first = inviteCode(user, "ABC234")

		assertViolates("uq_invite_codes_active_creator") { inviteCode(user, "XYZ789") }

		// 재요청: 이전 코드의 revoked_at을 기록하면 새 코드를 만들 수 있다
		jdbc.update("update invite_codes set revoked_at = now() where id = ?", first)
		inviteCode(user, "XYZ789")
		assertViolates("uq_invite_codes_code") { inviteCode(user(), "XYZ789") }
	}

	@Test
	fun `한 소셜 계정은 한 사용자에게만 연결된다`() {
		socialAccount(user(), "GOOGLE", "g-1")

		assertViolates("uq_social_accounts_provider_user") { socialAccount(user(), "GOOGLE", "g-1") }
	}

	@Test
	fun `커플 전용 컬럼과 해제 상태 컬럼은 함께 움직인다`() {
		assertViolates("ck_spaces_couple_only_columns") {
			jdbc.update("insert into spaces (id, type, start_date) values (?, 'PERSONAL', date '2024-01-01')", uuid())
		}
		assertViolates("ck_spaces_unlinked_columns") {
			jdbc.update("insert into spaces (id, type, status, unlinked_at) values (?, 'COUPLE', 'UNLINKED', now())", uuid())
		}
		jdbc.update(
			"""
			insert into spaces (id, type, status, unlinked_at, purge_at)
			values (?, 'COUPLE', 'UNLINKED', now(), now() + interval '30 days')
			""".trimIndent(),
			uuid(),
		)
	}

	@Test
	fun `종일 일정은 날짜만, 시간 일정은 일시만 가진다`() {
		val user = user()
		val space = space("PERSONAL")

		// 정상: 종일, 시간
		event(space, user, allDay = true, startDate = "2026-01-01", endDate = "2026-01-02")
		event(space, user, allDay = false, startAt = "2026-01-01T09:00:00Z", endAt = "2026-01-01T10:00:00Z")

		assertViolates("ck_events_time_columns") {
			event(space, user, allDay = true, startDate = "2026-01-01", endDate = "2026-01-01", startAt = "2026-01-01T09:00:00Z")
		}
		assertViolates("ck_events_time_columns") {
			event(space, user, allDay = true, startDate = "2026-01-02", endDate = "2026-01-01")
		}
		assertViolates("ck_events_time_columns") {
			event(space, user, allDay = false, startAt = "2026-01-01T10:00:00Z", endAt = "2026-01-01T09:00:00Z")
		}
	}

	@Test
	fun `열거형은 정해진 값만 받는다`() {
		assertViolates("ck_users_account_type") {
			jdbc.update("insert into users (id, account_type, nickname) values (?, 'ADMIN', 'x')", uuid())
		}
		assertViolates("ck_auth_sessions_revoked_reason") {
			jdbc.update(
				"""
				insert into auth_sessions (id, user_id, refresh_token_hash, expires_at, last_used_at, revoked_at, revoked_reason)
				values (?, ?, ?, now(), now(), now(), 'EXPIRED')
				""".trimIndent(),
				uuid(), user(), "a".repeat(64),
			)
		}
	}

	@Test
	fun `콘텐츠 작성자는 지울 수 없고 계정 행은 사용자와 함께 지워진다`() {
		val author = user()
		val space = space("PERSONAL")
		socialAccount(author, "KAKAO", "k-1")
		jdbc.update("insert into memos (id, space_id, author_id, title) values (?, ?, ?, '메모')", uuid(), space, author)

		// 콘텐츠 사용자 참조는 RESTRICT (ERD 3.3)
		assertFailsWith<DataIntegrityViolationException> {
			jdbc.update("delete from users where id = ?", author)
		}

		// 공간이 지워져 콘텐츠가 사라지면 사용자를 지울 수 있고, 소셜 계정은 CASCADE로 지워진다
		jdbc.update("delete from spaces where id = ?", space)
		jdbc.update("delete from users where id = ?", author)
		assertEquals(0, jdbc.queryForObject("select count(*) from social_accounts", Int::class.java))
	}

	// ── 도우미 ──

	private fun uuid(): UUID = UUID.randomUUID()

	private fun user(): UUID = uuid().also {
		jdbc.update("insert into users (id, account_type, nickname) values (?, 'GUEST', '게스트')", it)
	}

	private fun space(type: String): UUID = uuid().also {
		jdbc.update("insert into spaces (id, type) values (?, ?)", it, type)
	}

	private fun member(space: UUID, user: UUID): UUID = uuid().also {
		jdbc.update(
			"insert into space_members (id, space_id, user_id, color, joined_at) values (?, ?, ?, 'pick-orange', now())",
			it, space, user,
		)
	}

	private fun inviteCode(creator: UUID, code: String): UUID = uuid().also {
		jdbc.update(
			"insert into invite_codes (id, code, creator_user_id, expires_at) values (?, ?, ?, now() + interval '10 minutes')",
			it, code, creator,
		)
	}

	private fun socialAccount(user: UUID, provider: String, providerUserId: String) {
		jdbc.update(
			"insert into social_accounts (id, user_id, provider, provider_user_id) values (?, ?, ?, ?)",
			uuid(), user, provider, providerUserId,
		)
	}

	private fun event(
		space: UUID,
		author: UUID,
		allDay: Boolean,
		startDate: String? = null,
		endDate: String? = null,
		startAt: String? = null,
		endAt: String? = null,
	) {
		jdbc.update(
			"""
			insert into events (id, space_id, author_id, title, is_all_day, start_date, end_date, start_at, end_at)
			values (?, ?, ?, '일정', ?, cast(? as date), cast(? as date), cast(? as timestamptz), cast(? as timestamptz))
			""".trimIndent(),
			uuid(), space, author, allDay, startDate, endDate, startAt, endAt,
		)
	}

	private fun assertViolates(constraint: String, block: () -> Unit) {
		val e = assertFailsWith<DataIntegrityViolationException> { block() }
		val message = e.mostSpecificCause.message.orEmpty()
		assertTrue(constraint in message, "제약 $constraint 위반이어야 하는데: $message")
	}

	companion object {
		/** ERD 2장의 테이블 (삭제된 memo_checklist_items 제외) */
		val TABLES = listOf(
			"users", "social_accounts", "auth_sessions", "devices", "notification_settings", "notifications",
			"spaces", "space_members", "invite_codes", "invite_code_attempts",
			"events", "event_reminders", "anniversaries", "memos", "memo_favorites",
		)
	}
}
