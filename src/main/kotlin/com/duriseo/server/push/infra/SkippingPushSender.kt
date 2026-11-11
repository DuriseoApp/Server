package com.duriseo.server.push.infra

import com.duriseo.server.push.application.PushMessage
import com.duriseo.server.push.application.PushResult
import com.duriseo.server.push.application.PushSender

/** Firebase 키가 비어 있을 때(로컬·테스트) 쓰는 발송기. 아무것도 보내지 않는다. 알림 행은 그대로 만들어진다 (단위 6 확인 필요 3) */
class SkippingPushSender : PushSender {
	override fun send(message: PushMessage): PushResult = PushResult.FAILED
}
