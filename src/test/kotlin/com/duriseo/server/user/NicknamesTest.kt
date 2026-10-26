package com.duriseo.server.user

import com.duriseo.server.user.domain.Nicknames
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** 닉네임 규칙(순수 함수). 소셜 가입·연동 경로의 테스트는 2부에서 경로로 확인한다 (FR-AUTH-07 AC1, D-56, D-69, D-82) */
class NicknamesTest {

	@Test
	fun `FR-AUTH-07 AC1 소셜 이름은 앞뒤 공백만 지우고 그대로 닉네임이 된다 (순수 함수)`() {
		assertEquals("김철수", Nicknames.fromSocialName("  김철수 "))
	}

	@Test
	fun `FR-AUTH-07 AC1 소셜 이름이 10자를 넘으면 앞 10개 코드 포인트만 쓴다 (순수 함수)`() {
		assertEquals("가나다라마바사아자차", Nicknames.fromSocialName("가나다라마바사아자차카타파하"))
		// 이모지를 반으로 자르지 않는다
		assertEquals("😀".repeat(10), Nicknames.fromSocialName("😀".repeat(12)))
	}

	@Test
	fun `FR-AUTH-07 AC1 소셜 이름이 없거나 공백뿐이면 닉네임은 사용자다 (순수 함수)`() {
		assertEquals("사용자", Nicknames.fromSocialName(null))
		assertEquals("사용자", Nicknames.fromSocialName(""))
		assertEquals("사용자", Nicknames.fromSocialName("   \t "))
	}

	@Test
	fun `FR-AUTH-07 AC1 10개로 자른 끝이 공백이면 그 공백도 지운다 (순수 함수)`() {
		// 10번째 코드 포인트가 공백이다
		assertEquals("가나다라마바사아자", Nicknames.fromSocialName("가나다라마바사아자 차카타"))
	}
}
