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

	/** 상대가 메모를 남겼을 때 (PRD 8장, API.md 7장 "상대 활동 알림"). [authorNickname]은 메모를 만든 사람의 닉네임 */
	fun partnerMemoCreatedTitle(authorNickname: String) = "${authorNickname}님이 메모를 남겼어요"

	/** 상대가 메모를 고정했을 때 (PRD 8장, D-135). [pinnerNickname]은 고정한 사람의 닉네임 */
	fun memoPinnedTitle(pinnerNickname: String) = "${pinnerNickname}님이 메모를 고정했어요"

	/** 기념일 알림 제목 (PRD 8장): "오늘은 [기념일 이름]이에요". 자동·직접 모두 같다 */
	fun anniversaryTitle(name: String) = "오늘은 ${name}이에요"

	/** 자동 기념일 본문: "함께한 지 D+[N]" (N = 그날의 D+ 값, 처음 만난 날 = D+1) */
	fun autoAnniversaryBody(dayCount: Long) = "함께한 지 D+$dayCount"

	/** 직접 기념일 본문 (D-55): 매년 반복이고 두 번째 해부터는 "[N]번째 맞는 날이에요", 그 밖에는 "오늘이에요" */
	fun customAnniversaryBody(nthYear: Int?) = if (nthYear == null) "오늘이에요" else "${nthYear}번째 맞는 날이에요"

	/** 알림 본문 첫 줄이 길 때 자르는 길이 (D-131, D-82: 코드 포인트) */
	const val MEMO_LINE_MAX = 50

	/**
	 * 메모 알림의 본문 (PRD 8장 "[메모 제목]", D-131): 제목이 있으면 제목 그대로, 없으면 본문에서 앞뒤 공백을 지운 뒤 첫 번째 비어 있지 않은 줄.
	 * 고른 줄도 앞뒤 공백을 지운다(확인 필요 6). 50 코드 포인트까지 쓰고 넘으면 49 코드 포인트 + "…"이다.
	 * 제목도 본문도 없으면 null (메모는 둘 중 하나 이상을 가지므로 저장된 메모에서는 나오지 않는다).
	 */
	fun memoLine(title: String?, body: String?): String? {
		val line = title?.takeIf { it.isNotBlank() }
			?: body?.trim()?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
			?: return null
		val length = line.codePointCount(0, line.length)
		if (length <= MEMO_LINE_MAX) return line
		return line.substring(0, line.offsetByCodePoints(0, MEMO_LINE_MAX - 1)) + "…"
	}

	/** "M월 d일 (E)": 연도 없음, 앞자리 0 없음, 요일은 한국어 한 글자 (D-92). 로케일 데이터에 기대지 않고 직접 쓴다 */
	fun monthDayWeekday(date: LocalDate): String =
		"${date.monthValue}월 ${date.dayOfMonth}일 (${WEEKDAYS[date.dayOfWeek.value - 1]})"

	private const val WEEKDAYS = "월화수목금토일"
}
