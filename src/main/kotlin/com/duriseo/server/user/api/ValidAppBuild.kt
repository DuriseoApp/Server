package com.duriseo.server.user.api

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import java.math.BigInteger
import kotlin.reflect.KClass

/**
 * `PUT /me/devices`의 `appBuild`: 없거나 `null`이면 통과, 아니면 0~2147483647 정수여야 한다 (API.md 3.1, FR-APP-03).
 * 문자열·소수·boolean처럼 정수가 아닌 값도 JSON 읽기 오류가 아니라 필드 이름이 붙은 `INVALID`로 답하려고 값을 `Any?`로 받아 여기서 본다.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [AppBuildValidator::class])
annotation class ValidAppBuild(
	val message: String = "appBuild must be an integer between 0 and 2147483647",
	val groups: Array<KClass<*>> = [],
	val payload: Array<KClass<out Payload>> = [],
)

class AppBuildValidator : ConstraintValidator<ValidAppBuild, Any?> {

	override fun isValid(value: Any?, context: ConstraintValidatorContext): Boolean = value == null || toBuild(value) != null

	companion object {
		/** 0~2147483647 정수면 그 값, 아니면 null */
		fun toBuild(value: Any?): Int? {
			val long = when (value) {
				is Int -> value.toLong()
				is Long -> value
				is BigInteger -> if (value.bitLength() < 63) value.toLong() else return null
				else -> return null
			}
			return if (long in 0..Int.MAX_VALUE.toLong()) long.toInt() else null
		}
	}
}
