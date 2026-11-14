# 작업 계획

작업 단위 하나가 PR 하나예요. 단위가 끝나면 이 표의 상태를 같은 PR에서 바꿔요. 화면·기능 내용은 Docs를 보고, 여기에는 순서와 범위만 적어요. 기준 Docs는 PRD v0.12예요.

**지금 위치:** 키트 1~3과 단위 1~6이 머지됐고 개발 서버(dev)에 배포돼 있어요 (main `d619d1b`). 단위 6(알림)까지 머지됐어요. 단위 7(프로필 사진)은 PR #13으로 머지 대기예요. 끝난 단위의 진행 기록은 `docs/progress/archive/`에 있어요.

상태: 완료 = 머지됨 · PR 완료 = PR을 열었고 머지 대기 · 대기 = 아직 시작 안 함.

| 단위 | 범위 | 기준 | 상태 |
| --- | --- | --- | --- |
| 키트 1 | 뼈대: 패키지, 응답 형식 1.1, 오류 처리, traceId, 설정, CI, 에이전트 지침 | API.md 1장, 5장 | 완료 (PR #1) |
| 키트 2 | DB: ERD 15개 테이블 Flyway V1, 마이그레이션 테스트 | ERD 3장 | 완료 (PR #2) |
| 키트 3 | 인프라: AWS 서버 1대용 Docker Compose(dev 앱, prod 앱, PostgreSQL), DB 백업(S3), GitHub Actions 배포 | ERD 7장, Docs `config` | 완료 (PR #3, 보정 #4·#5) |
| 1 | 계정: 게스트 시작, 소셜 로그인(Google·카카오 검증), 게스트 연동·기존 계정 로그인, refresh(`sid`, 한 기기), 로그아웃, 탈퇴, 내 정보·프로필, `SpaceAccessPolicy`와 R- 규칙 테스트 | FR-AUTH, API 3.1·4.1~4.3·4.6~4.7, R-01·R-09·R-11·R-14·R-17 | 완료 (PR #6) |
| 2 | 커플: 초대코드, 연결(행 잠금, 커밋 뒤 `CoupleLinked` 이벤트. SYNC `LINKED` 발송은 단위 6), 기록 가져오기, 해제·복구, 처음 만난 날, 사람 색, 탈퇴 때 연결 해제, Kakao 연결 해제 웹훅 | FR-COUPLE, FR-CAL-04, FR-AUTH-06 AC2·AC4, API 3.2·3.6·4.4·4.5·4.8, R-08·R-09·R-18 | 완료 (PR #7) |
| 배포 1 | 단위 2까지 AWS dev에 배포해 두 기기로 연결 확인. 사용자가 `docs/SETUP.md` 3장(AWS·DuckDNS·GitHub 환경)을 마쳐야 한다 | 키트 3 | dev 배포됨. 두 기기 연결 확인은 기록 없음 (사용자 확인) |
| 3 | 캘린더: 일정, 일정 알림, 날짜 범위. 상대에게 `PARTNER_CREATED` 알림 행 | FR-CAL, API 3.3, R-02·R-07·R-10·R-13·R-15 | 완료 (PR #8) |
| 4 | 기념일: 자동 기념일 계산, 직접 기념일, 목록 2종, 캘린더용 범위 조회 | FR-ANNIV, API 3.3, R-05 | 완료 (PR #9) |
| 5 | 메모: 메모, 날짜, 고정, 즐겨찾기 | FR-MEMO, API 3.4, R-03·R-04·R-06 | 완료 |
| 6 | 알림: 알림 목록, 알림 설정, 푸시(FCM)·동기화 신호, 스케줄러(일정·기념일 알림), 정리 작업 | FR-NOTI, API 3.5·6장·7장, ERD 4.4 | 완료 |
| 7 | 프로필 사진: 업로드·재인코딩·S3 | FR-AUTH-07, API 8장, NFR-FILE-01 | PR 완료 (PR #13) |

## 앞 단위에서 넘어온 일

단위 1~4 진행 기록의 "남은 일" 중 아직 안 끝난 것만 모았어요. 단위를 시작할 때 진행 기록 범위 표에 넣어요. 끝난 일(일정 알림·초대코드·알림 행 삭제 이동, `CoupleContentGateway` 제거와 탈퇴 즐겨찾기 삭제 이동)은 뺐어요.

### 단위 6 (알림)

도메인 이벤트는 단위 1~4가 이미 발행한다(트랜잭션 안에서 발행, 리스너는 `AFTER_COMMIT`). 듣는 쪽이 아직 없다.

- `CoupleLinked`·`CoupleUnlinked`·`SpaceUpdated`·`UserWithdrawn`을 듣는 푸시와 SYNC(`SPACE`의 `LINKED`·`UNLINKED`·`UPDATED`, 행이 생긴 본인에게 `NOTIFICATION`). 탈퇴로 인한 해제는 `CoupleUnlinked`(`byWithdrawal`)와 `UserWithdrawn.partnerUserId` 두 곳에 담겨 있으니 한쪽에서만 보낸다.
- `EventCreated`·`EventUpdated`·`EventDeleted`를 듣는 SYNC `EVENT`, `PARTNER_CREATED` 푸시(상대의 `partner_created` 설정과 유효 세션을 본다)와 `NOTIFICATION` 신호.
- `AnniversaryCreated`·`AnniversaryUpdated`·`AnniversaryDeleted`를 듣는 SYNC `ANNIVERSARY`.
- `MemoCreated`·`MemoUpdated`(수정·고정·해제)·`MemoDeleted`를 듣는 SYNC `MEMO`, `PARTNER_CREATED`(메모)·`MEMO_PINNED` 푸시(상대의 `partner_created`·`memo_pinned` 설정과 유효 세션)와 `NOTIFICATION` 신호. 이벤트에 받는 사람(`partnerUserId`)과 알림 ID(`notificationId`)가 실려 있다. 즐겨찾기는 신호 없음 (D-134).
- 일정 알림 스케줄러(1분마다 `fire_at <= now`이고 `sent_at` 없는 `event_reminders`를 `EVENT_REMINDER` 행으로 만들고 `sent_at` 기록, 푸시). 서버 1대 가정의 중복 방지(DB-03)를 같이 본다.
- 기념일 알림 스케줄러(매시 정각, `ANNIVERSARY` 행·푸시). 단위 4의 `AnniversaryCalculator`를 재사용한다.
- 정리 작업(ERD 4.4): 게스트 정리(`WithdrawalService.eraseInTransaction`을 그대로 부른다), `purge_at` 지난 공간, 7일 지난 `invite_code_attempts`, 30일 지난 초대코드, 만료 세션(`REPLACED`는 `expires_at`까지 남김), 세션 없는 사용자의 `devices`, 30일 지난 `notifications`.
- 사용자 행 잠금: 재시도 3번을 다 쓴 뒤 상대가 또 바뀌면 순서 밖에서 잠가 드물게 교착 판정 500이 날 수 있다 (`PartnerLockRetry`, `UserService.lockShare`). 단위 6의 새 흐름에서도 사용자 행 → 공간 행 순서를 지킨다.

### 단위 7 (프로필 사진)

- `UserWithdrawn.profileImageKey`로 탈퇴 때 사진 파일을 지운다. 어떤 사용자도 참조하지 않는 파일 정리는 단위 6의 정리 작업(`job/CleanupJob`)에 8단계로 더한다(자리 주석 있음, 단위 6 확인 필요 4).
- 사진 변경(`PUT·DELETE /me/profile-image`) 성공 때 커플 공간 상대에게 SYNC `SPACE`/`UPDATED` (단위 6의 닉네임 변경과 같은 `SpaceUpdated` 발행).
- `GET /me`·`GET /space`는 `hasProfileImage`(사진이 있는지)만 주고, 이미지는 `GET /users/{id}/profile-image`(API.md 8장)로 받는다. 이 경로와 업로드·삭제를 붙인다.

### 단위와 상관없이 (사람이 할 일, 정리)

- 사용자: Parameter Store `/duriseo/secret/FIREBASE_SERVICE_ACCOUNT_JSON` 확인 (단위 6). 없거나 틀리면 배포 로그에 경고·오류 한 줄이 남고 푸시만 안 간다.
- 사용자: Parameter Store `/duriseo/secret/KAKAO_ADMIN_KEY` 등록 확인 (`docs/SETUP.md` 3.5). 비어 있으면 탈퇴 때 Kakao 연결 끊기를 건너뛰고 Kakao 웹훅이 모두 401이 된다.
- 사용자: Kakao Developers 동의항목 "닉네임" 켜기 (꺼져 있으면 Kakao 가입 닉네임이 모두 "사용자"가 된다, API.md 4.2).
- 사용자: Kakao Developers 앱 › 웹훅 › 연결 해제 웹훅에 dev·prod 주소 등록 (`docs/DEPLOY.md` 9장).
- 정리 검토: 날짜 범위 조회 검사·잘라내기를 `GET /events`·`GET /anniversaries`도 `common/api/DateRangeQuery`로 모은다 (지금 같은 규칙 복사본 3곳, 단위 5 리뷰).
- 정리 검토: `auth`와 `user` 패키지가 서로 참조한다 (`SocialAuthService`가 `user` 서비스를, `WithdrawalService`가 `SessionService`를 부른다). 방향을 정리할지 검토한다.

## Docs 후속

Docs 레포의 `/prd-sync`로 정해야 할 것이에요. 이 레포에서는 Docs를 고치지 않아요. 단위 1~4에서 모은 4건은 PRD v0.12로 반영돼 지웠어요.

- (단위 6) SPACE `UNLINKED` 신호에 공간 id를 싣는다. API.md 6장은 `LINKED`만 "id 없음"이라 적고 `UNLINKED`는 비어 있다.
- (단위 6) 보이는 푸시 data에서 값이 null인 키(`body`·`targetType`·`targetId`)는 뺀다. FCM data는 문자열뿐이라 정해야 하는데 API.md 7장이 비어 있다.
