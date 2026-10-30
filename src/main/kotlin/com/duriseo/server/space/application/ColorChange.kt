package com.duriseo.server.space.application

/**
 * `PATCH /space/colors` 요청 (API.md 3.2). 필드가 모두 선택이라 [members]는 비어 있을 수 있고, [together]는 없으면 null이다.
 * 값의 형식 검사(UUID, 팔레트 키)는 서비스가 R-08 뒤에 한다 (검사 순서). 그래서 값은 JSON에서 읽은 그대로 [Any]로 둔다.
 */
data class ColorChange(val members: List<MemberColorChange>, val togetherPresent: Boolean, val together: Any?)

/** `members[index]` 하나. [userId]·[color]는 JSON 값 그대로다 (null이 아님이 보장된 상태) */
data class MemberColorChange(val index: Int, val userId: Any, val color: Any)
