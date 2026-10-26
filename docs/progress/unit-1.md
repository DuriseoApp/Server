# 단위 1: 계정 (auth)

> 브랜치: `feat/1-auth` · PR: (링크) · 상태: 리뷰 중 (구현 1부·2부 완료)
> 기준: PRD v0.6, API.md 1장·2장(R-01·R-09·R-11·R-14·R-17)·3.1·4.1~4.3·4.6~4.7·5장, ERD 3.1·3.2·4.1·4.3·4.5·4.6·5장
> Docs 기준 커밋: DuriseoApp/Docs main `e7a31ad` (2026-10-26)

## 범위

### 1. 기능 (FR-AUTH)과 수용 기준

AC 하나에 테스트 하나 이상 (CONVENTIONS). "앱 동작"은 서버 API가 없어 서버 테스트가 없다. "단위 N"은 그 단위에서 테스트한다.

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-AUTH-01 AC1 | 게스트로 시작하면 서버에 UUID 게스트 계정과 개인 공간이 생긴다 (API 4.1, ERD 4.1) | `FR-AUTH-01 AC1 게스트 시작은 GUEST 사용자와 개인 공간을 만든다` (users GUEST·닉네임 "게스트"·`time_zone` Asia/Seoul, notification_settings 4개 모두 true, spaces PERSONAL·ACTIVE, space_members `pick-orange`, auth_sessions 1행) |
| | | `FR-AUTH-01 AC1 게스트 시작의 refresh 토큰은 365일 뒤 만료된다` |
| | | `FR-AUTH-01 AC1 게스트 시작의 access 토큰에는 새 세션 ID가 sid로 들어 있다` |
| FR-AUTH-01 AC2 | 저장된 인증 정보로 같은 게스트에 자동으로 들어간다 | `FR-AUTH-01 AC2 저장한 refresh 토큰으로 같은 게스트 계정의 새 토큰을 받는다` |
| FR-AUTH-01 AC3 | 앱 삭제·기기 변경 뒤에는 이전 게스트에 접근할 방법이 없다 (앱 동작) | `FR-AUTH-01 AC3 게스트 시작은 부를 때마다 새 계정을 만든다` (복구 경로가 없음을 서버 쪽에서 확인) |
| FR-AUTH-02 AC1 | 처음 쓰는 Google·Kakao 계정이면 회원과 개인 공간이 생기고 `isNewUser: true` | `FR-AUTH-02 AC1 처음 쓰는 Google 계정으로 로그인하면 회원과 개인 공간을 만들고 isNewUser는 true다` |
| | | `FR-AUTH-02 AC1 처음 쓰는 Kakao 계정으로 로그인하면 회원과 개인 공간을 만들고 isNewUser는 true다` |
| | | `FR-AUTH-02 AC1 회원의 refresh 토큰은 30일 뒤 만료된다` |
| FR-AUTH-02 AC2 | 이미 회원인 소셜 계정은 다른 기기·재설치에서도 같은 계정·공간으로 들어간다 | `FR-AUTH-02 AC2 이미 가입한 소셜 계정으로 로그인하면 같은 사용자와 공간으로 들어가고 isNewUser는 false다` |
| FR-AUTH-02 AC3 | 다른 기기에서 로그인하면 이전 기기의 로그인과 푸시가 끊긴다 (D-51, API 4.2 4단계, DB-09) | `FR-AUTH-02 AC3 다른 기기에서 로그인하면 이전 세션은 REPLACED가 되고 devices가 모두 지워진다` |
| | | `FR-AUTH-02 AC3 이전 기기의 access 토큰 요청은 401 SESSION_REPLACED` |
| | | `FR-AUTH-02 AC3 이전 기기의 refresh 요청은 401 SESSION_REPLACED` |
| | | `FR-AUTH-02 AC3 로그인한 사용자에게 유효한 세션은 하나뿐이다` |
| FR-AUTH-03 AC1 | 게스트가 연동하면 같은 계정이 회원이 되고 기록이 유지된다. 닉네임이 정확히 "게스트"일 때만 소셜 이름으로 (D-68, D-69) | `FR-AUTH-03 AC1 게스트가 연동하면 같은 사용자 ID로 MEMBER가 되고 공간과 기록이 그대로다` |
| | | `FR-AUTH-03 AC1 닉네임이 "게스트"면 연동할 때 소셜 프로필 이름(앞 10자)으로 바뀐다` |
| | | `FR-AUTH-03 AC1 직접 바꾼 닉네임은 연동해도 그대로다` |
| | | `FR-AUTH-03 AC1 소셜 이름이 없거나 공백뿐이면 연동할 때 닉네임은 "사용자"가 된다` |
| | | `FR-AUTH-03 AC1 연동은 현재 세션·sid·devices를 그대로 두고 새 토큰을 주지 않는다` |
| | | `FR-AUTH-03 AC1 연동 뒤 첫 refresh부터 만료가 30일이다` |
| FR-AUTH-03 AC2 | 이미 다른 회원이 쓰는 소셜 계정이면 연동하지 않고 데이터도 합치지 않는다 (Q-01) | `FR-AUTH-03 AC2 이미 다른 회원이 쓰는 소셜 계정으로 연동하면 409 SOCIAL_ALREADY_USED이고 아무것도 바뀌지 않는다` (`uq_social_accounts_provider_user` 위반으로 판정) |
| FR-AUTH-03 AC3 | [그 계정으로 로그인]: 게스트와 기록을 탈퇴처럼 지우고 그 회원으로 로그인 (D-67, API 4.7, DB-12) | `FR-AUTH-03 AC3 switch는 게스트 계정과 기록을 지우고 기존 회원으로 로그인한다` (isNewUser false, 게스트 익명화, 게스트 개인 공간 삭제) |
| | | `FR-AUTH-03 AC3 switch 뒤 이전 게스트 access 토큰은 401 SESSION_INVALID` |
| | | `FR-AUTH-03 AC3 switch는 그 회원의 다른 기기 세션을 REPLACED로 끊고 devices를 지운다` |
| | | `FR-AUTH-03 AC3 그 소셜 계정으로 가입한 회원이 없으면 404 SOCIAL_NOT_REGISTERED이고 게스트는 그대로다` |
| | | `FR-AUTH-03 AC3 대상 회원이 그 사이 탈퇴했으면 롤백하고 404 SOCIAL_NOT_REGISTERED` |
| | | `FR-AUTH-03 AC3 switch 도중 실패하면 게스트 계정이 그대로 남는다` |
| FR-AUTH-03 AC4 | [로그인하고 연결하기]로 연동했으면 SCR-COUPLE-01로 (D-79) | 앱 동작 (API 차이 없음, API 4.3 6단계) |
| FR-AUTH-04 AC1·AC2 | 게스트 데이터 안내, 게스트의 커플 연결 메뉴 안내 | 앱 동작. 서버 쪽 막기는 R-09 (아래) |
| FR-AUTH-05 AC1 | 로그아웃: 세션 무효화, 그 기기 푸시 토큰 삭제, 서버 데이터 유지 (D-50) | `FR-AUTH-05 AC1 로그아웃하면 현재 세션이 LOGOUT이 되고 devices가 모두 지워진다` |
| | | `FR-AUTH-05 AC1 로그아웃 뒤 같은 access 토큰은 401 SESSION_INVALID` |
| | | `FR-AUTH-05 AC1 로그아웃 뒤 refresh는 401 SESSION_INVALID` |
| | | `FR-AUTH-05 AC1 로그아웃해도 사용자와 공간 데이터는 남고 다시 로그인하면 그대로다` |
| FR-AUTH-05 AC2 | 게스트에게는 로그아웃이 없다 | `FR-AUTH-05 AC2 게스트가 로그아웃을 부르면 403 GUEST_NOT_ALLOWED` (= R-17 거부) |
| FR-AUTH-05 AC3 | "다른 계정으로 로그인"은 같은 로그아웃 호출 (D-80) | 앱 동작 (FR-AUTH-05 AC1 테스트로 충분) |
| FR-AUTH-06 AC1 | 개인 공간 사용자(게스트 포함) 탈퇴: 계정과 개인 공간 데이터 즉시 삭제 (ERD 4.3) | `FR-AUTH-06 AC1 개인 공간 회원이 탈퇴하면 개인 공간과 콘텐츠가 지워지고 사용자는 익명화된다` (`deleted_at`, 닉네임 "탈퇴한 사용자", `profile_image_key` null) |
| | | `FR-AUTH-06 AC1 게스트도 탈퇴할 수 있다` |
| | | `FR-AUTH-06 AC1 탈퇴 뒤 social_accounts·auth_sessions·devices·notification_settings·notifications·memo_favorites·event_reminders·유효한 invite_codes가 남지 않는다` |
| | | `FR-AUTH-06 AC1 탈퇴할 때 세션은 WITHDRAWN으로 무효화한 뒤 지운다` |
| | | `FR-AUTH-06 AC1 탈퇴 뒤 access 토큰은 401 SESSION_INVALID` |
| | | `FR-AUTH-06 AC1 탈퇴 뒤 같은 소셜 계정으로 로그인하면 새 회원이 된다` |
| | | `FR-AUTH-06 AC1 탈퇴하면 가져오기 대기 중인 개인 공간도 지운다` (픽스처) |
| FR-AUTH-06 AC2 | 커플 공간 사용자 탈퇴: 연결 해제 → 탈퇴 전용 해제 알림 → 커플 공간 90일 보관 | **일부만 단위 1** (확인 필요 10). 단위 1: `FR-AUTH-06 AC2 탈퇴하면 속했던 UNLINKED 커플 공간의 purge_at을 탈퇴 시각 + 90일로 늦춘다`, `FR-AUTH-06 AC2 purge_at이 이미 90일보다 늦으면 그대로 둔다` (픽스처). 연결 해제·`COUPLE_UNLINKED` 알림 행은 단위 2, 푸시·SYNC는 단위 6 |
| FR-AUTH-06 AC3 | Kakao 회원 탈퇴 시 Kakao 앱 연결 끊기 (커밋 뒤, 실패해도 되돌리지 않음, API 4.6) | `FR-AUTH-06 AC3 Kakao 회원이 탈퇴하면 커밋 뒤 provider_user_id로 Kakao 연결 끊기를 부른다` |
| | | `FR-AUTH-06 AC3 Kakao 연결 끊기가 실패하거나 시간이 초과돼도 탈퇴는 되돌리지 않는다` |
| | | `FR-AUTH-06 AC3 Google 회원과 게스트는 Kakao 연결 끊기를 부르지 않는다` |
| | | `FR-AUTH-06 AC3 탈퇴 트랜잭션이 실패하면 Kakao 연결 끊기를 부르지 않는다` |
| FR-AUTH-07 AC1 | 소셜 가입 닉네임 = 소셜 프로필 이름, 10자 초과면 앞 10자, 이름 없으면 "사용자", 게스트는 "게스트" (D-56, D-69) | `FR-AUTH-07 AC1 소셜 가입 닉네임은 소셜 프로필 이름이다` |
| | | `FR-AUTH-07 AC1 소셜 이름이 10자를 넘으면 앞 10자만 쓴다` |
| | | `FR-AUTH-07 AC1 소셜 이름이 없거나 공백뿐이면 닉네임은 "사용자"다` |
| | | `FR-AUTH-07 AC1 게스트의 기본 닉네임은 "게스트"다` |
| FR-AUTH-07 AC2 | 닉네임을 바꾸면 상대 화면에도 보인다 | `FR-AUTH-07 AC2 PATCH /me로 닉네임을 바꾸면 GET /me에 반영된다` |
| | | `FR-AUTH-07 AC2 닉네임이 10자를 넘으면 400 VALIDATION_FAILED (nickname TOO_LONG)` |
| | | `FR-AUTH-07 AC2 빈 닉네임은 400 VALIDATION_FAILED (nickname REQUIRED)` (확인 필요 12) |
| | | 상대 화면 쪽(`GET /space` 구성원 닉네임)은 단위 2 |
| FR-AUTH-07 AC3·AC5 | 프로필 사진 저장·삭제 | 단위 7 |
| FR-AUTH-07 AC4 | 사진 없으면 글자 아바타 | 앱 동작 |
| FR-AUTH-08 AC1·AC2 | 이용 동의 문구·링크 | 앱·웹 (API 없음) |
| FR-AUTH-09 AC1~AC3 | 앱 밖 계정 삭제 안내·메일 요청 | 웹·운영자 (API 없음, 처리 방식은 FR-AUTH-06과 같음) |

### 2. 토큰·세션 규칙 (API.md 1장, NFR-SEC-01, D-73)

| ID | 내용 | 테스트 |
| --- | --- | --- |
| NFR-SEC-01 | access 1시간 | `NFR-SEC-01 1시간이 지난 access 토큰은 401 TOKEN_EXPIRED` (Clock 고정) |
| NFR-SEC-01 | refresh는 같은 세션 행을 갱신, `sid` 불변 (DB-02) | `NFR-SEC-01 refresh는 같은 세션 행의 해시·만료·last_used_at만 바꾸고 sid는 그대로다` |
| NFR-SEC-01 | 이전 refresh 토큰 재사용 | `NFR-SEC-01 이미 교체된 refresh 토큰을 다시 보내면 401 SESSION_INVALID` |
| NFR-SEC-01 | 쓸 때마다 연장 | `NFR-SEC-01 refresh는 만료를 회원 30일·게스트 365일로 다시 늘린다` |
| NFR-SEC-01 | 만료 | `NFR-SEC-01 만료된 refresh 토큰은 401 SESSION_INVALID` |
| NFR-SEC-01 | 해시만 저장 | `NFR-SEC-01 auth_sessions에는 refresh 토큰의 SHA-256 해시만 저장한다` |
| NFR-SEC-01 | 요청마다 세션 검사 (API 1장) | `NFR-SEC-01 서명·만료가 맞아도 세션이 무효면 401 SESSION_INVALID` |
| NFR-SEC-01 | 토큰 없음·위조 (확인 필요 5) | `NFR-SEC-01 Authorization 헤더가 없으면 401 <확인 필요 5>`, `NFR-SEC-01 서명이 틀린 access 토큰은 401 <확인 필요 5>` |
| NFR-SEC-01 | 공개 경로 | `NFR-SEC-01 /auth/guest, /auth/social, /auth/refresh는 access 토큰 없이 부를 수 있다` |
| NFR-SEC-01 | Google 검증 (API 4.2 2단계) | `NFR-SEC-01 Google ID 토큰의 aud가 둘이서 클라이언트 ID가 아니면 401 SOCIAL_TOKEN_INVALID`, `... iss가 틀리면 ...`, `... 만료됐으면 ...`, `... 서명이 틀리면 ...` |
| NFR-SEC-01 | Kakao 검증 | `NFR-SEC-01 Kakao 토큰의 app_id가 둘이서 앱 ID가 아니면 401 SOCIAL_TOKEN_INVALID`, `NFR-SEC-01 Kakao가 토큰을 거부하면 401 SOCIAL_TOKEN_INVALID` |
| NFR-SEC-01 | 실패 시 무변경 | `NFR-SEC-01 소셜 토큰 검증이 실패하면 social·link·switch 모두 아무 행도 바꾸지 않는다` |
| NFR-PRIV-01 | 소셜에서 식별자·이름만 | `NFR-PRIV-01 소셜 로그인은 이메일을 저장하거나 로그에 남기지 않는다` (Google ID 토큰에 email이 들어 있음) |
| NFR-TIME-01 | `PATCH /me` 시간대 | `NFR-TIME-01 PATCH /me로 시간대를 바꾸면 users.time_zone이 바뀐다`, `NFR-TIME-01 IANA가 아닌 시간대는 400 VALIDATION_FAILED (timeZone INVALID)` |
| FR-SET-01 / FR-COUPLE-02 AC5 | `GET /me` 요약 | `FR-SET-01 GET /me는 내 정보와 개인 공간 요약을 돌려준다` (상대 null, 처음 만난 날 null, importPending false), `FR-COUPLE-02 AC5 GET /me는 커플 공간이면 COUPLE 종류와 상대·사람 색을 돌려준다` (픽스처), `FR-COUPLE-03 AC4 GET /me는 가져오기 대기 중인 개인 공간이 있으면 importPending이 true다` (픽스처) |

### 3. 권한 규칙 (API.md 2장) — `SpaceAccessPolicy`

`common` 아래 `SpaceAccessPolicy` 한 곳에 만든다 (ARCHITECTURE 3장). R-01·R-09·R-11은 단위 1에 쓰는 경로가 없으므로 정책 컴포넌트 테스트(픽스처, Testcontainers)로 허용·거부를 확인하고, 경로 테스트는 그 경로의 단위(2·3·5·7)에서 더한다. R-14·R-17은 경로 테스트까지 단위 1에서 한다. **계정 종류는 토큰이 아니라 DB(`users.account_type`)에서 읽는다** (연동 직후 같은 access 토큰으로 판단이 바뀌어야 함).

| ID | 내용 | 테스트 |
| --- | --- | --- |
| R-01 | 콘텐츠 리소스의 `space_id` = 요청자의 활성 공간(`left_at IS NULL`), 아니면 404 `NOT_FOUND` | 허용: `R-01 요청자의 활성 공간 리소스는 허용한다` |
| | | 거부: `R-01 다른 공간의 리소스는 404 NOT_FOUND` |
| | | 거부: `R-01 떠난 공간(left_at 있음)의 리소스는 404 NOT_FOUND` |
| | | 거부: `R-01 없는 리소스와 다른 공간의 리소스는 같은 404 응답이다` |
| R-09 | 초대코드 생성·입력: MEMBER이고 개인 공간 | 허용: `R-09 개인 공간의 회원은 허용한다` |
| | | 거부: `R-09 게스트는 403 GUEST_NOT_ALLOWED` |
| | | 거부: `R-09 커플 공간의 회원은 409 ALREADY_COUPLED` |
| R-11 | 프로필 사진 조회: 본인 또는 같은 활성 공간 구성원, 아니면 404 | 허용: `R-11 본인은 자기 프로필 사진에 접근할 수 있다` |
| | | 허용: `R-11 같은 활성 공간의 상대는 프로필 사진에 접근할 수 있다` |
| | | 거부: `R-11 다른 공간의 사용자는 404 NOT_FOUND` |
| | | 거부: `R-11 연결이 해제된 이전 상대는 404 NOT_FOUND` |
| | | 거부: `R-11 없는 사용자와 탈퇴한 사용자는 404 NOT_FOUND` |
| R-14 | `/auth/link`, `/auth/switch`는 GUEST만, 회원이면 403 `GUEST_ONLY`이고 아무것도 바꾸지 않음. 소셜 토큰 검증보다 먼저 (API 4.7 2단계) | 허용: `R-14 게스트는 /auth/link를 부를 수 있다`, `R-14 게스트는 /auth/switch를 부를 수 있다` |
| | | 거부: `R-14 회원이 /auth/link를 부르면 403 GUEST_ONLY이고 아무것도 바뀌지 않는다` |
| | | 거부: `R-14 회원이 /auth/switch를 부르면 403 GUEST_ONLY이고 아무것도 바뀌지 않는다` |
| | | 거부: `R-14 연동한 직후 같은 access 토큰으로 /auth/link를 다시 부르면 403 GUEST_ONLY` |
| R-17 | `/auth/logout`은 MEMBER만, 게스트면 403 `GUEST_NOT_ALLOWED`이고 아무것도 바꾸지 않음 | 허용: `R-17 회원은 로그아웃할 수 있다` |
| | | 허용: `R-17 연동한 게스트는 같은 access 토큰으로 로그아웃할 수 있다` |
| | | 거부: `R-17 게스트가 로그아웃을 부르면 403 GUEST_NOT_ALLOWED이고 세션과 devices가 그대로다` |

### 4. API 경로

모든 경로는 `/api/v1` 아래 (`WebConfig`가 붙임). 필드 이름을 Docs가 정하지 않은 곳은 *(미정)* 으로 두고 확인 필요에 올렸다.

| 메서드·경로 | 요청 | 성공 응답 | 오류 | 흐름·규칙 |
| --- | --- | --- | --- | --- |
| `POST /auth/guest` | 없음 | 201? *(미정, 확인 필요 1)* access·refresh 토큰 | — | API 4.1, ERD 4.1. 로그인 3종 공통 "이전 세션·기기 정리" 코드를 거친다 (새 사용자라 정리할 것이 없음) |
| `POST /auth/social` | `{provider: GOOGLE\|KAKAO, token}` (Google ID 토큰 / Kakao 액세스 토큰) | 200 access·refresh 토큰, `isNewUser` *(이름 외 미정)* | 401 `SOCIAL_TOKEN_INVALID`, 400 `VALIDATION_FAILED` | API 4.2. 같은 트랜잭션에서 다른 유효 세션 `REPLACED`, `devices` 전부 삭제 |
| `POST /auth/link` | `{provider, token}` + 게스트 access 토큰 | 성공 본문 *(미정: 204 추정, 확인 필요 11)*. 새 토큰 없음 | 403 `GUEST_ONLY`(R-14), 401 `SOCIAL_TOKEN_INVALID`, 409 `SOCIAL_ALREADY_USED` | API 4.3. 세션·`devices` 그대로, 닉네임 규칙 D-68·D-69 |
| `POST /auth/switch` | `{provider, token}` + 게스트 access 토큰 | `/auth/social`과 같음 (`isNewUser: false`) | 403 `GUEST_ONLY`, 401 `SOCIAL_TOKEN_INVALID`, 404 `SOCIAL_NOT_REGISTERED` | API 4.7, ERD 4.6, DB-12. 한 트랜잭션: `social_accounts` 재조회 + 대상 회원 행 `FOR UPDATE` → 게스트 탈퇴 처리(세션 WITHDRAWN 후 삭제, Kakao 끊기 없음) → 정리 → 새 세션 |
| `POST /auth/refresh` | refresh 토큰 *(전달 방식 미정, 확인 필요 2)* | 200 새 access·refresh 토큰, `sid` 그대로 | 401 `SESSION_REPLACED`, 401 `SESSION_INVALID` | API 1장, DB-02. 연동한 게스트는 이때부터 30일 |
| `POST /auth/logout` | 없음 | 204 | 403 `GUEST_NOT_ALLOWED`(R-17) | 세션 `LOGOUT`, 그 사용자의 `devices` 전부 삭제 (DB-09) |
| `GET /me` | 없음 | 200 내 정보 + 현재 공간 요약: 종류, 공간 ID, 상대, 사람 색, 처음 만난 날, `importPending` *(필드 이름·모양 미정, 확인 필요 11)* | 401 계열 | FR-SET-01, API 4.4 8단계(연결 확인 폴링). 로그인 수단 표시(FR-SET-01 AC1)용 필드가 필요 |
| `PATCH /me` | `{nickname?, timeZone?}` *(필드 이름 추정)* | 200 *(본문 미정)* | 400 `VALIDATION_FAILED` (닉네임 10자, 시간대 형식) | FR-AUTH-07, NFR-LIMIT-01. 시간대 변경은 단위 3의 알림 시각 재계산 연결점 |
| `DELETE /me` | 없음 | 204 *(추정)* | 401 계열 | API 4.6, ERD 4.3. 단위 1은 개인 공간 사용자 경로 (확인 필요 10) |
| `PUT /me/devices` | `{fcmToken, platform?}` *(필드 미정)* | *(미정)* | 400 | **단위 1인지 단위 6인지 확인 필요 9.** 같은 토큰이 다른 사용자로 오면 그 사용자로 옮긴다 (ERD devices) |

범위 밖 경로: `GET/PATCH /me/notification-settings`(단위 6, 확인 필요 9), `PUT/DELETE /me/profile-image`, `GET /users/{id}/profile-image`(단위 7).

### 5. ERD 테이블과 수명주기

단위 1이 쓰는 테이블은 모두 V1(`V1__init_schema.sql`)에 있다. **새 마이그레이션은 없을 예정**이다 (아래 확인 필요 16을 채택하면 V2 하나).

| 테이블 | 단위 1에서 하는 일 | 사용하는 제약 |
| --- | --- | --- |
| users | 게스트·회원 생성, `account_type` GUEST→MEMBER, `nickname`·`time_zone` 수정, 탈퇴 익명화(`deleted_at`, "탈퇴한 사용자", `profile_image_key` null) | `ck_users_account_type` |
| social_accounts | 생성, 조회, 탈퇴·switch 때 삭제 | `uq_social_accounts_provider_user` 위반 → 409 `SOCIAL_ALREADY_USED`, `uq_social_accounts_user_provider` |
| auth_sessions | 생성, refresh 교체(같은 행), `REPLACED`/`LOGOUT`/`WITHDRAWN` 무효화, 탈퇴 때 삭제 | `uq_auth_sessions_refresh_token_hash`, `ck_auth_sessions_revoked_reason`, `revoked_at`·`revoked_reason` 동시 기록(서비스) |
| devices | 로그인 3종·로그아웃·탈퇴 때 사용자 행 전부 삭제. 등록은 확인 필요 9 | `uq_devices_fcm_token` |
| notification_settings | 사용자 생성 때 기본값(모두 true)으로 생성, 탈퇴 때 삭제 | PK `user_id` |
| spaces | PERSONAL 생성, 탈퇴 때 개인 공간·가져오기 대기 공간 삭제, 속했던 UNLINKED 커플 공간 `purge_at` 연장 | `ck_spaces_*` |
| space_members | 개인 공간 소속(`pick-orange`) 생성, `SpaceAccessPolicy`·`GET /me`의 활성 공간 조회 | `uq_space_members_active_user` |
| notifications | 탈퇴 때 본인 행 삭제 (행 생성은 단위 2·6) | — |
| invite_codes | 탈퇴 때 본인의 유효한 코드 삭제 (생성은 단위 2) | — |
| event_reminders, memo_favorites | 탈퇴 때 본인 행 삭제 (기능은 단위 3·5) | — |
| events, anniversaries, memos | 개인 공간 삭제 때 CASCADE로 삭제 | FK `space_id` CASCADE |

| ERD 수명주기 | 단위 1 |
| --- | --- |
| 4.1 게스트 시작, 신규 소셜 로그인 | 전부 |
| 4.1 로그인 3종의 세션·기기 정리 | 전부 (세 경로가 같은 코드) |
| 4.1 게스트 → 기존 계정 로그인 | 전부 |
| 4.3 탈퇴 1단계(연결 해제) | 단위 2 (확인 필요 10) |
| 4.3 탈퇴 2~4단계, 세션 WITHDRAWN 후 삭제, Kakao 연결 끊기 | 단위 1 (프로필 사진 파일 삭제만 단위 7) |
| 4.4 게스트 정리·세션 정리·기기 정리 | 단위 6 (단위 1의 탈퇴 코드를 재사용) |
| 4.5 개인 공간 삭제 시 알림 CASCADE | 단위 1 (DB가 처리) |
| 4.6 표 전체 (로그인 3종, switch, 연동, 로그아웃, refresh, 탈퇴) | 단위 1 |

### 6. 관련 DB- 결정

| ID | 단위 1에서의 의미 |
| --- | --- |
| DB-02 | refresh는 같은 행 갱신, 회원 30일·게스트 365일, 이전 토큰은 해시가 없어 `SESSION_INVALID` |
| DB-04 | 탈퇴는 익명화, 속했던 커플 공간은 90일 보관 |
| DB-05 | 다른 공간 리소스는 404 (R-01·R-11) |
| DB-09 | 로그인 3종 `REPLACED` + `devices` 삭제, 로그아웃 `LOGOUT` + `devices` 삭제 |
| DB-10 | 게스트 정리(단위 6)가 단위 1의 탈퇴 처리를 그대로 부를 수 있게 만든다 |
| DB-11 | `REPLACED` 행은 `expires_at`까지 남아야 `SESSION_REPLACED`를 줄 수 있다 (정리 작업은 단위 6) |
| DB-12 | switch는 한 트랜잭션, 재조회 + `FOR UPDATE`, 실패 시 게스트 유지 |
| DB-14 | 유효 세션 조건(`revoked_at IS NULL AND expires_at > now`)을 조회하는 메서드를 단위 6이 쓰도록 둔다 (푸시 판단은 단위 6) |

## 다른 단위와의 경계, 단위 1이 남길 연결점

| 내용 | 단위 | 단위 1이 남길 것 |
| --- | --- | --- |
| 커플 사용자 탈퇴의 연결 해제, 탈퇴 전용 `COUPLE_UNLINKED` 행 | 2 | 탈퇴 서비스의 "커플 공간이면 먼저 해제" 단계 자리 (확인 필요 10) |
| 상대 화면의 닉네임 (`GET /space`) | 2 | — |
| `R-09` 경로 테스트 (`/invite-codes`, `/couple/join`) | 2 | `SpaceAccessPolicy`의 R-09 메서드 |
| 시간대 변경 시 `event_reminders.fire_at` 재계산 (ERD event_reminders) | 3 | `PATCH /me`에서 시간대가 바뀌면 도메인 이벤트(예: `UserTimeZoneChanged`)를 발행. 단위 3이 같은 트랜잭션 리스너로 재계산 |
| 탈퇴로 인한 해제 푸시·SYNC `SPACE`/`UNLINKED`, R-16, FCM | 6 | 커밋 뒤 이벤트(예: `UserWithdrawn`)에 필요한 값(상대 ID, 익명화 전 닉네임)을 담아 둔다 |
| 알림 설정 API, `PUT /me/devices`(확인 필요 9) | 6 | 사용자 생성 때 `notification_settings` 행 생성 |
| 게스트 정리·세션 정리·기기 정리 작업 (DB-10, DB-11, DB-14, Q-11) | 6 | 탈퇴 처리를 HTTP 없이 부를 수 있는 서비스 메서드, 유효 세션 조회 메서드 |
| 프로필 사진 업로드·삭제·조회, 탈퇴 때 파일 삭제 (NFR-FILE-01) | 7 | 탈퇴 이벤트에 익명화 전 `profile_image_key`를 담는다. `GET /me`의 사진 필드는 확인 필요 11 |

## 새 의존성 (추천)

| 용도 | 추천 | 이유 | 대안 |
| --- | --- | --- | --- |
| 자체 JWT 발급·검증 (HS256) | `com.nimbusds:nimbus-jose-jwt` | Jackson에 의존하지 않아 Boot 4의 Jackson 3(`tools.jackson`)과 충돌이 없다. Google ID 토큰 검증(RS256, JWKS)도 같은 라이브러리로 해서 의존성이 하나로 끝난다. Spring Security가 쓰는 라이브러리라 Boot BOM이 버전을 관리할 가능성이 높다 (빌드 때 확인, 아니면 버전 명시) | `io.jsonwebtoken:jjwt` 0.12+ (API는 편하지만 JSON 모듈이 Jackson 2(`jjwt-jackson`)나 Gson을 끌어온다) |
| Google ID 토큰 검증 | 위 Nimbus의 `JWKSourceBuilder`(캐시) + `DefaultJWTProcessor`로 `https://www.googleapis.com/oauth2/v3/certs` 서명, `iss`, `aud`, `exp` 검증 | 추가 의존성 없음. 서버 메모리가 작아(앱 512~640MB) 무거운 클라이언트를 피한다. 테스트는 RSA 키를 만들어 가짜 JWKS를 주입하면 된다 | `com.google.api-client:google-api-client`의 `GoogleIdTokenVerifier` (공식이지만 google-http-client·Gson·Guava를 끌어온다) |
| Kakao `access_token_info`·연결 끊기 호출 | Spring `RestClient` + `JdkClientHttpRequestFactory` (Java 21 HttpClient), 짧은 제한 시간 | 이미 있는 `spring-boot-starter-webmvc`에 포함, 새 의존성 없음. 테스트는 `MockRestServiceServer`(spring-test) | WebClient(webflux 추가 필요, 불필요) |
| 인증 처리 방식 | Spring Security를 넣지 않고 `HandlerInterceptor` + `@CurrentUser` 인자 리졸버 | 오류가 `GlobalExceptionHandler`를 거쳐 API.md 1.1 형식(`SESSION_REPLACED` 등)으로 나간다. 서블릿 필터에서 던지면 `@RestControllerAdvice`를 거치지 않는다 | `spring-boot-starter-security` + `oauth2-resource-server` (표준이지만 오류 응답을 따로 맞춰야 하고 설정이 많다) |
| UUIDv7 (ERD 1장, 단위 1이 첫 엔티티) | Hibernate 7의 `@UuidGenerator(style = VERSION_7)` | 새 의존성 없음, persist 시점에 ID가 정해져 `sid`를 바로 토큰에 넣을 수 있다 | `com.fasterxml.uuid:java-uuid-generator` |
| 해시·난수 | JDK `MessageDigest`(SHA-256), `SecureRandom` | 새 의존성 없음 | — |

## 환경변수

새 환경변수는 없을 예정이다. 단위 1에서 앱 코드가 읽기 시작하는 것:

| 변수 | 상태 | 단위 1에서 할 일 |
| --- | --- | --- |
| `JWT_SECRET` | `.env.example`, `docs/SETUP.md` 2장 표, `deploy/bin/deploy.sh`(환경마다 `openssl rand -hex 32`로 생성해 `app-<env>.env`에 기록) 모두 이미 있음 | `application.yml`에 `duriseo.jwt.secret: ${JWT_SECRET:}`, `DuriseoProperties`에 추가. 비어 있거나 32바이트 미만이면 시작 실패. 테스트는 테스트 전용 값 주입. 키 바이트 해석(문자열 UTF-8 그대로 vs hex 디코드)을 결정에 적는다 |
| `GOOGLE_WEB_CLIENT_ID` | 이미 `application.yml`·`DuriseoProperties`에 있음 (Parameter Store `/duriseo/config/`) | `aud` 검증에 사용 |
| `KAKAO_APP_ID` | 이미 있음 | `app_id` 검증에 사용 |
| `KAKAO_ADMIN_KEY` | 이미 있음. 비밀값은 Parameter Store `/duriseo/secret/KAKAO_ADMIN_KEY`에 **사용자가 넣어야 함** (SETUP 3.5: "카카오 탈퇴: 단위 1") | 연결 끊기에 사용, 로그에 쓰지 않음. 비어 있으면 끊기를 건너뛰고 경고 로그 |

토큰 수명(1시간·30일·365일), Google JWKS 주소, Kakao API 주소, 외부 호출 제한 시간은 Docs가 정한 값·상수라 환경변수로 만들지 않는다.

## 확인 필요

문서끼리 다르거나 비어 있는 곳. 해결은 추측하지 않고 선택지를 적는다. Docs를 고쳐야 하는 항목은 Docs 레포 `/prd-sync`로.

1. **토큰 응답 필드 이름이 없다.** `API.md` 3.1(`/auth/guest`·`/auth/social`·`/auth/switch`·`/auth/refresh` 행, 103~107행), 4.1 3단계(189행), 4.2 4단계(200행)는 "access·refresh 토큰", `isNewUser`만 말한다. `/auth/guest` 응답에 `isNewUser`가 있는지, 만료 시각을 주는지, 생성 응답이 201인지도 없다. `API.md` 7행은 상세 스키마를 서버 OpenAPI가 정한다고 한다. Android 단위 2가 이 값에 의존한다.
   - A) 서버가 정하고 OpenAPI·진행 기록 "결정"에 적는다. 예: `{accessToken, refreshToken, accessTokenExpiresAt, refreshTokenExpiresAt, isNewUser}`를 세 로그인과 refresh가 같이 쓰고, 모두 200
   - B) `/prd-sync`로 API.md 3.1에 먼저 적는다
2. **refresh 토큰 전달 방식이 없다.** `API.md` 3.1 `/auth/refresh` 행(107행)의 권한은 "refresh 토큰"뿐이다. A) 본문 `{refreshToken}` B) `Authorization: Bearer <refresh>` 헤더. 본문이 없을 때 400 `VALIDATION_FAILED`인지 401 `SESSION_INVALID`인지도 같이 정해야 한다.
3. **refresh 토큰 형식.** `PRD.md` NFR-SEC-01(513행)과 D-73(603행)은 "자체 access·refresh 토큰(JWT)"이라고 하고, `ERD.md` auth_sessions(93행)는 SHA-256 해시만 저장한다. refresh는 어차피 DB에서 해시로 찾으므로 JWT의 이점이 없다.
   - A) refresh도 JWT(`sub`, `sid`, `exp`, `jti`)로 해서 PRD 문구를 따른다
   - B) 256비트 난수(불투명 토큰)로 하고 PRD 문구를 "access 토큰(JWT)과 refresh 토큰"으로 고친다 (더 단순, 추천)
4. **access 토큰 클레임과 서명 알고리즘이 없다.** `API.md` 1장(20행)과 `ERD.md` auth_sessions(107행)는 `sid`만 정한다. 제안: HS256(`JWT_SECRET`), `sub` = 사용자 ID, `sid`, `iat`, `exp`. 계정 종류(`accountType`)는 넣지 않는다 (연동하면 토큰 수명 안에 바뀌는데 R-14·R-17은 바뀐 값으로 판단해야 하므로). A) 서버가 정하고 "결정"에 적는다 B) API.md 1장에 적는다.
5. **access 토큰이 없거나 위조됐을 때의 오류 코드가 없다.** `API.md` 5장(270~272행)의 401은 `TOKEN_EXPIRED`, `SESSION_INVALID`, `SESSION_REPLACED`뿐이다. `Authorization` 헤더 없음, 형식 오류, 서명 불일치, `sid` 행 없음은 정해져 있지 않다.
   - A) 모두 401 `SESSION_INVALID` (앱은 안내 없이 시작 화면으로. 있는 코드라 추천)
   - B) 새 코드(예: `UNAUTHENTICATED`)를 `/prd-sync`로 API.md 5장에 추가
6. **소셜 제공자 장애 때의 오류 코드가 없다.** `API.md` 4.2 2단계(196~198행)와 5장 `SOCIAL_TOKEN_INVALID`(273행)는 검증 실패만 말한다. Google 공개키를 못 받거나 Kakao가 5xx·시간 초과일 때:
   - A) 500 `INTERNAL_ERROR` (앱이 공통 오류를 보여 주고 다시 시도. 추천)
   - B) 401 `SOCIAL_TOKEN_INVALID` (토큰이 틀린 것처럼 보임)
7. **Kakao 프로필 이름을 받는 방법이 없다.** `API.md` 4.2(198행)는 `GET /v1/user/access_token_info`만 부르는데, 이 응답에는 `id`·`app_id`·`expires_in`만 있고 닉네임이 없다. 그런데 `PRD.md` FR-AUTH-07 AC1(222행), `API.md` 4.2 3단계(199행)·4.3 4단계(208행)는 소셜 프로필 이름을 닉네임으로 쓴다.
   - A) 검증 뒤 같은 토큰으로 `GET https://kapi.kakao.com/v2/user/me`를 불러 프로필 닉네임만 읽는다 (서버가 신뢰할 수 있는 값, NFR-PRIV-01 범위 안. API.md 4.2에 추가 필요. 추천)
   - B) 앱이 요청 본문에 이름을 함께 보낸다 (호출이 하나 줄지만 신뢰할 수 없는 값, 요청 형식 변경)
   - C) Kakao는 항상 "사용자" (D-69의 뜻과 다름)
   - Google은 ID 토큰의 `name` 클레임을 쓴다. 앱이 profile 범위를 요청해야 들어온다 (Android 단위 2에 알릴 것).
8. **Google `iss` 값.** `API.md` 4.2(197행)와 5장은 `accounts.google.com`만 적었는데 Google은 `accounts.google.com`과 `https://accounts.google.com` 둘 다 발급한다. A) 둘 다 허용하고 API.md 문구를 고친다 (Google 안내와 같음, 추천) B) 문서대로 하나만 (일부 정상 토큰이 거부될 수 있음).
9. **`PUT /me/devices`와 `/me/notification-settings`가 어느 단위인지 겹친다.** `docs/PLAN.md` 단위 1의 기준은 "API 3.1"이라 두 경로가 들어가지만, 단위 6 범위는 "알림 설정, 푸시(FCM)"다. `API.md` 4.2 5단계(201행)는 로그인 직후 앱이 `PUT /me/devices`를 부른다고 한다. 요청·응답 필드(`fcmToken`, `platform`, 204?)도 정해져 있지 않다.
   - A) 단위 1: `PUT /me/devices`(저장만, 로그인·로그아웃의 `devices` 삭제 테스트에도 필요), 단위 6: 알림 설정 API (단위 1은 행 생성만). 추천
   - B) 둘 다 단위 6 (그 전까지 Android는 404를 무시해야 함)
   - C) 둘 다 단위 1
10. **커플 사용자 탈퇴(FR-AUTH-06 AC2)의 범위.** `docs/PLAN.md`는 탈퇴를 단위 1, 연결 해제를 단위 2에 두는데 `API.md` 4.6(244행)과 `ERD.md` 4.3 1단계는 탈퇴가 4.5 연결 해제(알림 삭제, `event_reminders` 삭제, 개인 공간 되살리기/만들기, `COUPLE_UNLINKED` 행, 푸시·SYNC)를 먼저 하라고 한다. 단위 1 시점에는 `/couple/join`이 없어 운영에서 커플 공간이 생길 수 없다.
    - A) 단위 1: 개인 공간 탈퇴(AC1·AC3)와 해제가 필요 없는 데이터 단계(UNLINKED 공간 `purge_at` +90일, 가져오기 대기 공간 삭제, 익명화). 단위 2: "탈퇴 전 연결 해제"와 탈퇴 전용 `COUPLE_UNLINKED` 행. 단위 6: 푸시·SYNC. 단위 1에서 활성 커플 공간 사용자가 탈퇴를 부르면 어떻게 할지(예: 단위 2가 채울 연결점, 그 전에는 500)도 정한다. 추천
    - B) 단위 1에서 연결 해제까지 만든다 (단위 2와 코드가 겹치고 단위 1이 커진다)
11. **응답 본문 모양이 없다.** `POST /auth/link`(`API.md` 105행, 4.3 4·5단계: 새 토큰 없음, 앱은 `GET /me`로 다시 읽음), `PATCH /me`(110행), `DELETE /me`(114행)의 성공 본문, `GET /me`(109행)의 필드 이름(종류·공간 ID·상대·사람 색·처음 만난 날·`importPending`)이 없다. `PRD.md` FR-SET-01 AC1(419행)의 "로그인 수단 표시"에 필요한 필드, 상대의 모양(ID·닉네임·색), 프로필 사진 필드(단위 7)도 정해지지 않았다.
    - A) 서버가 OpenAPI로 정한다. 예: link 204, `DELETE /me` 204, `PATCH /me` 200 `MeResponse`, `GET /me` = `{id, accountType, nickname, timeZone, providers, profileImageUrl(단위 7 전까지 null), space: {id, type, myColor, togetherColor, startDate, partner: {id, nickname, color} | null}, importPending}`
    - B) API.md 3.1에 먼저 적는다
12. **"10자"를 세는 단위와 빈 닉네임.** `PRD.md` NFR-LIMIT-01(519행)·D-56, `ERD.md` users.nickname `varchar(10)`. PostgreSQL은 코드 포인트로 세고 Kotlin `String.length`·`@Size`는 UTF-16 단위로 센다. 이모지 닉네임이 서버 검증과 DB에서 다르게 판정되고, "앞 10자"로 자르면 이모지가 반으로 잘릴 수 있다. 공백만 있는 닉네임을 `PATCH /me`로 보낼 때의 처리(앞뒤 공백 제거? `REQUIRED`?)도 없다.
    - A) 코드 포인트로 센다 (DB와 같음, 추천), 앞뒤 공백을 지우고 비면 400 `VALIDATION_FAILED`(`REQUIRED`)
    - B) 글자 묶음(grapheme)으로 센다 (사람이 보는 글자 수와 같지만 DB 한도와 어긋날 수 있음)
13. **시간대.** 로그인 요청에 시간대가 없어 새 사용자는 앱이 `PATCH /me`를 부를 때까지 `Asia/Seoul`이다 (`ERD.md` 1장 "앱 실행 시 갱신"). 잘못된 시간대의 오류 코드도 명시되지 않았다(`VALIDATION_FAILED` + `INVALID`로 볼 수 있음). `ERD.md` event_reminders `fire_at`(264행)은 시간대가 바뀌면 다시 계산한다고 한다 (단위 3 연결점). A) 지금대로(로그인 뒤 앱이 `PATCH /me`) B) 로그인 3종 본문에 선택 필드 `timeZone`을 더한다 (API 변경).
14. **CONVENTIONS의 "AC 하나에 테스트 하나 이상"과 앱·웹 전용 AC.** FR-AUTH-03 AC4, FR-AUTH-04 AC1·AC2, FR-AUTH-05 AC3, FR-AUTH-07 AC4, FR-AUTH-08, FR-AUTH-09는 서버 API가 없다. 위 범위 표처럼 "앱 동작"으로 표시하고 서버 테스트를 두지 않아도 되는지.
15. **PLAN.md 상태.** 키트 2(V1 마이그레이션)와 키트 3(PR #3)은 이미 main에 머지됐는데 `docs/PLAN.md` 상태가 "PR 완료"로 남아 있다. 단위 1 PR에서 "완료"로 고칠지.
16. **(선택) `auth_sessions.user_id` 인덱스가 없다.** `ERD.md` 3.1 auth_sessions에는 `refresh_token_hash` UNIQUE만 있다. 로그인 때 "그 사용자의 다른 유효 세션 무효화"와 단위 6의 "유효 세션이 있는가"(R-16, DB-14)는 `user_id`로 찾는다. 지금 규모에서는 문제가 아니다. A) 그대로 B) ERD에 INDEX (user_id)를 추가하고 V2 마이그레이션.


### 확인 필요 해결 (2026-10-26, Docs PR #7 = PRD v0.7 머지)

사용자가 추천안대로 결정했고 Docs에 반영됐다. **아래 기준은 Docs main(PRD v0.7)의 API.md·ERD.md다.** 이 절과 Docs가 다르면 Docs가 맞다.

- 1·2·4·5·6·8·11·12·13: API.md 1장(토큰 형식, 인증 실패 규칙·순서, refresh 본문 `{refreshToken}`), 3.1(`AuthTokens`·`Me`·`MeSpace`·`MePartner`, 경로별 요청·성공 상태, 검증), 4.2(Google `iss` 두 값·`name`, Kakao `/v2/user/me` 조회 시점과 4xx는 "사용자"), 5장
- 3: refresh는 무작위 43자(32바이트 base64url), 해시만 저장 (D-81)
- 7: Kakao 닉네임은 `/v2/user/me`의 `kakao_account.profile.nickname`, 신규 가입과 `/auth/link`("게스트" 교체)일 때만 호출
- 9: `PUT /me/devices`는 단위 1. 알림 설정 API는 단위 6
- 10: 단위 1은 개인 공간 사용자 탈퇴와 해제 없이 되는 처리까지. 커플 해제는 단위 2, 푸시·SYNC는 단위 6 (연결점만 남김)
- 12: 닉네임은 앞뒤 공백 제거 후 코드 포인트 1~10 (D-82)
- 14: 앱·웹 전용 AC는 "앱 동작"으로 표시하고 서버 테스트 없음
- 15: PLAN.md의 키트 2·3을 "완료"로 이 단위 PR에서 고친다
- 16: `auth_sessions.user_id` 인덱스는 이번에 넣지 않는다 (마이그레이션 없음)
- 의존성: `nimbus-jose-jwt`(JWT 발급·검증, Google JWKS), Kakao는 Spring `RestClient`, 인증은 Spring Security 대신 인터셉터 + 인자 리졸버, UUIDv7은 Hibernate `@UuidGenerator(style = VERSION_7)`

## 결정

구현하면서 정한 것 중 문서에 없는 것. 문서에 있어야 할 결정이면 여기 적지 말고 작업을 멈추고 보고한다.

결정 후보 (범위 승인 때 함께 확인):

- 로그인 3종의 "이전 세션·기기 정리"는 사용자 행을 `SELECT … FOR UPDATE`로 잠근 뒤 한다. 같은 계정으로 두 기기가 동시에 로그인해도 유효 세션이 하나만 남게 하려고 (D-51, ARCHITECTURE 4장).
- 같은 소셜 계정의 첫 로그인이 동시에 두 번 오면 `uq_social_accounts_provider_user` 위반이 난다. 이때는 이미 생긴 회원으로 로그인 처리한다 (500을 내지 않음).
- Kakao 연결 끊기는 커밋 뒤(`@TransactionalEventListener(AFTER_COMMIT)`) 연결 2초·응답 3초 제한으로 부르고, 실패는 키·토큰 없이 로그만 남긴다.

구현 1부에서 정한 것:

- `JWT_SECRET`은 문자열을 UTF-8 바이트 그대로 HS256 키로 쓴다 (hex 디코드 안 함). 배포 스크립트가 만드는 `openssl rand -hex 32`는 64자라 64바이트 키가 된다. 32바이트 미만이면 `AccessTokenCodec` 생성에서 실패해 서버가 시작하지 않는다.
- `SpaceAccessPolicy`는 `common`이 도메인에 의존하지 않도록 엔티티·리포지토리 대신 `EntityManager` 네이티브 쿼리(`users`, `space_members`, `spaces`)로 읽는다. 계정 종류는 요청마다 `users.account_type`에서 읽는다.
- 인증 인터셉터는 컨트롤러 메서드(`HandlerMethod`)가 있는 `/api/v1/**` 경로에만 적용한다. 없는 경로는 인증 없이 기존대로 404 `NOT_FOUND`다. 공개 경로는 `AuthWebConfig.PUBLIC_PATHS`.
- 세션이 `REPLACED`이면 만료 시각과 관계없이(행이 남아 있는 한) `SESSION_REPLACED`를 준다. `revoked_at`이 없고 `expires_at`이 지났으면 `SESSION_INVALID` (access 경로·refresh 경로 모두).
- `POST /auth/refresh`에서 `refreshToken`이 없거나 비면 400 `VALIDATION_FAILED`(`refreshToken`, `REQUIRED`)로 한다 (Docs는 이 경우를 정하지 않았고 1.1의 "필수값 누락" 규칙을 따랐다).
- `PUT /me/devices`는 `INSERT … ON CONFLICT (fcm_token) DO UPDATE` 한 문장으로 처리해 같은 토큰의 겹친 요청이 유일 제약 오류(500)가 되지 않게 한다. 그 사용자의 다른 FCM 토큰 행은 지우지 않는다 (Docs에 없음).
- `LoginService.login`·`cleanUpPreviousSessionsAndDevices`·`startSession`은 `Propagation.MANDATORY`다. 사용자 생성·정리·새 세션이 한 트랜잭션이어야 해서, 2부의 `/auth/social`·`/auth/switch` 서비스가 트랜잭션을 열고 부른다.
- `UserService.createMember(socialName)`은 2부 신규 가입이 쓸 자리다 (닉네임은 `Nicknames.fromSocialName`).
- `PATCH /me`는 "필드 없음"과 `null`을 구분해야 해서 본문을 `Map`으로 받아 `UpdateMeRequest.parse`에서 검사한다. 시간대는 `ZoneId.getAvailableZoneIds()`에 있는 이름만 허용한다 (`+09:00`, `UTC+9` 같은 오프셋 표기는 거부).
- JVM 메서드 이름에는 `/`를 쓸 수 없어 테스트 이름의 `/auth/guest`, `PATCH /me` 등은 `auth 아래 guest`, `PATCH me`처럼 적었다.

구현 2부에서 정한 것:

- 소셜 토큰 검증(`SocialTokenVerifier`)과 Kakao 닉네임 조회는 외부 호출이라 **DB 트랜잭션 밖**에서 하고, 검증이 끝난 뒤 짧은 트랜잭션을 연다. `SocialAuthService`는 `@Transactional`이 아니라 `TransactionTemplate`으로 범위를 정한다. 검증이 실패하면 트랜잭션을 열기 전이라 아무 행도 바뀌지 않는다.
- Google 검증은 Nimbus로 직접 한다: RS256만 받고, 공개키는 `GoogleKeySource`(Nimbus `JWKSourceBuilder` 캐시, 연결 2초·읽기 3초)로 찾고, `iss` 두 값·`aud`·`exp`는 주입된 `Clock`으로 확인한다. `sub`가 비었거나 100자를 넘으면(`provider_user_id`는 varchar(100)) 401 `SOCIAL_TOKEN_INVALID`다. `email` 등 다른 클레임은 읽지 않는다.
- Kakao는 `RestClient`(JDK HttpClient, 연결 2초·읽기 3초)로 부른다. `access_token_info`가 4xx면(429 포함) API.md대로 401 `SOCIAL_TOKEN_INVALID`, 그 밖의 실패(5xx·시간 초과·`id`/`app_id` 없는 응답)는 500 `INTERNAL_ERROR`다. 닉네임은 `SocialIdentity.profileName()`이 불릴 때만 `v2/user/me`를 부른다 (신규 가입, 닉네임이 정확히 "게스트"인 연동). 설정된 앱 ID가 비어 있으면 어떤 토큰도 401이다.
- 같은 소셜 계정의 첫 로그인이 동시에 오면 `uq_social_accounts_provider_user` 위반을 제약 이름으로 알아보고 이미 생긴 회원으로 로그인한다. `/auth/link`는 같은 위반을 409 `SOCIAL_ALREADY_USED`로 바꾼다.
- `/auth/link`는 R-14를 소셜 검증 앞과 트랜잭션 안(사용자 행을 잠근 뒤 `promoteGuestToMember`)에서 두 번 확인한다. 닉네임이 필요한지(정확히 "게스트")는 검증 뒤 트랜잭션 밖에서 읽어 정하고, 그 사이 닉네임이 "게스트"로 바뀌는 드문 경우에는 이름 없음으로 보아 "사용자"가 된다.
- `/auth/switch`는 트랜잭션 안에서 소셜 계정 재조회 → 대상 회원 행 잠금(탈퇴했거나 MEMBER가 아니면 404) → 게스트 행 잠금(그 사이 탈퇴·연동했으면 401/403) → 게스트 탈퇴 → 로그인 3종 정리 → 새 세션 순서다. 잠금은 대상 회원 먼저, 게스트 나중이다.
- 탈퇴는 `WithdrawalService`(user)가 ERD 4.3 순서로 하고, 게스트 삭제(switch)와 단위 6의 게스트 정리가 `eraseInTransaction`을 그대로 부른다. 세션은 `SessionService`(auth)가 `WITHDRAWN`으로 무효화한 뒤 지운다. 알림·즐겨찾기·일정 알림·초대코드는 아직 엔티티가 없어 `WithdrawalDataEraser`가 네이티브 SQL로 지운다. "유효한 초대코드"는 `used_at`과 `revoked_at`이 모두 null인 코드다.
- **커플 해제 연결점**: 탈퇴하는 사용자의 활성 공간이 COUPLE이면 `WithdrawalService.unlinkCoupleBeforeWithdrawal`이 `IllegalStateException`을 던져 500 `INTERNAL_ERROR`가 되고 전체가 롤백된다 (단위 2 전에는 커플 공간이 생길 수 없다). 단위 2가 이 함수에서 4.5 연결 해제와 상대 `COUPLE_UNLINKED` 행을 만들고 상대 ID를 돌려주면 `UserWithdrawn.partnerUserId`에 담긴다. 이때 테스트 `FR-AUTH-06 AC2 활성 커플 공간 사용자의 탈퇴는 …500…`을 해제 동작 테스트로 바꾼다.
- `UserWithdrawn` 이벤트(탈퇴 트랜잭션 안에서 발행)에 익명화 전 닉네임·`profile_image_key`·상대 ID(지금은 null)·Kakao `provider_user_id`를 담는다. Kakao 연결 끊기는 `KakaoUnlinkListener`(`AFTER_COMMIT`)가 하고, 실패는 사용자 UUID와 예외 종류만 로그에 남긴다 (Kakao ID·키·토큰 제외). 어드민 키가 비어 있으면 `KakaoApiClient.unlink`가 경고만 남기고 건너뛴다.
- 테스트 지원: `TestClockConfig`가 `TestSocialConfig`(가짜 `SocialTokenVerifier`, 가짜 `KakaoUnlinker`, 둘 다 `@Primary`)를 함께 가져와 모든 통합 테스트가 한 Spring 컨텍스트(한 PostgreSQL 컨테이너)를 공유한다. 가짜는 `delegate`로 실제 Google 검증기를 끼울 수 있다 (NFR-PRIV-01 경로 테스트).
- 1부가 `LoginService`를 직접 불러 확인하던 FR-AUTH-02 AC3 테스트는 `POST /auth/social` 경로 테스트로 바꿨다 (`LoginCleanupTest`). `AuthTokenTest`의 공개 경로 테스트(social)와 `LogoutTest`의 `R-17 연동한 게스트…`도 가짜 제공자와 `/auth/link` 경로를 쓰도록 바꿨다.

## 리뷰 기록

| 회차 | Critical | Major | 비고 |
| --- | --- | --- | --- |

## 남은 일

- 리뷰(`server-reviewer`) Critical 0까지, CI 초록불 확인 (이 환경은 Gradle을 못 돌려 구현 2부는 CI가 첫 컴파일이다)
- 사용자: 단위 1을 dev에 배포하기 전에 Parameter Store `/duriseo/secret/KAKAO_ADMIN_KEY` 등록 (SETUP 3.5)
- 사용자: Kakao Developers 동의항목 "닉네임" 켜기 (API.md 4.2, 꺼져 있으면 Kakao 가입 닉네임이 모두 "사용자"가 된다)
- 단위 2: `WithdrawalService.unlinkCoupleBeforeWithdrawal` 채우기 (위 "결정")
- 단위 6: 게스트 정리 작업이 `WithdrawalService.eraseInTransaction`을 부르고, `UserWithdrawn`을 듣는 푸시·SYNC. 단위 7: `UserWithdrawn.profileImageKey`로 파일 삭제
- Android 단위 2에 알릴 것: 토큰 응답·refresh 요청 형식(확인 필요 1·2), Google 로그인 때 profile 범위 요청(확인 필요 7), `SESSION_REPLACED`/`SESSION_INVALID` 처리
