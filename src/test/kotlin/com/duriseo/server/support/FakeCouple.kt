package com.duriseo.server.support

import com.duriseo.server.couple.application.CoupleLinked
import com.duriseo.server.couple.application.InviteCodeGenerator
import com.duriseo.server.couple.application.RandomInviteCodeGenerator
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
 * 커밋 뒤에 전달된 `CoupleLinked`를 모은다. [onLinked]는 전달되는 순간 실행한다: 그때 다른 연결에서 커밋된 값이 보이는지로
 * "커밋 뒤에만 전달된다"를 확인한다. 롤백된 트랜잭션의 이벤트는 오지 않는다.
 */
class CoupleEventRecorder {

	val linked = CopyOnWriteArrayList<CoupleLinked>()

	@Volatile
	var onLinked: ((CoupleLinked) -> Unit)? = null

	fun reset() {
		linked.clear()
		onLinked = null
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun record(event: CoupleLinked) {
		onLinked?.invoke(event)
		linked.add(event)
	}
}

/** 커플 통합 테스트가 쓰는 가짜들. `TestClockConfig`가 함께 가져와 컨텍스트가 하나로 공유된다 */
@TestConfiguration(proxyBeanMethods = false)
class TestCoupleConfig {

	@Bean
	@Primary
	fun scriptedInviteCodeGenerator() = ScriptedInviteCodeGenerator(RandomInviteCodeGenerator())

	@Bean
	fun coupleEventRecorder() = CoupleEventRecorder()
}
