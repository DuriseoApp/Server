package com.duriseo.server.auth.domain

/** auth_sessions.revoked_reason (ERD 3.1) */
enum class SessionRevokeReason {
	/** 사용자가 로그아웃함 */
	LOGOUT,

	/** 다른 기기 로그인으로 끊김 (로그인 3종) */
	REPLACED,

	/** 탈퇴로 무효가 됨 (2부) */
	WITHDRAWN,
}
