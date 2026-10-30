package com.duriseo.server.couple

import com.duriseo.server.couple.application.InviteCodeFormat
import com.duriseo.server.couple.application.RandomInviteCodeGenerator
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 초대코드 모양과 생성기 (FR-COUPLE-01 AC1·AC4, ERD 3.2 invite_codes). DB가 필요 없다 */
class InviteCodeGeneratorTest {

	@Test
	fun `FR-COUPLE-01 AC1 초대코드는 O·I·L·0·1을 뺀 대문자·숫자 31자 집합에서만 만든다`() {
		assertEquals(31, InviteCodeFormat.ALPHABET.length)
		assertEquals(31, InviteCodeFormat.ALPHABET.toSet().size)
		"OIL01".forEach { assertFalse(it in InviteCodeFormat.ALPHABET, "$it 는 집합에 없어야 한다") }

		val generator = RandomInviteCodeGenerator()
		val used = mutableSetOf<Char>()
		repeat(5000) {
			val code = generator.generate()
			assertEquals(6, code.length)
			assertTrue(code.all { it in InviteCodeFormat.ALPHABET }, "집합 밖 글자: $code")
			assertTrue(InviteCodeFormat.isValid(code))
			used += code.toSet()
		}
		// 3만 번 고르면 31자가 모두 나온다 (한 글자라도 빠질 확률은 무시할 만큼 작다)
		assertEquals(InviteCodeFormat.ALPHABET.toSet(), used)
	}

	@Test
	fun `FR-COUPLE-01 AC4 입력은 공백을 모두 지우고 대문자로 바꾼다`() {
		assertEquals("XK7M2P", InviteCodeFormat.normalize(" xk7 m2p\t"))
		assertEquals("XK7M2P", InviteCodeFormat.normalize("XK7M2P"))
		assertEquals("", InviteCodeFormat.normalize("   "))
	}

	@Test
	fun `FR-COUPLE-02 AC2 6자리가 아니거나 집합 밖 글자가 있는 코드는 형식이 틀리다`() {
		assertTrue(InviteCodeFormat.isValid("XK7M2P"))
		assertFalse(InviteCodeFormat.isValid("XK7M2"))
		assertFalse(InviteCodeFormat.isValid("XK7M2PQ"))
		// O, I, L, 0, 1은 집합에 없다
		listOf("XK7M2O", "XK7M2I", "XK7M2L", "XK7M20", "XK7M21", "XK7M2-").forEach {
			assertFalse(InviteCodeFormat.isValid(it), it)
		}
		// 소문자는 정규화 전 값이라 형식 검사를 통과하지 못한다 (정규화를 먼저 한다)
		assertFalse(InviteCodeFormat.isValid("xk7m2p"))
	}
}
