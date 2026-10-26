package com.duriseo.server.auth.application

import com.duriseo.server.user.domain.AccountType
import java.time.Duration

/** 토큰 수명 (API.md 1장, PRD D-73, NFR-SEC-01). Docs가 정한 상수라 환경변수로 만들지 않는다 */
object TokenLifetimes {

	val ACCESS: Duration = Duration.ofHours(1)

	private val REFRESH_MEMBER: Duration = Duration.ofDays(30)
	private val REFRESH_GUEST: Duration = Duration.ofDays(365)

	/** 계정 종류는 갱신할 때마다 DB에서 읽은 값을 넘긴다. 연동한 게스트는 다음 갱신부터 30일이 된다 */
	fun refresh(accountType: AccountType): Duration = when (accountType) {
		AccountType.MEMBER -> REFRESH_MEMBER
		AccountType.GUEST -> REFRESH_GUEST
	}
}
