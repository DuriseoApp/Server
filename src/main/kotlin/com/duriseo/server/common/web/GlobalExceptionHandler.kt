package com.duriseo.server.common.web

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.ErrorDetails
import com.duriseo.server.common.api.ErrorResponse
import com.duriseo.server.common.api.FieldError
import com.duriseo.server.common.api.FieldErrorDetails
import com.duriseo.server.common.api.FieldErrorReason
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * 모든 오류를 API.md 1.1의 오류 객체로 바꾼다. 이 밖의 곳에서 오류 응답을 직접 만들지 않는다.
 * 사용자에게 보이는 문구는 앱이 code로 정한다. message는 로그용이라 내부 정보를 넣지 않는다.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

	private val log = LoggerFactory.getLogger(javaClass)

	@ExceptionHandler(ApiException::class)
	fun handleApi(e: ApiException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
		respond(request, e.code, e.message ?: e.code.name, e.details)

	@ExceptionHandler(MethodArgumentNotValidException::class)
	fun handleInvalidBody(e: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
		val fields = e.bindingResult.fieldErrors.map { FieldError(it.field, reasonOf(it.code)) }
		val message = fields.joinToString { "${it.field}: ${it.reason}" }
		return respond(request, ErrorCode.VALIDATION_FAILED, message, FieldErrorDetails(fields))
	}

	@ExceptionHandler(MissingServletRequestParameterException::class)
	fun handleMissingParam(e: MissingServletRequestParameterException, request: HttpServletRequest) =
		respond(
			request, ErrorCode.VALIDATION_FAILED, "${e.parameterName}: REQUIRED",
			FieldErrorDetails(listOf(FieldError(e.parameterName, FieldErrorReason.REQUIRED))),
		)

	@ExceptionHandler(MethodArgumentTypeMismatchException::class)
	fun handleTypeMismatch(e: MethodArgumentTypeMismatchException, request: HttpServletRequest) =
		respond(
			request, ErrorCode.VALIDATION_FAILED, "${e.name}: INVALID",
			FieldErrorDetails(listOf(FieldError(e.name, FieldErrorReason.INVALID))),
		)

	@ExceptionHandler(
		HttpMessageNotReadableException::class,
		HandlerMethodValidationException::class,
		HttpMediaTypeNotSupportedException::class,
	)
	fun handleUnreadable(e: Exception, request: HttpServletRequest) =
		respond(request, ErrorCode.VALIDATION_FAILED, "request body or parameters are invalid", FieldErrorDetails(emptyList()))

	@ExceptionHandler(MaxUploadSizeExceededException::class)
	fun handleTooLarge(e: MaxUploadSizeExceededException, request: HttpServletRequest) =
		respond(request, ErrorCode.IMAGE_TOO_LARGE, "upload too large", null)

	/** 없는 경로와 지원하지 않는 메서드는 모두 404 NOT_FOUND (리소스 존재를 드러내지 않는다, ERD DB-05) */
	@ExceptionHandler(
		NoResourceFoundException::class,
		NoHandlerFoundException::class,
		HttpRequestMethodNotSupportedException::class,
	)
	fun handleNotFound(e: Exception, request: HttpServletRequest) =
		respond(request, ErrorCode.NOT_FOUND, "not found", null)

	@ExceptionHandler(Exception::class)
	fun handleUnexpected(e: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
		log.error("Unhandled exception", e)
		return respond(request, ErrorCode.INTERNAL_ERROR, "internal error", null)
	}

	private fun respond(
		request: HttpServletRequest,
		code: ErrorCode,
		message: String,
		details: ErrorDetails?,
	): ResponseEntity<ErrorResponse> =
		ResponseEntity.status(code.status)
			.body(ErrorResponse(code, message, details, TraceIdFilter.of(request)))

	private fun reasonOf(constraint: String?): FieldErrorReason = when (constraint) {
		"NotNull", "NotBlank", "NotEmpty" -> FieldErrorReason.REQUIRED
		"Size", "Length" -> FieldErrorReason.TOO_LONG
		else -> FieldErrorReason.INVALID
	}
}
