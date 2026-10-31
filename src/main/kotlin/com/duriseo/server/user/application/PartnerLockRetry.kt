package com.duriseo.server.user.application

/** 사용자와 커플 상대를 잠그는 동안 상대가 바뀌었다 (`UserService.lockForUpdateWithPartner`). 트랜잭션을 롤백하고 처음부터 다시 잠근다 */
class PartnerChangedException : RuntimeException("couple partner changed while locking") {
	// 흐름 제어용이라 스택을 채우지 않는다
	override fun fillInStackTrace(): Throwable = this
}

/**
 * 본인과 상대의 사용자 행을 UUID 순서로 잠그는 트랜잭션(연결 해제, 탈퇴, Kakao 웹훅)을 다시 시도하는 도우미.
 * 상대는 잠금 없이 먼저 읽기 때문에, 그 사이 연결·해제로 상대가 바뀌면 새 상대는 UUID 순서를 지켜 잠글 수 없다.
 * 이때 [PartnerChangedException]을 던져 트랜잭션을 롤백하고 [block]을 처음부터 다시 실행해 순서대로 잠근다.
 * [MAX_RETRIES]번 다시 시도해도 바뀌면 마지막 시도는 새 상대를 이어서 잠그는 기존 동작을 한다(순서가 어긋날 수 있다).
 *
 * [block]은 매번 **새 트랜잭션을 열어야** 한다(`TransactionTemplate`). 이미 열린 트랜잭션 안에서는 쓰지 않는다.
 * 이 도우미 밖(예: 게스트 정리)에서는 다시 시도하지 않고 기존 동작이다.
 */
object PartnerLockRetry {

	const val MAX_RETRIES = 3

	private val retriesLeft = ThreadLocal<Int>()

	/** 지금 시도에서 상대가 바뀌면 다시 시도할 수 있는가 */
	fun canRetry(): Boolean = (retriesLeft.get() ?: 0) > 0

	fun <T> run(block: () -> T): T {
		var left = MAX_RETRIES
		while (true) {
			retriesLeft.set(left)
			try {
				return block()
			} catch (e: PartnerChangedException) {
				// left가 0인 시도는 던지지 않으므로 여기서 left는 항상 1 이상이다
				left--
			} finally {
				retriesLeft.remove()
			}
		}
	}
}
