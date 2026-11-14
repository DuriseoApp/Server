package com.duriseo.server.user.application

import java.util.UUID

/**
 * 사용자의 프로필 사진이 교체·삭제돼 이전 파일([previousVersion] = 이전 `users.profile_image_key`)이 더는 쓰이지 않는다.
 * 키를 바꾼 트랜잭션 안에서 발행하고, 파일은 커밋 뒤 `ProfileImageFileListener`가 best-effort로 지운다 (API.md 8장).
 * 지우지 못한 파일은 매일 정리 작업 8단계가 회수한다 (ERD 4.4).
 */
data class ProfileImageFileObsolete(val userId: UUID, val previousVersion: String)
