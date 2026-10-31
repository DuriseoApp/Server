package com.duriseo.server.user.application

import java.util.UUID

/**
 * 사용자가 탈퇴(익명화)했다. 탈퇴 트랜잭션 안에서 발행하고, 외부 호출은 커밋 뒤 리스너가 한다 (ARCHITECTURE 4장).
 *
 * - [kakaoProviderUserId]: Kakao 회원이면 연결 끊기에 쓸 ID. 소셜 계정 행을 지우기 전에 읽은 값이다. 로그에 남기지 않는다.
 * - [nicknameBeforeWithdrawal], [partnerUserId], [profileImageKey]: 단위 6(탈퇴 해제 알림·푸시·SYNC)과 단위 7(프로필 사진 파일 삭제)이
 *   커밋 뒤에 쓴다. 익명화 전 값이다. [partnerUserId]는 탈퇴로 연결이 해제된 상대(커플이 아니었으면 null)다. 같은 해제는 `CoupleUnlinked`
 *   (`byWithdrawal = true`)로도 발행된다. 단위 6은 둘 중 한쪽에서만 상대에게 신호를 보낸다.
 */
data class UserWithdrawn(
	val userId: UUID,
	val nicknameBeforeWithdrawal: String,
	val profileImageKey: String?,
	val partnerUserId: UUID?,
	val kakaoProviderUserId: String?,
) {
	// Kakao ID가 로그에 남지 않게 한다
	override fun toString(): String = "UserWithdrawn(userId=$userId)"
}
