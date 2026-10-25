package com.duriseo.server.common.web

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.RetryDetails
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** 오류 응답이 API.md 1.1 오류 객체 모양인지 확인한다 */
class GlobalExceptionHandlerTest {

	private lateinit var mockMvc: MockMvc

	@BeforeEach
	fun setUp() {
		val validator = LocalValidatorFactoryBean().apply { afterPropertiesSet() }
		mockMvc = MockMvcBuilders.standaloneSetup(TestController())
			.setControllerAdvice(GlobalExceptionHandler())
			.setValidator(validator)
			.addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(TraceIdFilter())
			.build()
	}

	@Test
	fun `ApiException은 코드의 상태와 오류 객체로 바뀐다`() {
		mockMvc.perform(get("/test/not-author"))
			.andExpect(status().isForbidden)
			.andExpect(jsonPath("$.code").value("NOT_AUTHOR"))
			.andExpect(jsonPath("$.details").doesNotExist())
			.andExpect(jsonPath("$.traceId").isNotEmpty)
			.andExpect(header().exists(TraceIdFilter.HEADER))
	}

	@Test
	fun `429는 details에 retryAfterSeconds를 담는다`() {
		mockMvc.perform(get("/test/too-soon"))
			.andExpect(status().isTooManyRequests)
			.andExpect(jsonPath("$.code").value("INVITE_REISSUE_TOO_SOON"))
			.andExpect(jsonPath("$.details.retryAfterSeconds").value(18))
	}

	@Test
	fun `입력 검증 실패는 VALIDATION_FAILED와 필드 목록`() {
		mockMvc.perform(
			post("/test/nickname")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"nickname": "열한글자가넘는닉네임이에요"}"""),
		)
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.details.fields[0].field").value("nickname"))
			.andExpect(jsonPath("$.details.fields[0].reason").value("TOO_LONG"))
	}

	@Test
	fun `처리하지 못한 예외는 500 INTERNAL_ERROR`() {
		mockMvc.perform(get("/test/boom"))
			.andExpect(status().isInternalServerError)
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.message").value("internal error"))
	}

	@RestController
	class TestController {
		@GetMapping("/test/not-author")
		fun notAuthor(): String = throw ApiException(ErrorCode.NOT_AUTHOR)

		@GetMapping("/test/too-soon")
		fun tooSoon(): String = throw ApiException(ErrorCode.INVITE_REISSUE_TOO_SOON, details = RetryDetails(18))

		@PostMapping("/test/nickname")
		fun nickname(@Valid @RequestBody body: NicknameRequest): String = body.nickname

		@GetMapping("/test/boom")
		fun boom(): String = throw IllegalStateException("secret internal detail")
	}

	data class NicknameRequest(
		@field:NotBlank @field:Size(max = 10) val nickname: String,
	)
}
