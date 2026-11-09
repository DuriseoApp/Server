package com.duriseo.server.memo

import com.duriseo.server.notification.application.NotificationTexts
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 알림의 "본문 첫 줄" 규칙 (D-131, 확인 필요 6): 순수 함수 단위 테스트 */
class MemoFirstLineTest {

	@Test
	fun `D-131 제목이 있으면 본문과 관계없이 제목을 그대로 쓴다`() {
		assertEquals("장보기", NotificationTexts.memoLine("장보기", "첫 줄\n둘째 줄"))
		assertEquals("장보기", NotificationTexts.memoLine("장보기", null))
	}

	@Test
	fun `D-131 제목이 없으면 본문 첫 비어 있지 않은 줄을 쓰고 50자를 넘으면 49자와 말줄임표다`() {
		assertEquals("첫 줄", NotificationTexts.memoLine(null, "첫 줄\n둘째 줄"))
		// 앞의 빈 줄과 공백을 건너뛴다
		assertEquals("내용", NotificationTexts.memoLine(null, "\n  \n\t\n내용\n끝"))
		assertEquals("내용", NotificationTexts.memoLine(null, "  \r\n\r\n 내용 \r\n끝"))
		assertEquals("내용", NotificationTexts.memoLine(null, "\r\r내용"))
		// 고른 줄의 뒤쪽 공백도 지운다 (확인 필요 6)
		assertEquals("내용", NotificationTexts.memoLine(null, "  내용   \n둘째"))
		// 50자는 그대로, 51자는 49자 + "…"
		assertEquals("가".repeat(50), NotificationTexts.memoLine(null, "가".repeat(50)))
		assertEquals("가".repeat(49) + "…", NotificationTexts.memoLine(null, "가".repeat(51)))
		assertEquals("가".repeat(49) + "…", NotificationTexts.memoLine(null, "\n" + "가".repeat(500) + "\n둘째"))
		// 자르는 길이는 앞뒤 공백을 지운 뒤 잰다
		assertEquals("가".repeat(50), NotificationTexts.memoLine(null, "   " + "가".repeat(50) + "   "))
	}

	@Test
	fun `D-131 길이는 UTF-16이 아니라 코드 포인트로 센다`() {
		assertEquals("😀".repeat(50), NotificationTexts.memoLine(null, "😀".repeat(50)))
		assertEquals("😀".repeat(49) + "…", NotificationTexts.memoLine(null, "😀".repeat(51)))
	}

	@Test
	fun `D-131 제목도 본문도 없으면 null이다`() {
		assertNull(NotificationTexts.memoLine(null, null))
		assertNull(NotificationTexts.memoLine(null, "  \n \t "))
		assertNull(NotificationTexts.memoLine("", ""))
	}

	@Test
	fun `D-131 알림 제목 문구`() {
		assertEquals("에이님이 메모를 남겼어요", NotificationTexts.partnerMemoCreatedTitle("에이"))
		assertEquals("에이님이 메모를 고정했어요", NotificationTexts.memoPinnedTitle("에이"))
	}
}
