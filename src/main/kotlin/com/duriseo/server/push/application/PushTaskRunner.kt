package com.duriseo.server.push.application

import java.util.concurrent.RejectedExecutionException

/**
 * 푸시·동기화 신호 발송을 요청 스레드(스케줄러 스레드)에서 떼어 내는 자리. 운영 구현은 [QueuedPushTaskRunner]이고, 테스트는 바로 실행하는 구현으로 바꿔
 * 결과가 결정적이게 한다. 큐가 차서 받지 못하면 [RejectedExecutionException]을 던지고, 부르는 쪽은 그 신호를 버린다(재시도 없음).
 */
fun interface PushTaskRunner {
	@Throws(RejectedExecutionException::class)
	fun run(task: Runnable)
}
