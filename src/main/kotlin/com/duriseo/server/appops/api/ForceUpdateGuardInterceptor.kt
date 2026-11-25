package com.duriseo.server.appops.api

import com.duriseo.server.appops.application.ClientInfo
import com.duriseo.server.appops.application.ForceUpdateRules
import com.duriseo.server.appops.domain.AppPlatform
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.UpdateRuleDetails
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/**
 * 강제 업데이트 차단 (R-20, D-180, FR-APP-02.AC6). `/api/v1` 아래 요청에서 요청 헤더(1장)가 맞는 `FORCE` 규칙이 있으면
 * 426 `APP_VERSION_UNSUPPORTED`로 거부한다. 인증보다 먼저 도는 인터셉터라 로그인 전 요청과 만료된 토큰의 요청도 막는다.
 * 예외 경로(공개 조회 3개, `/admin/` 아래 경로)는 `AppOpsWebConfig`가 뺀다.
 * `X-Platform`·`X-App-Build`를 읽을 수 없으면 통과하고, `X-OS-Version`을 읽을 수 없으면 OS 조건이 있는 규칙만 건너뛴다.
 */
@Component
class ForceUpdateGuardInterceptor(private val rules: ForceUpdateRules) : HandlerInterceptor {

	override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
		val client = ClientInfo(
			platform = parsePlatform(request.getHeader("X-Platform")),
			build = parseInt(request.getHeader("X-App-Build")),
			osVersion = parseInt(request.getHeader("X-OS-Version")),
		)
		val match = rules.findMatch(client) ?: return true
		throw ApiException(
			ErrorCode.APP_VERSION_UNSUPPORTED,
			details = UpdateRuleDetails(match.ruleId, match.title, match.body, match.image, match.imageAlt),
		)
	}

	companion object {
		fun parsePlatform(value: String?): AppPlatform? = AppPlatform.entries.firstOrNull { it.name == value }

		/** 0~2147483647 정수(숫자만, 최대 10자리)면 그 값, 아니면 null */
		fun parseInt(value: String?): Int? {
			if (value == null || value.length > 10 || !value.all { it in '0'..'9' }) return null
			val long = value.toLongOrNull() ?: return null
			return if (long <= Int.MAX_VALUE) long.toInt() else null
		}
	}
}
