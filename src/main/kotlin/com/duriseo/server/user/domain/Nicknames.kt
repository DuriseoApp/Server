package com.duriseo.server.user.domain

/**
 * 닉네임 규칙 (PRD D-56, D-69, D-82, ERD 3.1 users.nickname).
 * 앞뒤 공백을 지우고, 글자 수는 유니코드 코드 포인트로 센다 (Kotlin `String.length`는 UTF-16 단위라 쓰지 않는다).
 */
object Nicknames {

	const val MAX_CODE_POINTS = 10

	/** 게스트 기본 닉네임. 연동 때 정확히 이 값일 때만 소셜 이름으로 바꾼다 (D-68) */
	const val GUEST_DEFAULT = "게스트"

	/** 소셜 이름을 받지 못했거나 공백뿐일 때 (D-69) */
	const val FALLBACK = "사용자"

	/** 탈퇴 익명화 (ERD 4.3) */
	const val WITHDRAWN = "탈퇴한 사용자"

	fun codePointLength(value: String): Int = value.codePointCount(0, value.length)

	/** 소셜 프로필 이름 → 닉네임. 앞뒤 공백 제거, 코드 포인트 앞 10개, 비면 "사용자" */
	fun fromSocialName(socialName: String?): String {
		val trimmed = socialName?.trim().orEmpty()
		if (trimmed.isEmpty()) return FALLBACK
		return takeCodePoints(trimmed, MAX_CODE_POINTS)
	}

	/** 앞 [count] 코드 포인트만 남긴다. 이모지 같은 보충 문자를 반으로 자르지 않는다 */
	fun takeCodePoints(value: String, count: Int): String {
		if (codePointLength(value) <= count) return value
		val end = value.offsetByCodePoints(0, count)
		return value.substring(0, end)
	}
}
