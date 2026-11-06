# 작업 계획

작업 단위 하나가 PR 하나예요. 단위가 끝나면 이 표의 상태를 같은 PR에서 바꿔요. 화면·기능 내용은 Docs를 보고, 여기에는 순서와 범위만 적어요. 기준 Docs는 PRD v0.11이에요.

**지금 위치:** 키트 1~3과 단위 1~4가 머지됐고 개발 서버(dev)에 배포돼 있어요 (main `e0a7611`). 다음은 단위 5(메모)예요. 끝난 단위의 진행 기록은 `docs/progress/archive/`에 있어요.

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
| 5 | 메모: 메모, 날짜, 고정, 즐겨찾기 | FR-MEMO, API 3.4, R-03·R-04·R-06 | 대기 |
| 6 | 알림: 알림 목록, 알림 설정, 푸시(FCM)·동기화 신호, 스케줄러(일정·기념일 알림), 정리 작업 | FR-NOTI, API 3.5·6장·7장, ERD 4.4 | 대기 |
| 7 | 프로필 사진: 업로드·재인코딩·S3 | FR-AUTH-07, API 8장, NFR-FILE-01 | 대기 |

## 앞 단위에서 넘어온 일

단위 1~4 진행 기록의 "남은 일" 중 아직 안 끝난 것만 모았어요. 단위를 시작할 때 진행 기록 범위 표에 넣어요. 끝난 일(일정 알림·초대코드·알림 행 삭제 이동, `CoupleContentGateway`의 일정·기념일 이동)은 뺐어요.

### 단위 5 (메모)

- `CoupleContentGateway`에 남은 `memos` 네이티브 SQL 3곳(`hasContent`·`countsOf`·`moveAll`)을 메모 도메인 서비스로 옮기고 이 클래스를 없앤다 (`ImportCounts`의 `memos` 개수 포함, ARCHITECTURE 2장의 임시 예외 해소).
- `WithdrawalDataEraser`의 `memo_favorites` 삭제를 메모 서비스로 옮긴다.

### 단위 6 (알림)

도메인 이벤트는 단위 1~4가 이미 발행한다(트랜잭션 안에서 발행, 리스너는 `AFTER_COMMIT`). 듣는 쪽이 아직 없다.

- `CoupleLinked`·`CoupleUnlinked`·`SpaceUpdated`·`UserWithdrawn`을 듣는 푸시와 SYNC(`SPACE`의 `LINKED`·`UNLINKED`·`UPDATED`, 행이 생긴 본인에게 `NOTIFICATION`). 탈퇴로 인한 해제는 `CoupleUnlinked`(`byWithdrawal`)와 `UserWithdrawn.partnerUserId` 두 곳에 담겨 있으니 한쪽에서만 보낸다.
- `EventCreated`·`EventUpdated`·`EventDeleted`를 듣는 SYNC `EVENT`, `PARTNER_CREATED` 푸시(상대의 `partner_created` 설정과 유효 세션을 본다)와 `NOTIFICATION` 신호.
- `AnniversaryCreated`·`AnniversaryUpdated`·`AnniversaryDeleted`를 듣는 SYNC `ANNIVERSARY`.
- 일정 알림 스케줄러(1분마다 `fire_at <= now`이고 `sent_at` 없는 `event_reminders`를 `EVENT_REMINDER` 행으로 만들고 `sent_at` 기록, 푸시). 서버 1대 가정의 중복 방지(DB-03)를 같이 본다.
- 기념일 알림 스케줄러(매시 정각, `ANNIVERSARY` 행·푸시). 단위 4의 `AnniversaryCalculator`를 재사용한다.
- 정리 작업(ERD 4.4): 게스트 정리(`WithdrawalService.eraseInTransaction`을 그대로 부른다), `purge_at` 지난 공간, 7일 지난 `invite_code_attempts`, 30일 지난 초대코드, 만료 세션(`REPLACED`는 `expires_at`까지 남김), 세션 없는 사용자의 `devices`, 30일 지난 `notifications`.
- 사용자 행 잠금: 재시도 3번을 다 쓴 뒤 상대가 또 바뀌면 순서 밖에서 잠가 드물게 교착 판정 500이 날 수 있다 (`PartnerLockRetry`, `UserService.lockShare`). 단위 6의 새 흐름에서도 사용자 행 → 공간 행 순서를 지킨다.

### 단위 7 (프로필 사진)

- `UserWithdrawn.profileImageKey`로 탈퇴 때 사진 파일을 지운다. 어떤 사용자도 참조하지 않는 파일 정리는 단위 6의 정리 작업과 겹치니 함께 정한다.
- `GET /me`·`GET /space`는 `hasProfileImage`(사진이 있는지)만 주고, 이미지는 `GET /users/{id}/profile-image`(API.md 8장)로 받는다. 이 경로와 업로드·삭제를 붙인다.

### 단위와 상관없이 (사람이 할 일, 정리)

- 사용자: Parameter Store `/duriseo/secret/KAKAO_ADMIN_KEY` 등록 확인 (`docs/SETUP.md` 3.5). 비어 있으면 탈퇴 때 Kakao 연결 끊기를 건너뛰고 Kakao 웹훅이 모두 401이 된다.
- 사용자: Kakao Developers 동의항목 "닉네임" 켜기 (꺼져 있으면 Kakao 가입 닉네임이 모두 "사용자"가 된다, API.md 4.2).
- 사용자: Kakao Developers 앱 › 웹훅 › 연결 해제 웹훅에 dev·prod 주소 등록 (`docs/DEPLOY.md` 9장).
- 정리 검토: `auth`와 `user` 패키지가 서로 참조한다 (`SocialAuthService`가 `user` 서비스를, `WithdrawalService`가 `SessionService`를 부른다). 방향을 정리할지 검토한다.

## Docs 후속

Docs 레포의 `/prd-sync`로 정해야 할 것이에요. 이 레포에서는 Docs를 고치지 않아요 (코드는 지금 그대로 둬요).

- API.md 1장: 일시 형식은 `YYYY-MM-DDTHH:MM:SSZ`만 받고, `Z`가 아닌 오프셋과 소수 초는 거부(`start`/`end`는 `INVALID`)한다고 명시. 지금은 "ISO 8601 UTC"뿐이다. Android도 이 형식만 보내야 한다 (단위 3).
- API.md 4.4 2단계·ERD 3.2의 오입력 실패 기록 "연결 트랜잭션과 별도로 커밋": 구현은 거부할 때 시도 행만 넣고 같은 트랜잭션을 커밋한 뒤 오류를 돌려준다(연결은 바꾸지 않음). 결과는 같으니 문구를 구현에 맞추거나 구현을 문서에 맞출지 정한다 (단위 2).
- 탈퇴한 사람의 초대코드: 탈퇴 때는 유효한 코드(`used_at`·`revoked_at`이 모두 null)만 지워진다(ERD 4.3). 연결로 이미 무효가 된 코드는 남아 `INVITE_EXPIRED`가 나간다. API.md 4.4 2단계 9번·5장은 "탈퇴한 사람의 코드는 `INVITE_NOT_FOUND`"라고만 적었다 (단위 2). 이미 무효인 코드까지 포함하는지 확인.
- (선택) 기념일 `PATCH`가 저장값과 같은 값만 보내면 아무것도 바꾸지 않는 처리(`updated_at` 그대로, `AnniversaryUpdated` 없음)와 `upcoming`의 직접 기념일 개수 상한은 API.md 3.3에 없다 (단위 4).
