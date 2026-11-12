package com.duriseo.server.push

import com.duriseo.server.config.DuriseoProperties
import com.duriseo.server.push.infra.PushConfig
import com.duriseo.server.push.infra.QueuedPushTaskRunner
import com.duriseo.server.push.infra.SkippingPushSender
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 발송기 선택(키가 비었거나 잘못돼도 서버는 뜬다)과 발송 스레드 풀(큐가 차면 버린다). DB가 필요 없다 */
class PushInfraTest {

	private fun senderFor(credentialsBase64: String) =
		PushConfig().pushSender(DuriseoProperties(firebase = DuriseoProperties.Firebase(projectId = "fake-project", credentialsBase64 = credentialsBase64)))

	@Test
	fun `7장 Firebase 키가 비어 있으면 건너뛰는 발송기로 뜬다`() {
		assertIs<SkippingPushSender>(senderFor(""))
		assertIs<SkippingPushSender>(senderFor("   "))
	}

	@Test
	fun `7장 Firebase 키가 base64가 아니면 서버가 죽지 않고 건너뛰는 발송기로 뜬다`() {
		assertIs<SkippingPushSender>(senderFor("this is !!! not base64"))
	}

	@Test
	fun `7장 Firebase 키가 서비스 계정 JSON이 아니면 서버가 죽지 않고 건너뛰는 발송기로 뜬다`() {
		val notAKey = Base64.getEncoder().encodeToString("""{"fake": "not a service account"}""".toByteArray())
		assertIs<SkippingPushSender>(senderFor(notAKey))
		assertIs<SkippingPushSender>(senderFor(Base64.getEncoder().encodeToString("plain text".toByteArray())))
	}

	@Test
	fun `6장 발송 스레드 풀은 정원이 차면 받지 않고 거절한다`() {
		val runner = QueuedPushTaskRunner(threads = 1, queueCapacity = 1)
		val release = CountDownLatch(1)
		val started = CountDownLatch(1)
		val finished = AtomicInteger()
		try {
			runner.run { started.countDown(); release.await(10, TimeUnit.SECONDS); finished.incrementAndGet() }
			assertTrue(started.await(10, TimeUnit.SECONDS))
			runner.run { finished.incrementAndGet() } // 대기 큐 한 칸
			// 스레드 1개가 일하고 큐가 찼다: 다음 신호는 버려진다 (재시도 없음)
			assertFailsWith<RejectedExecutionException> { runner.run { finished.incrementAndGet() } }
		} finally {
			release.countDown()
			runner.close()
		}
		assertEquals(2, finished.get())
	}
}
