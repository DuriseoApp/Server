package com.duriseo.server.push.infra

import com.duriseo.server.push.application.PushMessage
import com.duriseo.server.push.application.PushPriority
import com.duriseo.server.push.application.PushResult
import com.duriseo.server.push.application.PushSender
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import org.slf4j.LoggerFactory

/**
 * firebase-admin으로 data 메시지를 보낸다 (API.md 6장, 7장). 우선순위는 Android `high`/`normal`, TTL은 정하지 않는다.
 * 재시도하지 않는다. 무효 토큰 응답(`UNREGISTERED`, `INVALID_ARGUMENT`)이면 [PushResult.INVALID_TOKEN], 그 밖의 오류는 [PushResult.FAILED]다.
 * 로그에는 오류 코드만 남기고 토큰·서비스 계정 키는 쓰지 않는다.
 */
class FcmPushSender(private val messaging: FirebaseMessaging) : PushSender {

	private val log = LoggerFactory.getLogger(javaClass)

	override fun send(message: PushMessage): PushResult {
		return try {
			messaging.send(toFcm(message))
			PushResult.SENT
		} catch (e: FirebaseMessagingException) {
			val code = e.messagingErrorCode
			if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
				PushResult.INVALID_TOKEN
			} else {
				log.warn("FCM send failed (type={}, code={})", message.data["type"], code ?: e.errorCode)
				PushResult.FAILED
			}
		} catch (e: Exception) {
			log.warn("FCM send failed (type={}, error={})", message.data["type"], e.javaClass.simpleName)
			PushResult.FAILED
		}
	}

	private fun toFcm(message: PushMessage): Message =
		Message.builder()
			.setToken(message.token)
			.putAllData(message.data)
			.setAndroidConfig(
				AndroidConfig.builder()
					.setPriority(if (message.priority == PushPriority.HIGH) AndroidConfig.Priority.HIGH else AndroidConfig.Priority.NORMAL)
					.build(),
			)
			.build()
}
