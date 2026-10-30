package com.duriseo.server.notification.application

/**
 * 알림 제목·본문 (PRD 8장 "알림 문구"). 만들 때 서버가 채워 저장하고 푸시에도 같은 값을 보낸다 (D-46).
 * 이름이 나중에 바뀌어도 이미 만든 알림은 그대로다.
 */
object NotificationTexts {

	const val COUPLE_LINKED_BODY = "이제 캘린더와 메모를 함께 써요"

	fun coupleLinkedTitle(partnerNickname: String) = "${partnerNickname}님과 연결됐어요"

	/** 상대가 연결을 해제했을 때 (복구 안내가 들어간다, FR-COUPLE-06) */
	const val COUPLE_UNLINKED_BODY = "30일 안에 다시 연결하면 기록이 돌아와요"

	fun coupleUnlinkedTitle(partnerNickname: String) = "${partnerNickname}님이 연결을 해제했어요"

	/** 상대가 탈퇴해 연결이 해제됐을 때 (복구 안내 없음, PRD D-53) */
	const val COUPLE_UNLINKED_BY_WITHDRAWAL_BODY = "함께 쓴 기록은 더 이상 볼 수 없어요"

	fun coupleUnlinkedByWithdrawalTitle(partnerNickname: String) = "${partnerNickname}님이 탈퇴해 연결이 해제됐어요"
}
