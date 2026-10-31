package com.duriseo.server.couple.application

import org.springframework.stereotype.Component
import java.security.SecureRandom

/** 새 초대코드 한 개를 만든다. 테스트가 충돌 상황을 만들려고 바꿔 끼울 수 있게 인터페이스로 둔다 */
interface InviteCodeGenerator {
	fun generate(): String
}

/** `SecureRandom`으로 31자 집합에서 6자리를 고른다 (31^6 ≈ 8.9억 조합, DB-08) */
@Component
class RandomInviteCodeGenerator : InviteCodeGenerator {

	private val random = SecureRandom()

	override fun generate(): String {
		val chars = CharArray(InviteCodeFormat.LENGTH) { InviteCodeFormat.ALPHABET[random.nextInt(InviteCodeFormat.ALPHABET.length)] }
		return String(chars)
	}
}
