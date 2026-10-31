package com.duriseo.server.user

import com.duriseo.server.user.application.PartnerChangedException
import com.duriseo.server.user.application.PartnerLockRetry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 연결 해제·탈퇴·웹훅이 사용자와 상대를 잠그다가 상대가 바뀌었을 때의 재시도 규칙 (FR-COUPLE-05, FR-AUTH-06, 잠금 순서 users → spaces).
 * 실제로 상대가 바뀌는 경쟁은 재현하기 어려워 도우미의 규칙만 확인한다.
 */
class PartnerLockRetryTest {

	@Test
	fun `FR-COUPLE-05 상대가 바뀌지 않으면 한 번만 실행한다`() {
		var attempts = 0

		val result = PartnerLockRetry.run { attempts++; "done" }

		assertEquals("done", result)
		assertEquals(1, attempts)
	}

	@Test
	fun `FR-COUPLE-05 상대가 바뀌면 처음부터 다시 실행하고 성공하면 그 결과를 돌려준다`() {
		var attempts = 0

		val result = PartnerLockRetry.run {
			attempts++
			if (attempts < 3) throw PartnerChangedException()
			"done"
		}

		assertEquals("done", result)
		assertEquals(3, attempts)
	}

	@Test
	fun `FR-COUPLE-05 재시도를 다 쓴 마지막 시도는 더 던지지 않도록 canRetry가 false다`() {
		val canRetryPerAttempt = mutableListOf<Boolean>()

		PartnerLockRetry.run {
			canRetryPerAttempt.add(PartnerLockRetry.canRetry())
			if (PartnerLockRetry.canRetry()) throw PartnerChangedException()
		}

		// 처음 시도 + 재시도 3번. 마지막 시도만 기존 동작(새 상대를 이어서 잠금)을 한다
		assertEquals(listOf(true, true, true, false), canRetryPerAttempt)
	}

	@Test
	fun `FR-COUPLE-05 다른 예외는 다시 시도하지 않고 그대로 던진다`() {
		var attempts = 0

		assertFailsWith<IllegalStateException> { PartnerLockRetry.run { attempts++; throw IllegalStateException("boom") } }

		assertEquals(1, attempts)
	}

	@Test
	fun `FR-COUPLE-05 도우미 밖에서는 canRetry가 false이고 끝난 뒤에도 남지 않는다`() {
		assertFalse(PartnerLockRetry.canRetry())

		PartnerLockRetry.run { assertTrue(PartnerLockRetry.canRetry()) }
		assertFailsWith<IllegalStateException> { PartnerLockRetry.run { throw IllegalStateException("boom") } }

		assertFalse(PartnerLockRetry.canRetry())
	}
}
