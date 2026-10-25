package com.duriseo.server.common.api

/*
 * 응답 형식 (Docs docs/API.md 1.1)
 * - 성공: 봉투 없이 리소스 그대로. 내용이 없으면 204
 * - 목록: 최상위는 항상 객체. 배열을 그대로 돌려주지 않는다
 * - 오류: ErrorResponse. 오류를 200으로 돌려주지 않는다
 * - 값이 없는 필드는 빼지 않고 null로 보낸다 (Jackson 기본 동작을 바꾸지 않는다)
 */

/** 페이지가 없는 목록: `{"items": [...]}` */
data class ListResponse<T>(val items: List<T>)

/** 페이지가 있는 목록: `{"items": [...], "nextCursor": "..."}`. 마지막 페이지면 nextCursor = null */
data class PageResponse<T>(val items: List<T>, val nextCursor: String?)

/** 개수가 필요한 경로만 쓴다 (예: GET /anniversaries/past) */
data class CountedPageResponse<T>(val items: List<T>, val nextCursor: String?, val totalCount: Long)

/** 오류 객체: `{code, message, details, traceId}` */
data class ErrorResponse(
	val code: ErrorCode,
	val message: String,
	val details: ErrorDetails?,
	val traceId: String,
)

/** `details`에 들어갈 수 있는 모양. API.md 1.1 표에 있는 것만 둔다 */
sealed interface ErrorDetails

/** VALIDATION_FAILED, DATE_OUT_OF_RANGE */
data class FieldErrorDetails(val fields: List<FieldError>) : ErrorDetails

data class FieldError(val field: String, val reason: FieldErrorReason)

enum class FieldErrorReason { REQUIRED, TOO_LONG, INVALID, OUT_OF_RANGE }

/** INVITE_REISSUE_TOO_SOON, INVITE_RATE_LIMITED */
data class RetryDetails(val retryAfterSeconds: Long) : ErrorDetails

/** 서비스 코드에서 던지는 예외. GlobalExceptionHandler가 오류 객체로 바꾼다 */
class ApiException(
	val code: ErrorCode,
	message: String = code.name,
	val details: ErrorDetails? = null,
) : RuntimeException(message)
