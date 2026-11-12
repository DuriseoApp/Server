package com.duriseo.server.support

import com.duriseo.server.anniversary.application.AnniversaryCreated
import com.duriseo.server.anniversary.application.AnniversaryDeleted
import com.duriseo.server.anniversary.application.AnniversaryUpdated
import com.duriseo.server.couple.application.CoupleLinked
import com.duriseo.server.couple.application.CoupleUnlinked
import com.duriseo.server.couple.application.InviteCodeGenerator
import com.duriseo.server.couple.application.RandomInviteCodeGenerator
import com.duriseo.server.event.application.EventCreated
import com.duriseo.server.event.application.EventDeleted
import com.duriseo.server.event.application.EventUpdated
import com.duriseo.server.memo.application.MemoCreated
import com.duriseo.server.memo.application.MemoDeleted
import com.duriseo.server.memo.application.MemoUpdated
import com.duriseo.server.push.application.PushMessage
import com.duriseo.server.push.application.PushPriority
import com.duriseo.server.push.application.PushResult
import com.duriseo.server.push.application.PushSender
import com.duriseo.server.push.application.PushTaskRunner
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 초대코드 생성기를 감싼 테스트용 생성기. [enqueue]한 코드를 먼저 돌려주고, 없으면 진짜 생성기를 쓴다.
 * 코드가 이미 있는 코드와 겹치는 상황(`uq_invite_codes_code`)을 만들려고 쓴다.
 */
class ScriptedInviteCodeGenerator(private val fallback: InviteCodeGenerator) : InviteCodeGenerator {

	private val script = ConcurrentLinkedQueue<String>()

	/** 생성기가 돌려준 코드를 순서대로 모은다 */
	val generated = CopyOnWriteArrayList<String>()

	fun enqueue(vararg codes: String) {
		script.addAll(codes)
	}

	fun reset() {
		script.clear()
		generated.clear()
	}

	override fun generate(): String {
		val code = script.poll() ?: fallback.generate()
		generated.add(code)
		return code
	}
}

/**
 * 커밋 뒤에 전달된 `CoupleLinked`·`CoupleUnlinked`를 모은다. [onLinked]·[onUnlinked]는 전달되는 순간 실행한다: 그때 다른 연결에서
 * 커밋된 값이 보이는지로 "커밋 뒤에만 전달된다"를 확인한다. 롤백된 트랜잭션의 이벤트는 오지 않는다.
 */
class CoupleEventRecorder {

	val linked = CopyOnWriteArrayList<CoupleLinked>()

	val unlinked = CopyOnWriteArrayList<CoupleUnlinked>()

	@Volatile
	var onLinked: ((CoupleLinked) -> Unit)? = null

	@Volatile
	var onUnlinked: ((CoupleUnlinked) -> Unit)? = null

	fun reset() {
		linked.clear()
		unlinked.clear()
		onLinked = null
		onUnlinked = null
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun record(event: CoupleLinked) {
		onLinked?.invoke(event)
		linked.add(event)
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun record(event: CoupleUnlinked) {
		onUnlinked?.invoke(event)
		unlinked.add(event)
	}
}

/**
 * 커밋 뒤에 전달된 `EventCreated`·`EventUpdated`·`EventDeleted`를 모은다. [onCreated]는 전달되는 순간 실행한다: 그때 다른 연결에서
 * 커밋된 값이 보이는지로 "커밋 뒤에만 전달된다"를 확인한다. 롤백된 트랜잭션의 이벤트는 오지 않는다.
 */
class EventChangeRecorder {

	val created = CopyOnWriteArrayList<EventCreated>()

	val updated = CopyOnWriteArrayList<EventUpdated>()

	val deleted = CopyOnWriteArrayList<EventDeleted>()

	@Volatile
	var onCreated: ((EventCreated) -> Unit)? = null

	fun reset() {
		created.clear()
		updated.clear()
		deleted.clear()
		onCreated = null
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun record(event: EventCreated) {
		onCreated?.invoke(event)
		created.add(event)
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun record(event: EventUpdated) {
		updated.add(event)
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun record(event: EventDeleted) {
		deleted.add(event)
	}
}

/**
 * 커밋 뒤에 전달된 `AnniversaryCreated`·`AnniversaryUpdated`·`AnniversaryDeleted`를 모은다. [onCreated]·[onUpdated]는 전달되는 순간 실행한다:
 * 그때 다른 연결에서 커밋된 값이 보이는지로 "커밋 뒤에만 전달된다"를 확인한다. 롤백된 트랜잭션의 이벤트는 오지 않는다.
 */
class AnniversaryChangeRecorder {

	val created = CopyOnWriteArrayList<AnniversaryCreated>()

	val updated = CopyOnWriteArrayList<AnniversaryUpdated>()

	val deleted = CopyOnWriteArrayList<AnniversaryDeleted>()

	@Volatile
	var onCreated: ((AnniversaryCreated) -> Unit)? = null

	@Volatile
	var onUpdated: ((AnniversaryUpdated) -> Unit)? = null

	fun reset() {
		created.clear()
		updated.clear()
		deleted.clear()
		onCreated = null
		onUpdated = null
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun record(event: AnniversaryCreated) {
		onCreated?.invoke(event)
		created.add(event)
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun record(event: AnniversaryUpdated) {
		onUpdated?.invoke(event)
		updated.add(event)
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun record(event: AnniversaryDeleted) {
		deleted.add(event)
	}
}

/** 커밋 뒤에 전달된 `MemoCreated`·`MemoUpdated`·`MemoDeleted`를 모은다. 롤백된 트랜잭션의 이벤트는 오지 않는다 */
class MemoChangeRecorder {

	val created = CopyOnWriteArrayList<MemoCreated>()

	val updated = CopyOnWriteArrayList<MemoUpdated>()

	val deleted = CopyOnWriteArrayList<MemoDeleted>()

	fun reset() {
		created.clear()
		updated.clear()
		deleted.clear()
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun record(event: MemoCreated) {
		created.add(event)
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun record(event: MemoUpdated) {
		updated.add(event)
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun record(event: MemoDeleted) {
		deleted.add(event)
	}
}

/**
 * FCM 대신 보낸 메시지를 모으는 발송기 (단위 6). [resultFor]로 토큰별 결과(무효 토큰, 일시 오류)를 정한다.
 * 메시지는 보낸 순서대로 쌓인다. `data["type"]`이 `PUSH`면 보이는 푸시, `SYNC`면 동기화 신호다.
 */
class RecordingPushSender : PushSender {

	val sent = CopyOnWriteArrayList<PushMessage>()

	@Volatile
	var resultFor: (PushMessage) -> PushResult = { PushResult.SENT }

	fun reset() {
		sent.clear()
		resultFor = { PushResult.SENT }
	}

	override fun send(message: PushMessage): PushResult {
		sent.add(message)
		return resultFor(message)
	}

	/** [token] 기기가 받은 메시지 */
	fun to(token: String): List<PushMessage> = sent.filter { it.token == token }

	/** [token] 기기가 받은 보이는 푸시 */
	fun pushesTo(token: String): List<PushMessage> = to(token).filter { it.data["type"] == "PUSH" }

	/** [token] 기기가 받은 동기화 신호 중 [resource]인 것 */
	fun syncsTo(token: String, resource: String): List<PushMessage> =
		to(token).filter { it.data["type"] == "SYNC" && it.data["resource"] == resource }

	fun isHighPriority(message: PushMessage) = message.priority == PushPriority.HIGH
}

/** 커플 통합 테스트가 쓰는 가짜들. `TestClockConfig`가 함께 가져와 컨텍스트가 하나로 공유된다 */
@TestConfiguration(proxyBeanMethods = false)
class TestCoupleConfig {

	@Bean
	@Primary
	fun scriptedInviteCodeGenerator() = ScriptedInviteCodeGenerator(RandomInviteCodeGenerator())

	/** 운영 발송기(키가 비면 건너뛰는 구현) 대신 기록하는 가짜를 쓴다 */
	@Bean
	@Primary
	fun recordingPushSender() = RecordingPushSender()

	/** 운영의 발송 스레드 풀 대신 호출한 스레드에서 바로 실행한다. 테스트가 결정적이다 */
	@Bean
	@Primary
	fun inlinePushTaskRunner(): PushTaskRunner = PushTaskRunner { it.run() }

	@Bean
	fun coupleEventRecorder() = CoupleEventRecorder()

	@Bean
	fun eventChangeRecorder() = EventChangeRecorder()

	@Bean
	fun anniversaryChangeRecorder() = AnniversaryChangeRecorder()

	@Bean
	fun memoChangeRecorder() = MemoChangeRecorder()
}
