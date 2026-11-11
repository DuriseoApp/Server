package com.duriseo.server.push.infra

import com.duriseo.server.config.DuriseoProperties
import com.duriseo.server.push.application.PushSender
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.ByteArrayInputStream
import java.util.Base64

/**
 * 발송기 선택. `duriseo.firebase.credentials-base64`가 비어 있으면 서버는 뜨고 푸시·SYNC만 건너뛴다(시작 때 경고 한 줄).
 * 값이 있는데 읽을 수 없으면(base64·JSON이 틀림) 시작하지 않는다: 푸시가 조용히 안 가는 것보다 낫다. 오류 메시지에 키 내용은 쓰지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class PushConfig {

	private val log = LoggerFactory.getLogger(javaClass)

	@Bean
	fun pushSender(properties: DuriseoProperties): PushSender {
		val firebase = properties.firebase
		if (firebase.credentialsBase64.isBlank()) {
			log.warn("FIREBASE_CREDENTIALS_BASE64 is empty: push and sync signals will be skipped (notification rows are still created)")
			return SkippingPushSender()
		}
		val credentials = try {
			GoogleCredentials.fromStream(ByteArrayInputStream(Base64.getDecoder().decode(firebase.credentialsBase64.trim())))
		} catch (e: Exception) {
			throw IllegalStateException("FIREBASE_CREDENTIALS_BASE64 is not a readable service account key (${e.javaClass.simpleName})")
		}
		val options = FirebaseOptions.builder()
			.setCredentials(credentials)
			.apply { if (firebase.projectId.isNotBlank()) setProjectId(firebase.projectId) }
			.build()
		// 이름을 붙여 기본 앱과 겹치지 않게 한다 (같은 JVM에서 컨텍스트를 다시 만들어도 한 번만 만든다)
		val app = FirebaseApp.getApps().firstOrNull { it.name == APP_NAME } ?: FirebaseApp.initializeApp(options, APP_NAME)
		return FcmPushSender(FirebaseMessaging.getInstance(app))
	}

	private companion object {
		const val APP_NAME = "duriseo"
	}
}
