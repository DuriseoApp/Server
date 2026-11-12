package com.duriseo.server.push.infra

import com.duriseo.server.config.DuriseoProperties
import com.duriseo.server.push.application.PushSender
import com.duriseo.server.push.application.PushTaskRunner
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
 * 값이 있는데 읽을 수 없으면(base64·JSON이 틀림) **error 로그를 남기고 같은 건너뛰는 발송기로 뜬다**: 키 오류로 서버가 안 뜨면 배포 스크립트의
 * 자동 되돌리기도 같은 환경변수를 써서 복구할 수 없다. 알림 행은 그대로 만들어진다. 로그에는 키 내용을 쓰지 않고 예외 종류만 남긴다.
 * FCM 연결·읽기 제한 시간은 설정값이다(`duriseo.firebase.*-timeout-millis`).
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
		return try {
			val credentials = GoogleCredentials.fromStream(ByteArrayInputStream(Base64.getDecoder().decode(firebase.credentialsBase64.trim())))
			val options = FirebaseOptions.builder()
				.setCredentials(credentials)
				.setConnectTimeout(firebase.connectTimeoutMillis)
				.setReadTimeout(firebase.readTimeoutMillis)
				.apply { if (firebase.projectId.isNotBlank()) setProjectId(firebase.projectId) }
				.build()
			// 이름을 붙여 기본 앱과 겹치지 않게 한다 (같은 JVM에서 컨텍스트를 다시 만들어도 한 번만 만든다)
			val app = FirebaseApp.getApps().firstOrNull { it.name == APP_NAME } ?: FirebaseApp.initializeApp(options, APP_NAME)
			FcmPushSender(FirebaseMessaging.getInstance(app))
		} catch (e: Exception) {
			log.error(
				"FIREBASE_CREDENTIALS_BASE64 is not a usable service account key ({}): push and sync signals will be skipped",
				e.javaClass.simpleName,
			)
			SkippingPushSender()
		}
	}

	/** 푸시·동기화 신호를 요청 스레드 밖에서 보내는 풀. 종료할 때 `close()`가 불린다 */
	@Bean
	fun pushTaskRunner(properties: DuriseoProperties): PushTaskRunner =
		QueuedPushTaskRunner(properties.push.threads, properties.push.queueCapacity)

	private companion object {
		const val APP_NAME = "duriseo"
	}
}
