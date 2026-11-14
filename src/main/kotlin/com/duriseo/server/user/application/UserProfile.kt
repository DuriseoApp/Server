package com.duriseo.server.user.application

import java.util.UUID

/** 다른 도메인이 사용자를 보여 줄 때 읽는 값: 닉네임과 프로필 사진 (`profileImageVersion` = `users.profile_image_key`, 없으면 null) */
data class UserProfile(val userId: UUID, val nickname: String, val profileImageVersion: String?) {
	val hasProfileImage: Boolean get() = profileImageVersion != null
}
