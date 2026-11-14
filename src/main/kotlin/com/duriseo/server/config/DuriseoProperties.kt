package com.duriseo.server.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 외부 서비스 설정값. 값은 환경변수로만 넣는다 (.env.example, docs/SETUP.md).
 * 코드·설정 파일·문서에 실제 값을 쓰지 않는다.
 */
@ConfigurationProperties(prefix = "duriseo")
data class DuriseoProperties(
	val jwt: Jwt = Jwt(),
	val google: Google = Google(),
	val kakao: Kakao = Kakao(),
	val firebase: Firebase = Firebase(),
	val push: Push = Push(),
	val storage: Storage = Storage(),
	val image: Image = Image(),
) {
	/**
	 * access 토큰(HS256) 서명 키. 문자열을 UTF-8 바이트 그대로 키로 쓴다 (hex 디코드하지 않음).
	 * 32바이트 이상이어야 한다. 로그에 찍히지 않도록 toString에서 값을 가린다.
	 */
	data class Jwt(val secret: String = "") {
		override fun toString(): String = "Jwt(secret=***)"
	}

	/** ID 토큰의 aud가 이 값인지 확인한다 (API.md 4.2, NFR-SEC-01) */
	data class Google(val webClientId: String = "")

	/** 토큰의 app_id가 appId인지 확인한다. adminKey는 탈퇴 시 연결 끊기에 쓴다 (FR-AUTH-06 AC3) */
	data class Kakao(val appId: String = "", val adminKey: String = "") {
		override fun toString(): String = "Kakao(appId=$appId, adminKey=***)"
	}

	/**
	 * FCM 발송. credentialsBase64는 서비스 계정 키 JSON을 base64로 인코딩한 값.
	 * 연결·읽기 제한 시간(밀리초)은 FCM이 느려도 발송 스레드가 오래 붙잡히지 않게 짧게 둔다.
	 */
	data class Firebase(
		val projectId: String = "",
		val credentialsBase64: String = "",
		val connectTimeoutMillis: Int = 5_000,
		val readTimeoutMillis: Int = 10_000,
	) {
		override fun toString(): String = "Firebase(projectId=$projectId, credentialsBase64=***)"
	}

	/** 푸시·동기화 신호를 요청 스레드 밖에서 보내는 실행기. 큐가 차면 그 신호는 버린다(재시도 없음, API.md 6장·7장) */
	data class Push(val threads: Int = 2, val queueCapacity: Int = 1_000)

	/**
	 * 프로필 사진 저장소 (API.md 8장). [type]이 `local`이면 [localDir] 폴더에, `s3`이면 [s3Bucket]에 둔다.
	 * S3 자격 증명은 설정으로 받지 않는다: EC2 역할(인스턴스 프로파일)의 기본 자격 증명 체인을 쓴다.
	 */
	data class Storage(
		val type: StorageType = StorageType.LOCAL,
		val localDir: String = "./data/profile-images",
		val s3Bucket: String = "",
		val s3Region: String = "ap-northeast-2",
		/**
		 * 저장소가 `s3`일 때 정리 작업 8단계(미참조 파일 삭제)를 돌릴지. 기본 false다: 다른 환경(맥 등)이 운영 버킷을 가리켜도 운영 사진을 지우지 않게,
		 * prod 앱만 켠다(`deploy.sh`). `local`일 때는 이 값과 관계없이 돈다.
		 */
		val cleanupEnabled: Boolean = false,
	)

	enum class StorageType { LOCAL, S3 }

	/** 프로필 사진 재인코딩 설정. 처리 동시 실행 수는 메모리(앱 컨테이너 512~640MB, progressive JPEG 디코드는 네이티브 메모리를 크게 쓴다) 때문에 1로 둔다 */
	data class Image(val jpegQuality: Float = 0.85f, val maxConcurrentProcessing: Int = 1)
}
