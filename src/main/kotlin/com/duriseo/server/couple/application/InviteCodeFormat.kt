package com.duriseo.server.couple.application

import java.util.Locale

/** 초대코드 모양: 대문자 6자리, 31자 집합 (PRD D-05, ERD 3.2 invite_codes) */
object InviteCodeFormat {

	/** 영문 대문자 A–Z 중 O·I·L을 뺀 23자 + 숫자 2–9 8자 = 31자 */
	const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
	const val LENGTH = 6

	/** 입력한 코드는 공백을 모두 지우고 대문자로 바꾼 뒤 비교한다 (FR-COUPLE-01.AC4) */
	fun normalize(raw: String): String = raw.filterNot { it.isWhitespace() }.uppercase(Locale.ROOT)

	/** 정규화한 코드가 6자리이고 모든 글자가 31자 집합 안에 있는가 */
	fun isValid(normalized: String): Boolean = normalized.length == LENGTH && normalized.all { it in ALPHABET }
}
