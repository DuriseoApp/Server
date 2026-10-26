package com.duriseo.server.auth.application.social

import com.duriseo.server.config.DuriseoProperties
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.http.HttpClient
import java.time.Duration

/** Kakao가 요청을 거부했다 (4xx) */
class KakaoRejectedException(val status: Int) : RuntimeException("kakao rejected the request (status $status)")

/** Kakao에서 답을 받지 못했다 (5xx, 시간 초과, 연결 실패, 읽을 수 없는 응답) */
class KakaoUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 탈퇴 뒤 Kakao 앱 연결 끊기 (API.md 4.6). 테스트는 이 인터페이스를 가짜로 바꾼다 */
interface KakaoUnlinker {

	fun unlink(providerUserId: String)
}

@Configuration(proxyBeanMethods = false)
class KakaoClientConfig {

	/** 짧은 연결·읽기 제한 시간을 둔다. 로그인 요청과 탈퇴 응답이 Kakao 때문에 길어지지 않게 한다 */
	@Bean
	fun kakaoRestClient(): RestClient {
		val httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
		val factory = JdkClientHttpRequestFactory(httpClient)
		factory.setReadTimeout(READ_TIMEOUT)
		return RestClient.builder().requestFactory(factory).build()
	}

	private companion object {
		val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
		val READ_TIMEOUT: Duration = Duration.ofSeconds(3)
	}
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class KakaoTokenInfoResponse(
	@JsonProperty("id") val id: Long? = null,
	@JsonProperty("app_id") val appId: Long? = null,
)

/** `GET /v2/user/me`에서 닉네임만 읽는다. 이메일 등 다른 값은 필드가 없어 받지 않는다 (NFR-PRIV-01) */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KakaoUserMeResponse(
	@JsonProperty("kakao_account") val kakaoAccount: KakaoAccount? = null,
) {
	@JsonIgnoreProperties(ignoreUnknown = true)
	data class KakaoAccount(@JsonProperty("profile") val profile: Profile? = null)

	@JsonIgnoreProperties(ignoreUnknown = true)
	data class Profile(@JsonProperty("nickname") val nickname: String? = null)
}

/**
 * Kakao REST API 호출 (API.md 4.2, 4.6). 토큰과 어드민 키는 로그에 남기지 않는다.
 * 4xx는 [KakaoRejectedException], 그 밖의 실패(5xx, 시간 초과 등)는 [KakaoUnavailableException]으로 바꿔 던진다.
 */
@Component
class KakaoApiClient(
	@Qualifier("kakaoRestClient") private val restClient: RestClient,
	private val properties: DuriseoProperties,
) : KakaoUnlinker {

	private val log = LoggerFactory.getLogger(javaClass)

	/** 토큰 확인. 토큰의 사용자 ID와 발급된 앱 ID를 돌려준다 */
	fun accessTokenInfo(accessToken: String): KakaoTokenInfoResponse {
		val info: KakaoTokenInfoResponse? = execute {
			restClient.get()
				.uri(TOKEN_INFO_URL)
				.header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
				.retrieve()
				.body(KakaoTokenInfoResponse::class.java)
		}
		if (info == null || info.id == null || info.appId == null) {
			throw KakaoUnavailableException("access_token_info response has no id or app_id")
		}
		return info
	}

	/** 프로필 닉네임만 읽는다. 동의항목이 꺼져 있어 닉네임이 없으면 null */
	fun nickname(accessToken: String): String? {
		val me: KakaoUserMeResponse? = execute {
			restClient.get()
				.uri(USER_ME_URL)
				.header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
				.retrieve()
				.body(KakaoUserMeResponse::class.java)
		}
		return me?.kakaoAccount?.profile?.nickname
	}

	/** 앱 연결 끊기 (어드민 키). 키가 비어 있으면 건너뛰고 경고만 남긴다 */
	override fun unlink(providerUserId: String) {
		val adminKey = properties.kakao.adminKey
		if (adminKey.isBlank()) {
			log.warn("Kakao admin key is not configured; skipping Kakao unlink")
			return
		}
		val form = LinkedMultiValueMap<String, String>()
		form.add("target_id_type", "user_id")
		form.add("target_id", providerUserId)
		execute {
			restClient.post()
				.uri(UNLINK_URL)
				.header(HttpHeaders.AUTHORIZATION, "KakaoAK $adminKey")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.toBodilessEntity()
		}
	}

	private fun <T> execute(call: () -> T): T = try {
		call()
	} catch (e: HttpClientErrorException) {
		throw KakaoRejectedException(e.statusCode.value())
	} catch (e: RestClientException) {
		throw KakaoUnavailableException("kakao request failed (${e.javaClass.simpleName})", e)
	}

	companion object {
		const val TOKEN_INFO_URL = "https://kapi.kakao.com/v1/user/access_token_info"
		const val USER_ME_URL = "https://kapi.kakao.com/v2/user/me"
		const val UNLINK_URL = "https://kapi.kakao.com/v1/user/unlink"
	}
}
