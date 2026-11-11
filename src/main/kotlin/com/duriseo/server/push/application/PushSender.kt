package com.duriseo.server.push.application

/** FCM 우선순위. 보이는 푸시는 [HIGH], 동기화 신호(SYNC)는 [NORMAL] (API.md 6장, 7장) */
enum class PushPriority { HIGH, NORMAL }

/**
 * 기기 하나에 보내는 data 메시지. [data]의 값은 모두 문자열이다(FCM data 규칙, 값이 없는 키는 넣지 않는다).
 * TTL은 정하지 않는다: FCM 기본값을 쓴다 (API.md 7장, 단위 6 확인 필요 2). [token]은 비밀값에 준하므로 로그에 쓰지 않는다.
 */
data class PushMessage(val token: String, val data: Map<String, String>, val priority: PushPriority) {
	// FCM 토큰이 로그에 남지 않게 한다
	override fun toString(): String = "PushMessage(type=${data["type"]}, priority=$priority)"
}

/** 보낸 결과 (API.md 7장 "푸시 실패") */
enum class PushResult {
	/** FCM이 받아 줬다 */
	SENT,

	/** 무효 토큰(`UNREGISTERED` 등): 그 `devices` 행을 지운다 */
	INVALID_TOKEN,

	/** 일시 오류·설정 없음 등: 행을 그대로 두고 로그만 남긴다. 재시도하지 않는다 */
	FAILED,
}

/** FCM으로 data 메시지를 보내는 자리. 구현은 `FcmPushSender`, 키가 없으면 `SkippingPushSender`, 테스트는 기록하는 가짜다. 예외를 밖으로 던지지 않는다 */
interface PushSender {
	fun send(message: PushMessage): PushResult
}
