package com.duriseo.server.space.domain

/** 사람 색 팔레트 키 (ERD 3.2, API.md 3.2: 디자인 시스템 `pick-*` 토큰 이름 그대로) */
object PersonColors {

	/** 개인 공간·초대코드를 만든 사람의 기본 색 (FR-CAL-04.AC3, AC5) */
	const val ORANGE = "pick-orange"

	/** 초대코드를 입력한 사람의 기본 색 (FR-CAL-04.AC3) */
	const val BLUE = "pick-blue"

	/** '함께'의 기본 색 (FR-CAL-04.AC3) */
	const val PURPLE = "pick-purple"

	/** 팔레트 키 12개. 이 순서가 팔레트 순서다 (API.md 3.2). 12개 밖의 값은 서비스가 거부한다 (DB CHECK 없음) */
	val PALETTE: List<String> = listOf(
		"pick-orange", "pick-rose", "pick-amber", "pick-mustard", "pick-green", "pick-teal",
		"pick-blue", "pick-purple", "pick-magenta", "pick-brown", "pick-gray", "pick-ink",
	)

	fun isPaletteKey(value: String): Boolean = value in PALETTE
}
