package com.duriseo.server.notification.application

/**
 * 알림 제목·본문 (PRD 8장 "알림 문구"). 만들 때 서버가 채워 저장하고 푸시에도 같은 값을 보낸다 (D-46).
 * 이름이 나중에 바뀌어도 이미 만든 알림은 그대로다.
 */
object NotificationTexts {

	const val COUPLE_LINKED_BODY = "이제 캘린더와 메모를 함께 써요"

	fun coupleLinkedTitle(partnerNickname: String) = "${partnerNickname}님과 연결됐어요"
}
