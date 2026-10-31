package com.duriseo.server.auth.api

import com.duriseo.server.auth.application.KakaoUnlinkWebhookService
import com.duriseo.server.auth.application.social.KakaoWebhookAuthenticator
import com.duriseo.server.common.web.TraceIdFilter
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

/**
 * Kakao 연결 해제 알림 웹훅 (API.md 3.6, 4.8, FR-AUTH-06.AC4). Kakao 서버만 부르며 access 토큰이 필요 없다(`AuthWebConfig`).
 * Kakao는 GET(쿼리 문자열)과 POST(`application/x-www-form-urlencoded`) 둘 다 쓸 수 있어 값은 `getParameter`로 같이 읽는다.
 *
 * **200이 아닌 것은 인증 실패(401 `WEBHOOK_UNAUTHORIZED`)뿐이다.** 인증을 통과한 뒤에는 사용자가 없거나 처리 중 오류가 나도
 * 200과 빈 본문으로 답한다(200이 아니면 Kakao가 웹훅을 끌 수 있다). 처리하지 못한 알림은 ERROR 로그에 Kakao `user_id`,
 * `referrer_type`, `traceId`를 남기고 운영자가 처리한다 (`docs/DEPLOY.md`). 어드민 키·헤더 값은 로그에 쓰지 않는다.
 */
@RestController
class KakaoWebhookController(
	private val authenticator: KakaoWebhookAuthenticator,
	private val service: KakaoUnlinkWebhookService,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	@RequestMapping("/webhooks/kakao/unlink", method = [RequestMethod.GET, RequestMethod.POST])
	fun unlink(request: HttpServletRequest): ResponseEntity<Void> {
		authenticator.authenticate(request.getHeader(HttpHeaders.AUTHORIZATION), parameter(request, "app_id"))

		val kakaoUserId = parameter(request, "user_id")?.trim()
		val referrerType = parameter(request, "referrer_type")
		if (kakaoUserId.isNullOrEmpty()) {
			log.warn("Kakao unlink notice without user_id was ignored (referrer_type={})", printable(referrerType))
		} else {
			try {
				service.handle(kakaoUserId)
			} catch (e: Exception) {
				log.error(
					"Kakao unlink notice was not processed: user_id={} referrer_type={} traceId={}",
					printable(kakaoUserId), printable(referrerType), TraceIdFilter.of(request), e,
				)
			}
		}
		return ResponseEntity.ok().build()
	}

	/** 본문을 읽지 못하는 요청은 값이 없는 것으로 본다 (인증 값이면 401, 아니면 200) */
	private fun parameter(request: HttpServletRequest, name: String): String? =
		try {
			request.getParameter(name)
		} catch (e: Exception) {
			null
		}

	/** 로그에 줄바꿈 등을 넣어 로그를 속이지 못하게 제어 문자를 지우고 길이를 줄인다 */
	private fun printable(value: String?): String =
		value?.filter { it.code in 0x20..0x7E || it.code > 0xA0 }?.take(MAX_LOGGED_LENGTH) ?: "-"

	private companion object {
		const val MAX_LOGGED_LENGTH = 64
	}
}
