package com.duriseo.server.push.infra

import com.duriseo.server.push.application.PushTaskRunner
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 정원이 있는 발송 스레드 풀. 스레드 [threads]개, 대기 [queueCapacity]개이고 넘치면 [java.util.concurrent.RejectedExecutionException]을 던진다
 * (밀린 신호를 쌓아 두지 않는다: 푸시는 재시도하지 않고 앱이 화면 진입 때 다시 불러온다, NFR-SYNC-02). 스레드는 데몬이다.
 * 종료할 때 남은 일을 잠깐(5초) 기다린다.
 */
class QueuedPushTaskRunner(threads: Int, queueCapacity: Int) : PushTaskRunner, AutoCloseable {

	private val executor = ThreadPoolExecutor(
		threads, threads, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(queueCapacity), NamedDaemonThreadFactory(),
		ThreadPoolExecutor.AbortPolicy(),
	)

	override fun run(task: Runnable) {
		executor.execute(task)
	}

	override fun close() {
		executor.shutdown()
		if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
	}

	private class NamedDaemonThreadFactory : ThreadFactory {
		private val number = AtomicInteger()

		override fun newThread(task: Runnable): Thread =
			Thread(task, "push-${number.incrementAndGet()}").apply { isDaemon = true }
	}
}
