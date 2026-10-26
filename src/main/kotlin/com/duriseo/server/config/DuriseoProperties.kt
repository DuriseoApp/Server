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
	data class Kakao(val appId: String = "", val adminKey: String = "")

	/** FCM 발송. credentialsBase64는 서비스 계정 키 JSON을 base64로 인코딩한 값 */
	data class Firebase(val projectId: String = "", val credentialsBase64: String = "")
}
