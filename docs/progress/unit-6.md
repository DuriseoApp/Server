# 단위 6: 알림 — 알림 목록·설정, 푸시(FCM)·동기화 신호, 스케줄러, 정리 작업 (notification)

> 브랜치: `feat/6-notification` · PR: (draft 때 채움) · 상태: 진행 중 (2026-11-11 범위 승인, 확인 필요 1~5 추천안대로)
> 기준: PRD v0.12 (FR-NOTI-02·04·05, FR-CAL-05 AC7, 8장, D-39·D-45·D-46·D-73·D-85·D-90·D-92·D-134~D-142), API.md 1.1·2장(R-12·R-16)·3.1(`/me/notification-settings`)·3.5·6장·7장, ERD 3장(notifications, notification_settings, devices, event_reminders)·4.4·DB-03·DB-14·DB-18·DB-19
> Docs 기준 커밋: DuriseoApp/Docs main `cee0885`
> 앞 단위: 단위 5 머지됨 (main `2855633`)
> 리뷰: 동시성 큰 단위라 **opus**로 부른다 (PLAN)

## 요약

| 묶음 | 내용 |
| --- | --- |
| A. API | `GET /notifications`, `GET /notifications/unread-count`, `PUT /notifications/{id}/read`(R-12), `GET`·`PATCH /me/notification-settings` |
| B. 보내는 쪽 | 새 패키지 `push/`: FCM 보내기(firebase-admin), 받는 사람 고르기(유효 세션 R-16, 종류별 설정), 무효 토큰이면 `devices` 삭제. 커밋 뒤(`AFTER_COMMIT`) 리스너가 단위 1~5의 도메인 이벤트를 받아 SYNC·푸시를 보낸다 |
| C. 스케줄러 | 일정 알림(1분마다, 지연 10분 넘으면 행만, D-137), 기념일 알림(매시 정각, 현지 9시, 중복은 같은 날 같은 (user, type, 제목) 행으로 거름 DB-18) |
| D. 정리 작업 | 매일 03:00 KST (ERD 4.4 순서대로) |
| 이월 | PLAN "단위 6" 8건, `PATCH /me` 닉네임 변경의 SYNC `SPACE`/`UPDATED`(이벤트 발행이 아직 없음) |

새 `ErrorCode` 없음. 새 마이그레이션은 필요하면 인덱스만(스케줄러 조회용, `add-migration`). 새 의존성 `com.google.firebase:firebase-admin`. 새 환경변수 없음(`FIREBASE_PROJECT_ID`, `FIREBASE_CREDENTIALS_BASE64`는 이미 있음).

## 확인 필요

| # | 빈 곳 | 추천안 | 근거 |
| --- | --- | --- | --- |
| 1 | `PUT /notifications/{id}/read`가 30일 지난(아직 정리 전) 알림일 때 | 404 `NOT_FOUND`. 목록·개수와 같이 `created_at > now − 30일`인 것만 있는 알림으로 본다 | D-141 "서버에서도 삭제된다", DB-19 |
| 2 | FCM TTL (API.md 7장 "정하지 않는다") | 정하지 않고 FCM 기본값을 쓴다. 다시 정할 일이 생기면 설정값 하나로 바꿀 수 있게 둔다 | API.md 7장 |
| 3 | Firebase 키가 비어 있을 때(로컬·테스트) | 서버는 뜨고 푸시·SYNC만 건너뛴다(시작 때 경고 로그 한 줄). 알림 행은 그대로 만든다 | SETUP.md "Firebase 키는 단위 6 전에" |
| 4 | 프로필 사진 파일 정리(ERD 4.4 마지막 항목) | 단위 7에서 사진 저장소와 함께 같은 정리 작업에 한 단계로 더한다(이 단위는 자리만) | PLAN 단위 7 |
| 5 | 기념일 알림을 받는 사람 | 기념일이 있는 공간의 구성원 각자(커플이면 둘 다, 직접 기념일도 만든 사람만이 아니라 둘 다), 각자의 시간대로 오전 9시 | API.md 7장 "각 사용자의 시간대로 … 오늘이 기념일이면", PRD 8장 |

## 범위

테스트 클래스(`notification/`, `push/`, `cleanup/` 아래). 시계는 주입한 `Clock`으로, FCM은 가짜 발송기로 기록을 본다.

### A. API

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-NOTI-05 AC2 | `GET /notifications`: `created_at > now − 30일`, (`created_at`, `id`) 내림차순 커서, 항목 `{id, type, title, body, targetType, targetId, read, createdAt}` | `FR-NOTI-05 AC2 GET notifications는 30일 안 알림을 최신순으로 주고 같은 created_at도 페이지 경계에서 빠지지 않는다` · `…AC2 30일이 지난 알림은 정리 전에도 목록과 개수에 없다` |
| FR-CAL-01 AC4 | `GET /notifications/unread-count` `{count}` | `FR-CAL-01 AC4 unread-count는 30일 안의 안 읽은 알림만 센다` |
| FR-NOTI-05 AC3, D-142 | `PUT /notifications/{id}/read` 204, 멱등(`read_at` 유지), 30일 지난 것은 404 `<확인 필요 1>` | `FR-NOTI-05 AC3 읽음 처리는 204이고 다시 불러도 read_at이 그대로다` |
| R-12 | 본인 것만. 다른 사람 ID·없는 ID 404 | 허용: 위 / 거부: `R-12 다른 사람의 알림은 목록 개수에 없고 읽음 처리는 404다` |
| FR-NOTI-02 AC1 (D-62) | `GET /me/notification-settings` → `NotificationSettings`, 게스트 포함 | `FR-NOTI-02 AC1 게스트도 알림 설정을 읽고 바꿀 수 있다` |
| FR-NOTI-02 AC2 (D-145) | `PATCH` 부분 수정, `null`은 (필드, `REQUIRED`), boolean 아니면 `INVALID`, 응답은 전체 | `FR-NOTI-02 AC2 PATCH는 보낸 종류만 바꾸고 null은 400 REQUIRED다` |

### B. 보내는 쪽 (API.md 6장·7장)

| ID | 내용 | 테스트 |
| --- | --- | --- |
| 7장 ①②, R-16, DB-14 | 보이는 푸시는 유효 세션이 있고 그 종류 설정이 켜졌을 때만. 연결·해제는 ②를 안 본다. 행은 늘 만든다(이미 단위 1~5가 만듦) | `R-16 유효 세션이 없으면 푸시는 보내지 않고 알림 행과 NOTIFICATION 신호는 남는다` · `FR-NOTI-02 AC1 종류 설정을 끄면 그 종류 푸시가 가지 않는다` · `D-85 연결 해제 알림은 설정과 관계없이 푸시한다` |
| D-136, D-142 | 푸시도 data 메시지: `type`=`PUSH`, `notificationId`, `kind`, `title`, `body`, `targetType`, `targetId`. 우선순위 푸시 high, SYNC normal | `D-136 푸시는 data 메시지이고 notificationId kind title body target을 싣는다` |
| 7장 "푸시 실패" | 재시도 없음. 무효 토큰(`UNREGISTERED` 등)이면 그 `devices` 행 삭제, 일시 오류는 로그만 | `7장 무효 토큰 응답이면 그 기기 행을 지운다` |
| 6장 | 커밋 뒤에만, 실패 재시도 없음. 공간의 **다른** 구성원 기기에 `{type: SYNC, resource, id, action}` | `NFR-SYNC-01 롤백된 트랜잭션은 신호를 보내지 않는다` |
| 6장 `EVENT` | `EventCreated`·`Updated`·`Deleted` → SYNC `EVENT`. `EventCreated`에 알림 행이 있으면 상대에게 `PARTNER_CREATED` 푸시(`partner_created`) + `NOTIFICATION` | `FR-NOTI-04 AC2 일정을 notifyPartner로 만들면 상대에게 EVENT 신호 PARTNER_CREATED 푸시 NOTIFICATION 신호가 간다` · `D-123 같은 값 PATCH는 신호가 없다` |
| 6장 `ANNIVERSARY` | 직접 기념일 생성·수정·삭제 → SYNC `ANNIVERSARY` | `6장 직접 기념일을 바꾸면 상대에게 ANNIVERSARY 신호가 간다` |
| 6장 `MEMO`, D-134·D-135 | 메모 생성·수정·삭제·고정·해제 → SYNC `MEMO`. `PARTNER_CREATED`(메모)·`MEMO_PINNED` 푸시(`memo_pinned`) + `NOTIFICATION`. 즐겨찾기 신호 없음 | `D-135 메모를 고정하면 상대에게 MEMO 신호 MEMO_PINNED 푸시 NOTIFICATION 신호가 간다` · `D-134 즐겨찾기는 신호가 없다` |
| 6장 `SPACE` | `SpaceUpdated`(처음 만난 날·색) → `SPACE`/`UPDATED`(기념일 신호는 따로 없음). **`PATCH /me` 닉네임이 바뀌면 커플 공간 상대에게 `SPACE`/`UPDATED`**(이벤트 발행부터 추가) | `6장 처음 만난 날을 바꾸면 SPACE UPDATED만 가고 ANNIVERSARY 신호는 없다` · `6장 커플 공간에서 닉네임을 바꾸면 상대에게 SPACE UPDATED가 간다` |
| 6장 `LINKED`, 7장 | `CoupleLinked` → **두 사람 모두**에 `SPACE`/`LINKED`(`id` 없음) + 각자 `COUPLE_LINKED` 푸시·`NOTIFICATION` | `FR-COUPLE-02 연결되면 두 사람 모두에게 LINKED 신호와 COUPLE_LINKED 푸시가 간다` |
| 6장 `UNLINKED`, D-53·D-140 | `CoupleUnlinked` → 상대에게 `SPACE`/`UNLINKED` + `COUPLE_UNLINKED` 푸시·`NOTIFICATION`. 탈퇴로 인한 해제는 `CoupleUnlinked(byWithdrawal)`와 `UserWithdrawn.partnerUserId` 중 **한쪽에서만** 보낸다 | `FR-COUPLE-05 해제하면 상대에게 UNLINKED 신호와 COUPLE_UNLINKED 푸시가 한 번 간다` · `FR-AUTH-06 AC2 탈퇴로 해제되면 상대에게 탈퇴 문구 푸시가 한 번만 간다` |
| 6장 `NOTIFICATION` | 행이 생기면 받는 사람 **본인** 기기에 `{resource: NOTIFICATION, id, action: CREATED}`, 푸시를 끈 종류도 | 위 테스트들에 포함 |

### C. 스케줄러 (API.md 7장, DB-03 서버 1대)

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-CAL-05, D-90 | 1분마다 `fire_at <= now`이고 `sent_at` 없는 `event_reminders` → `EVENT_REMINDER` 행(받는 사람 = 알림 주인), `sent_at` 기록, 커밋 뒤 푸시(`event_reminder`·유효 세션) + `NOTIFICATION` | `FR-CAL-05 알림 시각이 되면 EVENT_REMINDER 행을 만들고 sent_at을 기록하고 푸시한다` |
| FR-CAL-05 AC7, D-137 | `fire_at`이 `now − 10분`보다 오래면 행만, 푸시 생략, `sent_at` 기록 | `FR-CAL-05 AC7 10분 넘게 늦은 알림은 행만 만들고 푸시하지 않는다` |
| D-139 | 본문 "[알림 시점] · [시작 시각]"(오후 7:30, 알림 주인 `users.time_zone`), 종일은 "오늘 · 하루 종일"·"내일 · 하루 종일". 제목은 PRD 8장 | `D-139 일정 알림 본문은 알림 주인 시간대의 시작 시각이다` (리마인더 6종) |
| 동시성 | 같은 행을 두 번 보내지 않음: 행 잠금(`FOR UPDATE SKIP LOCKED`) 또는 조건부 `UPDATE … WHERE sent_at IS NULL`. `PATCH /events`·`PATCH /me`의 다시 계산과 겹쳐도 한 번만 | `FR-CAL-05 같은 알림이 두 번 실행돼도 행과 푸시는 하나다` · `FR-CAL-05 다시 계산과 겹쳐도 옛 시각으로 보내지 않는다` |
| D-32·D-55·D-105·D-106·D-138 | 매시 정각: 현지 시가 9인 사용자의 활성 공간에서 오늘(현지) 기념일(자동·직접)마다 `ANNIVERSARY` 행 + 푸시(`anniversary`). 제목 "오늘은 [이름]이에요", 본문 자동 "함께한 지 D+[N]", 직접 반복·올해 > 등록 연도면 "[N]번째 맞는 날이에요", 그 밖 "오늘이에요". 자동은 `target_id` null. 2월 29일은 평년 2월 28일. `AnniversaryCalculator` 재사용 `<확인 필요 5>` | `D-138 현지 오전 9시인 사용자에게 오늘 기념일마다 알림 하나를 만든다` · `D-138 같은 날 다시 돌아도 같은 제목 알림을 만들지 않는다` · `D-106 처음 만난 날이 2월 29일이면 평년 주년 알림은 2월 28일이다` · `D-55 직접 반복 기념일 본문은 N번째 맞는 날이다` |

### D. 정리 작업 (ERD 4.4, 매일 03:00 KST, 이 순서)

| 단계 | 테스트 |
| --- | --- |
| 1 게스트 정리(`WithdrawalService.eraseInTransaction`, 유효 세션 없는 게스트) | `ERD 4.4 유효 세션이 없는 게스트는 탈퇴처럼 정리된다` |
| 2 `purge_at` 지난 공간 삭제 | `ERD 4.4 purge_at이 지난 공간을 지운다` |
| 3 `deleted_at` 있고 어떤 공간에서도 참조 안 되는 사용자 행 삭제 | `ERD 4.4 참조가 없는 탈퇴 사용자 행을 지운다` |
| 4 7일 지난 `invite_code_attempts`, 30일 지난 사용·만료·무효 초대코드 | `ERD 4.4 오래된 초대코드와 시도 기록을 지운다` |
| 5 만료 세션 삭제, 무효화 세션도 지우되 `REPLACED`는 `expires_at`까지 (DB-11) | `ERD 4.4 REPLACED 세션은 만료 전까지 남긴다` |
| 6 유효 세션 없는 사용자의 `devices` (5단계 뒤, DB-14) | `ERD 4.4 세션이 없는 사용자의 기기를 지운다` |
| 7 30일 지난 `notifications` | `ERD 4.4 30일 지난 알림을 지운다` |
| 8 프로필 사진 파일 정리 → 단위 7 `<확인 필요 4>` | — |

단계마다 따로 트랜잭션(한 단계 실패가 다음 단계를 막지 않게, 로그만). 게스트 정리는 사용자마다 따로.

### 이월 (PLAN "단위 6")

| 할 일 | 확인 |
| --- | --- |
| 위 B의 리스너 7종 (단위 1~5 이벤트) | B 테스트 |
| 스케줄러 2개, 정리 작업 | C·D 테스트 |
| 사용자 행 잠금 순서(사용자 → 공간 → 콘텐츠)를 새 흐름(스케줄러·정리)에서도 지킨다. `PartnerLockRetry` 3번 뒤 교착 500 가능성은 기록만 | 리뷰(opus) |

## 결정

구현하면서 정한 것 중 문서에 없는 것. 문서에 있어야 할 결정이면 여기 적지 말고 작업을 멈추고 보고한다.

묶음 A·B (2026-11-11):

- R-12는 `SpaceAccessPolicy.requireNotificationOwner`로 둔다. 서비스가 "보이는(30일 안) 알림의 받는 사람"을 읽어 넘기고, 없음·남의 것·30일 지남은 같은 404다. 읽음 처리는 `update … where id and read_at is null`이라 이미 읽은 알림은 `read_at`이 그대로다.
- 커서는 `memo`와 같은 방식(`created_at|id`를 base64url). 목록은 (`created_at`, `id`) 내림차순, id 비교는 PostgreSQL uuid 비교다.
- `PATCH /me/notification-settings` 본문이 비어 있어도(본문 없음) 200이고 모르는 필드는 무시한다.
- 닉네임 변경 이벤트는 새 클래스 없이 `SpaceUpdated(spaceId, userId)`를 재사용한다: 닉네임이 **저장값과 다르게** 바뀌고 활성 공간이 커플일 때만 `UserService.updateProfile`이 발행한다(공백을 지운 값이 같으면 없음).
- `SPACE` 신호의 `id`: `UPDATED`·`UNLINKED`는 공간 ID를 싣고, `LINKED`만 `id`가 없다(API.md 6장이 `LINKED`만 "id 없음"이라 적음). `UNLINKED`의 `id` 유무는 문서에 없어 공간 ID로 정했다. 앱은 어차피 `GET /me`를 다시 읽는다.
- 탈퇴 해제는 `CoupleUnlinked`에서만 보낸다(알림 ID가 거기에만 있다). `UserWithdrawn`은 푸시 쪽에서 듣지 않는다.
- FCM data 값은 문자열뿐이라 값이 없는 `body`·`targetType`·`targetId`는 키를 넣지 않는다.
- 무효 토큰은 `UNREGISTERED`와 `INVALID_ARGUMENT`일 때 `devices` 행을 지운다(형식이 틀린 토큰도 `INVALID_ARGUMENT`로 온다). 그 밖은 로그만(오류 코드만, 토큰 없음).
- 푸시는 같은 스레드에서 보낸다(요청 응답 전에 FCM 호출이 끝난다). 실패는 삼키지만 지연은 응답에 더해진다. 필요하면 나중에 실행기를 분리한다.
- Firebase 키가 있는데 읽을 수 없으면(base64·JSON 오류) 서버가 시작하지 않는다. 비어 있으면 건너뛰는 발송기로 뜨고 경고 한 줄.
- `push/`는 다른 도메인 테이블(devices, auth_sessions, notification_settings, space_members, notifications)을 읽기만 하므로 `SpaceAccessPolicy`처럼 네이티브 쿼리로 읽는다(`PushStore`, 모든 메서드 `REQUIRES_NEW`).
- 묶음 C가 쓸 공용 함수: `NotificationDispatcher.dispatch(notificationId, withPush = true)` (커밋 뒤에 부른다. 지연 10분 초과는 `withPush = false`).
- 의존성 `com.google.firebase:firebase-admin:9.5.0`(이 환경에서 Maven을 못 봐 버전은 CI에서 확인).

묶음 C·D (2026-11-11):

- 코드는 `job/`에 둔다(`EventReminderJob`, `AnniversaryReminderJob`, `CleanupJob`, SQL은 `CleanupStore`). 다른 도메인 테이블(event_reminders, events, anniversaries, spaces, users, auth_sessions, devices …)은 `push/PushStore`처럼 네이티브 쿼리로 읽고 지운다. 알림 행은 `NotificationService.createEventReminder`·`createAnniversary`로 만든다(`EventReminderTexts`가 일정 알림 본문).
- 스케줄링은 `duriseo.jobs.enabled`(기본 true)가 true일 때만 켠다(`JobConfig`). 테스트는 `src/test/resources/application.properties`에서 false로 두고 작업 메서드(`run()`)를 직접 부른다. 작업은 `fixedDelay`·`cron`이라 같은 작업은 겹치지 않고, 스케줄러 스레드는 3개(`spring.task.scheduling.pool.size`)라 작업끼리 서로 기다리지 않는다. ShedLock 없음(DB-03).
- 일정 알림은 후보 ID를 잠금 없이 (`fire_at`, `id`) 커서로 100개씩 읽고, **알림 행마다 자기 트랜잭션**에서 처리한다: 사용자 행 `FOR SHARE`(`UserService.lockShare`) → 알림 행 `FOR UPDATE OF r SKIP LOCKED`(같은 SQL이 `sent_at IS NULL`·`fire_at <= now`를 다시 확인하고 일정 제목·시작·공간과 알림 주인 시간대를 함께 읽음) → `sent_at` 기록 + 알림 행 생성 → 커밋 → `dispatch`. 사용자 행을 먼저 잡는 이유: 알림 행을 먼저 잡으면 `PATCH /me`(사용자 행 `FOR UPDATE` → 알림 행 수정)와 서로 기다린다(알림 행 잠금 → 알림 행 insert의 사용자 FK 확인). 한 행의 실패는 로그만 남기고 다음 행으로 넘어간다(커서라 실패한 행이 뒤를 막지 않는다).
- D-137의 "10분 넘게"는 `fire_at < now − 10분`(정확히 10분은 푸시)이다.
- 기념일 알림은 먼저 `users.time_zone` 값 중 지금 현지 시가 9인 것을 고르고(해석 못 하는 값은 UTC로 봄), 그 시간대의 활성 공간 구성원을 사용자 ID 순으로 100명씩 읽어 **사용자마다 자기 트랜잭션**에서 처리한다. 사용자 행을 `lockForUpdate`로 잡은 뒤 활성 공간·시간대를 다시 읽고 현지 시가 아직 9인지 다시 확인한다. 같은 제목이 같은 실행·현지 오늘 구간에 이미 있으면 건너뛴다(DB-18). 알림의 `space_id`는 사용자의 활성 공간이다.
- 정리 작업의 지우기는 한 묶음(500행)씩 `limit` 서브쿼리로 되풀이하고 묶음마다 트랜잭션이다. 지우는 조건은 모두 삭제 SQL 안에 있어 조회와 삭제 사이에 바뀐 행(복구된 공간 등)은 지우지 않는다. 경계: `purge_at <= now`, 알림 `created_at <= now − 30일`(조회 경계 `>`와 짝), 시도 기록 `attempted_at < now − 7일`. 초대코드가 "끝난 때" = 사용·무효·만료 시각 중 가장 이른 것이고 그것이 30일보다 오래면 지운다(유효한 코드는 끝난 때가 미래).
- 3단계(탈퇴 사용자 행)는 `space_members`(떠난 소속 포함)와 `events.author_id/updated_by`, `anniversaries.author_id`, `memos.author_id/pinned_by`(RESTRICT)가 없을 때만 지운다. 게스트 정리 뒤 개인 공간이 지워지면 같은 실행의 3단계가 그 게스트 행까지 지운다.
- 게스트 정리는 사용자마다 `CleanupStore.eraseGuest`(트랜잭션 하나)에서 유효 세션이 아직 없는지 다시 확인한 뒤 `eraseInTransaction(unlinkFromKakao = false)`를 부른다. 게스트는 커플이 될 수 없어 `PartnerLockRetry` 없이 부른다.
- 새 마이그레이션 없음: 1분 작업은 `ix_event_reminders_unsent_fire_at`(부분), 알림 목록은 `ix_notifications_user_created`, 공간 삭제는 `ix_spaces_purge_at`을 쓴다. 하루 한 번 도는 정리(알림 `created_at`, 세션 `expires_at`, 시도 기록 `attempted_at`)와 매시 도는 `users.time_zone` 고유값 조회는 인덱스 없는 훑기다. 데이터가 커져 느려지면 그때 새 버전으로 인덱스를 더한다.

## 리뷰 기록

| 회차 | Critical | Major | 비고 |
| --- | --- | --- | --- |

## 남은 일

- 사람: Firebase 서비스 계정 키가 Parameter Store `/duriseo/secret/FIREBASE_SERVICE_ACCOUNT_JSON`에 있는지 확인(개발 서버 실제 푸시)
- 단위 7: 프로필 사진 파일 정리를 정리 작업에 더하기, 사진 변경의 SYNC `SPACE`/`UPDATED`
