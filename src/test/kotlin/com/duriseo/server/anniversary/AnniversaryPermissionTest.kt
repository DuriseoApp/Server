package com.duriseo.server.anniversary

import com.duriseo.server.TestcontainersConfig
import com.duriseo.server.anniversary.application.AnniversaryService
import com.duriseo.server.support.TestClockConfig
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertEquals

/**
 * 직접 기념일 권한 규칙 R-01·R-05를 `DELETE /anniversaries/{id}`로 확인한다 (FR-ANNIV-03 AC2, NFR-SEC-02).
 * 규칙마다 허용·거부 테스트를 둔다. `POST`·`PATCH`의 R-01·R-05·R-15는 `AnniversaryWriteTest`, `GET` 경로의 R-01·R-15는 `AnniversaryListTest`가 확인한다.
 */
@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
class AnniversaryPermissionTest : AnniversaryTestSupport() {

	@Autowired
	private lateinit var anniversaryService: AnniversaryService

	/** 응답 본문에서 오류 코드·메시지·details만 뽑는다 (`traceId`는 요청마다 다르다) */
	private fun errorShape(body: String): List<Any?> = listOf(
		JsonPath.read<String>(body, "$.code"),
		JsonPath.read<String>(body, "$.message"),
		JsonPath.read<Any?>(body, "$.details"),
	)

	// ── R-01 ────────────────────────────────────────────

	@Test
	fun `R-01 요청자의 공간 기념일은 작성자가 DELETE할 수 있다`() {
		val couple = newCouple()
		val id = insertAnniversary(couple.spaceId, couple.a.userId, "에이의 기념일")

		deleteAnniversary(couple.a.accessToken, id).andExpect(status().isNoContent).andExpect(content().string(""))

		assertEquals(0, count("select count(*) from anniversaries where id = ?", id))
	}

	@Test
	fun `R-01 개인 공간의 기념일도 작성자가 DELETE할 수 있다`() {
		val me = startMember("에이")
		val id = insertAnniversary(activeSpaceIdOfUser(me.userId), me.userId)

		deleteAnniversary(me.accessToken, id).andExpect(status().isNoContent)

		assertEquals(0, anniversaryCount())
	}

	@Test
	fun `R-01 다른 커플 공간의 기념일은 DELETE가 404 NOT_FOUND이고 아무것도 바뀌지 않는다`() {
		val couple = newCouple()
		val other = newCouple()
		val id = insertAnniversary(other.spaceId, other.a.userId, "다른 커플의 기념일")
		val before = anniversarySnapshot()

		deleteAnniversary(couple.a.accessToken, id).expectError(404, "NOT_FOUND")
		deleteAnniversary(couple.b.accessToken, id).expectError(404, "NOT_FOUND")

		assertEquals(before, anniversarySnapshot())
	}

	@Test
	fun `R-01 없는 기념일 ID와 다른 공간 기념일과 UUID 형식이 아닌 ID는 본문까지 같은 404다`() {
		val couple = newCouple()
		val other = newCouple()
		val foreignId = insertAnniversary(other.spaceId, other.a.userId)

		val missing = bodyOf(deleteAnniversary(couple.a.accessToken, UUID.randomUUID()).expectError(404, "NOT_FOUND"))
		val foreign = bodyOf(deleteAnniversary(couple.a.accessToken, foreignId).expectError(404, "NOT_FOUND"))
		val malformed = bodyOf(deleteAnniversary(couple.a.accessToken, "not-a-uuid").expectError(404, "NOT_FOUND"))

		assertEquals(errorShape(missing), errorShape(foreign))
		assertEquals(errorShape(missing), errorShape(malformed))
	}

	@Test
	fun `R-01 해제된 이전 커플 공간의 기념일은 DELETE가 404다`() {
		val couple = newCouple()
		val id = insertAnniversary(couple.spaceId, couple.a.userId)
		unlinkCouple(couple.a.accessToken).andExpect(status().isNoContent)

		// 해제 뒤 A의 활성 공간은 개인 공간이다
		deleteAnniversary(couple.a.accessToken, id).expectError(404, "NOT_FOUND")

		assertEquals(1, count("select count(*) from anniversaries where id = ?", id))
	}

	// ── R-05 ────────────────────────────────────────────

	@Test
	fun `R-05 작성자는 자기 기념일을 삭제할 수 있다`() {
		val couple = newCouple()
		val id = insertAnniversary(couple.spaceId, couple.b.userId, "비비의 기념일")

		deleteAnniversary(couple.b.accessToken, id).andExpect(status().isNoContent)

		assertEquals(0, count("select count(*) from anniversaries where id = ?", id))
	}

	@Test
	fun `FR-ANNIV-03 AC2 상대가 만든 기념일을 DELETE하면 403 NOT_AUTHOR이고 남는다`() {
		val couple = newCouple()
		val id = insertAnniversary(couple.spaceId, couple.a.userId, "에이의 기념일")
		val before = anniversarySnapshot()

		deleteAnniversary(couple.b.accessToken, id).expectError(403, "NOT_AUTHOR")

		assertEquals(1, count("select count(*) from anniversaries where id = ? and author_id = ?", id, couple.a.userId))
		assertEquals(before, anniversarySnapshot())
	}

	@Test
	fun `R-05 검사 순서 다른 공간의 기념일은 403이 아니라 404다`() {
		val couple = newCouple()
		val other = newCouple()
		val id = insertAnniversary(other.spaceId, other.a.userId)

		// 작성자가 아니고 같은 공간도 아니면 R-01의 404가 먼저다 (존재를 드러내지 않는다)
		val foreign = bodyOf(deleteAnniversary(couple.b.accessToken, id).expectError(404, "NOT_FOUND"))
		val missing = bodyOf(deleteAnniversary(couple.b.accessToken, UUID.randomUUID()).expectError(404, "NOT_FOUND"))

		assertEquals(errorShape(missing), errorShape(foreign))
	}

	@Test
	fun `R-05 거부되면 기념일이 하나도 바뀌지 않는다`() {
		val couple = newCouple()
		insertAnniversary(couple.spaceId, couple.a.userId, "에이의 기념일 1")
		val target = insertAnniversary(couple.spaceId, couple.a.userId, "에이의 기념일 2", date = "2027-03-01", repeatsYearly = false)
		insertAnniversary(couple.spaceId, couple.b.userId, "비비의 기념일")
		val before = anniversarySnapshot()

		deleteAnniversary(couple.b.accessToken, target).expectError(403, "NOT_AUTHOR")

		assertEquals(before, anniversarySnapshot())
		assertEquals(3, anniversaryCount())
	}

	@Test
	fun `FR-ANNIV-02 AC3 자동 기념일은 저장된 행이 없어 DELETE로 가리킬 수 없다`() {
		val couple = newCouple()
		setStartDate(couple.spaceId, "2026-01-01")

		// 자동 기념일에는 anniversaryId가 없다. 임의의 UUID는 없는 기념일과 같은 404다
		deleteAnniversary(couple.a.accessToken, UUID.randomUUID()).expectError(404, "NOT_FOUND")
		assertEquals(0, anniversaryCount())
	}

	// ── POST 사전 잠금 ───────────────────────────────────

	@Test
	fun `R-01 POST 쓰기 블록은 사용자 행을 잠근 뒤의 활성 공간을 받는다`() {
		val me = startMember("에이")
		val personal = activeSpaceIdOfUser(me.userId)

		val seen = anniversaryService.inLockedActiveSpace(me.userId) { it }

		assertEquals(personal, seen)

		// 커플로 연결되면 활성 공간이 바뀐다
		val partner = startMember("비비")
		val coupleSpace = connect(me, partner)
		assertEquals(coupleSpace, anniversaryService.inLockedActiveSpace(me.userId) { it })
	}
}
