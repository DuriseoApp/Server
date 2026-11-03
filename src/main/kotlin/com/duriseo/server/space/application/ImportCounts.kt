package com.duriseo.server.space.application

/**
 * 가져오기 대기 중인 개인 공간의 기록 개수 (API.md 3.1 `ImportCounts`, PRD D-93).
 * 서버는 요청 때 대기 공간의 `events`, `anniversaries`(직접 기념일 행 수. 자동 기념일은 저장하지 않으므로 세지 않는다), `memos` 행 수를 센다.
 * 따로 저장하지 않는다 (ERD 4.1).
 */
data class ImportCounts(val events: Int, val anniversaries: Int, val memos: Int)
