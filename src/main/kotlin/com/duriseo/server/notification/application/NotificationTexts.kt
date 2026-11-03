package com.duriseo.server.notification.application

import java.time.LocalDate

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

	/** 상대가 일정을 추가했을 때 (PRD 8장, API.md 3.3 "상대 일정 추가 알림", D-92). [authorNickname]은 일정을 만든 사람의 닉네임 */
	fun partnerCreatedTitle(authorNickname: String) = "${authorNickname}님이 일정을 추가했어요"

	/** "[날짜] · [일정 제목]". [startDate]는 일정의 시작 날짜다(시간 일정은 받는 사람의 시간대로 바꾼 날짜). 예: "10월 17일 (토) · 1박 2일 여행" */
	fun partnerCreatedBody(startDate: LocalDate, eventTitle: String) = "${monthDayWeekday(startDate)} · $eventTitle"

	/** "M월 d일 (E)": 연도 없음, 앞자리 0 없음, 요일은 한국어 한 글자 (D-92). 로케일 데이터에 기대지 않고 직접 쓴다 */
	fun monthDayWeekday(date: LocalDate): String =
		"${date.monthValue}월 ${date.dayOfMonth}일 (${WEEKDAYS[date.dayOfWeek.value - 1]})"

	private const val WEEKDAYS = "월화수목금토일"
}
