package com.duriseo.server.space.api

import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.FieldError
import com.duriseo.server.common.api.FieldErrorDetails
import com.duriseo.server.common.api.FieldErrorReason
import com.duriseo.server.space.application.ColorChange
import com.duriseo.server.space.application.MemberColorChange

/**
 * `PATCH /space/colors` 본문 `{members?: [{userId, color}], together?}` (API.md 3.2). 필드는 모두 선택이다.
 * "필드가 없음"과 `null`을 구분해야 해서 본문을 `Map`으로 받아 여기서 검사한다.
 *
 * 이 클래스는 검사 순서 1단계(필드 누락·null)만 한다:
 * `members`와 `together`가 둘 다 없거나, `members`가 빈 배열이고 `together`도 없으면 `members` `REQUIRED`.
 * `members`·`together`·`members[i].userId`·`members[i].color`가 `null`이면 그 필드가 `REQUIRED`.
 * 값의 뜻(UUID, 팔레트 키)은 R-08 뒤에 서비스가 본다 (2~3단계).
 */
object UpdateColorsRequest {

	private const val MEMBERS = "members"
	private const val TOGETHER = "together"

	fun parse(body: Map<String, Any?>): ColorChange {
		val errors = mutableListOf<FieldError>()
		val members = mutableListOf<MemberColorChange>()

		val membersPresent = body.containsKey(MEMBERS)
		val togetherPresent = body.containsKey(TOGETHER)
		val rawMembers = body[MEMBERS]
		val rawTogether = body[TOGETHER]

		if (membersPresent) {
			when {
				rawMembers == null -> errors += FieldError(MEMBERS, FieldErrorReason.REQUIRED)
				rawMembers !is List<*> -> errors += FieldError(MEMBERS, FieldErrorReason.INVALID)
				else -> rawMembers.forEachIndexed { index, item -> parseMember(index, item, members, errors) }
			}
		}
		if (togetherPresent && rawTogether == null) errors += FieldError(TOGETHER, FieldErrorReason.REQUIRED)

		// 둘 다 없거나 members가 빈 배열이고 together도 없으면 members REQUIRED (null 필드가 이미 오류면 겹치지 않게 한다)
		val noMembers = !membersPresent || (rawMembers is List<*> && rawMembers.isEmpty())
		if (noMembers && !togetherPresent && errors.none { it.field == MEMBERS }) errors += FieldError(MEMBERS, FieldErrorReason.REQUIRED)

		if (errors.isNotEmpty()) {
			throw ApiException(
				ErrorCode.VALIDATION_FAILED,
				errors.joinToString { "${it.field}: ${it.reason}" },
				FieldErrorDetails(errors),
			)
		}
		return ColorChange(members, togetherPresent, rawTogether)
	}

	private fun parseMember(index: Int, item: Any?, members: MutableList<MemberColorChange>, errors: MutableList<FieldError>) {
		if (item !is Map<*, *>) {
			// 원소가 null이면 REQUIRED, 객체가 아닌 다른 값이면 INVALID
			errors += FieldError("$MEMBERS[$index]", if (item == null) FieldErrorReason.REQUIRED else FieldErrorReason.INVALID)
			return
		}
		// JSON 객체는 키가 항상 문자열이다
		@Suppress("UNCHECKED_CAST")
		val fields = item as Map<String, Any?>
		val userId = fields["userId"]
		val color = fields["color"]
		if (userId == null) errors += FieldError("$MEMBERS[$index].userId", FieldErrorReason.REQUIRED)
		if (color == null) errors += FieldError("$MEMBERS[$index].color", FieldErrorReason.REQUIRED)
		if (userId != null && color != null) members += MemberColorChange(index, userId, color)
	}
}
