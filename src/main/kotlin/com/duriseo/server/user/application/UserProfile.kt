package com.duriseo.server.user.application

import java.util.UUID

/** 다른 도메인이 사용자를 보여 줄 때 읽는 값: 닉네임과 프로필 사진 여부 (사진 파일은 단위 7) */
data class UserProfile(val userId: UUID, val nickname: String, val hasProfileImage: Boolean)
