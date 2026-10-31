# 단위 3: 캘린더 — 일정, 일정 알림, 날짜 범위 (event)

> 브랜치: `feat/3-calendar` · PR: (링크) · 상태: 범위 확인 중 (구현 전, 코드 없음)
> 기준: PRD v0.8 (FR-CAL-01 AC2·AC6, FR-CAL-02, FR-CAL-03, FR-CAL-05, FR-NOTI-04 일부, NFR-SEC-02, NFR-TIME-01, NFR-LIMIT-01, NFR-SYNC-01·03, D-10, D-12, D-13, D-16, D-34, D-35, D-52, D-59, D-64, D-74, D-77), API.md 1장·1.1·2장(R-01·R-02·R-07·R-10·R-13·R-15)·3.3(일정 6개 경로)·3.5·5장·6장·7장, ERD 3.3(events·event_reminders)·4.1·4.2·4.3·4.5·5장(DB-05·06·07·13)
> Docs 기준 커밋: DuriseoApp/Docs main `afc3d1b` (PRD v0.8). 읽은 경로는 `/home/claude/docs-main` (`../Docs` 대신)
> 앞 단위: 단위 2 머지됨 (main `7016114`)

## 요약

- 새 API 경로 **7개** (API.md 3.3 표의 일정 행 7개): `GET /events`, `POST /events`, `GET /events/{id}`, `PATCH /events/{id}`, `DELETE /events/{id}`, `PUT /events/{id}/reminder`, `DELETE /events/{id}/reminder`. 같은 표의 `/anniversaries` 계열은 단위 4
- FR **5개**를 다룬다: FR-CAL-01(AC2 서버 몫, AC6), FR-CAL-02, FR-CAL-03, FR-CAL-05, FR-NOTI-04(행 생성만, 확인 필요 7). FR-CAL-04는 단위 2에서 끝났다.
- R- 규칙 **6개**: R-01(일정 경로 적용), R-02, R-07, R-10, R-13, R-15(일정 쓰기·`GET /events` 잘라내기).
- 새 `ErrorCode` 없음 (`NOT_AUTHOR`, `INVALID_TARGET`, `DATE_OUT_OF_RANGE`, `VALIDATION_FAILED`, `NOT_FOUND`가 이미 있다). **새 마이그레이션 없음** (V1에 `events`, `event_reminders`, 제약·인덱스 모두 있다).
- 단위 2에서 넘어온 일: `CoupleContentGateway`의 events·`event_reminders` 네이티브 SQL 3곳과 `WithdrawalDataEraser`의 `event_reminders` 삭제를 일정 도메인 서비스로 옮기고, 단위 1이 남긴 `UserTimeZoneChanged`를 듣는 리스너를 만든다.
- **확인 필요 13건** (아래). 이 중 1~6은 응답·검증 모양이라 구현 전에 정해야 하고, 7~8은 단위 6과의 경계다.

## 범위

### 1. 기능 (FR)과 수용 기준

AC 하나에 테스트 하나 이상 (CONVENTIONS). "앱 동작"은 서버 API가 없어 서버 테스트가 없다 (단위 1 결정). JVM 메서드 이름에 `/`를 못 쓰므로 경로는 `POST events`처럼 적는다 (단위 1 결정). `<확인 필요 N>`은 아래 확인 필요가 정해지면 채운다. 테스트 클래스는 `event/` 아래 `EventQueryTest`, `EventWriteTest`, `EventPermissionTest`, `EventReminderTest`, `EventDateRangeTest`로 나눈다 (예정).

**FR-CAL-01 캘린더 홈** (D-30, D-74) — 서버 몫은 AC2의 일정 조회와 AC6

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-CAL-01 AC1 | 오늘이 속한 달로 시작 | 앱 동작 |
| FR-CAL-01 AC2 | 일정이 있는 날 표시, 날짜를 누르면 그 날 목록. 서버는 날짜 범위의 일정을 준다 (기념일·메모는 단위 4·5, 정렬 표시는 앱) | `FR-CAL-01 AC2 GET events는 범위 안에 걸치는 일정만 돌려주고 범위 밖 일정은 빼고 돌려준다` |
| | | `FR-CAL-01 AC2 GET events는 종일 일정과 시간 일정을 함께 돌려준다` |
| | | `FR-CAL-01 AC2 GET events는 요청자의 공간 일정만 돌려준다` (다른 공간·해제된 이전 공간 일정은 없음. R-01) |
| | | `FR-CAL-01 AC2 시간 일정의 범위 경계는 요청자 시간대 기준 날짜로 판단한다` (UTC 15:30 = 서울 다음 날 0:30 일정이 서울 사용자의 다음 날 조회에 걸림, 확인 필요 3) |
| | | `FR-CAL-01 AC2 from이 to보다 뒤면 400 VALIDATION_FAILED이고 3개월을 넘어도 400이다` (확인 필요 3) |
| | | `FR-CAL-01 AC2 from이나 to가 없거나 날짜 형식이 아니면 400 VALIDATION_FAILED` (확인 필요 3) |
| FR-CAL-01 AC3 | D+N (기념일) | 단위 4 |
| FR-CAL-01 AC4 | 알림 버튼 점 | 단위 6 (`GET /notifications/unread-count`) |
| FR-CAL-01 AC5 | 개인 공간 앱바 | 앱 동작 |
| FR-CAL-01 AC6 | 1950-01-01 ~ 2050-12-31. 쓰기는 400 `DATE_OUT_OF_RANGE`, 범위 조회는 잘라서 조회 (D-74). 일정 몫만. 메모 날짜·기념일은 단위 5·4 | 아래 R-15 테스트 |

**FR-CAL-02 일정 작성** (D-10, D-13, D-34, D-59, D-64) — `POST /events`

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-CAL-02 AC1 | 제목·시작·종료로 저장하면 현재 공간에 일정이 생긴다. 제목 필수 | `FR-CAL-02 AC1 제목과 시작·종료를 보내면 요청자의 활성 공간에 일정이 만들어진다` (201, `author_id`, `space_id`, `is_together` false, `updated_by` 확인 필요 2) |
| | | `FR-CAL-02 AC1 제목이 없거나 빈 값이면 400 VALIDATION_FAILED (title REQUIRED)` (공백만 있는 값은 확인 필요 2) |
| | | `FR-CAL-02 AC1 제목이 50자를 넘으면 400 VALIDATION_FAILED (title TOO_LONG)이고 코드 포인트로 센다` (이모지 포함, NFR-LIMIT-01, D-82) |
| | | `FR-CAL-02 AC1 일정 메모가 1000자를 넘으면 400 VALIDATION_FAILED (note TOO_LONG)` |
| | | `FR-CAL-02 AC1 여러 날 일정은 종료 날짜가 시작보다 뒤인 일정으로 저장된다` (D-64) |
| FR-CAL-02 AC2 | 종일이면 시간 없이 시작일·종료일만 저장 | `FR-CAL-02 AC2 종일 일정은 start_date·end_date만 저장하고 start_at·end_at은 null이다` (확인 필요 1·2의 `start`/`end` 형식) |
| | | `FR-CAL-02 AC2 종일 일정에 시간이 있는 값을 보내면 400 VALIDATION_FAILED (확인 필요 2)` |
| FR-CAL-02 AC3 | 종료가 시작보다 이르면 저장되지 않는다 | `FR-CAL-02 AC3 종료가 시작보다 이른 시간 일정은 400 VALIDATION_FAILED (end INVALID)이고 저장되지 않는다` |
| | | `FR-CAL-02 AC3 종료 날짜가 시작 날짜보다 이른 종일 일정도 400 VALIDATION_FAILED (end INVALID)` |
| | | `FR-CAL-02 AC3 종료가 시작과 같은 일정은 저장된다` (DB `end_at >= start_at`) |
| FR-CAL-02 AC4 | 대상(나·함께, 기본 나), 일정 메모, 일정 알림(기본 없음)은 선택. 개인 공간은 항상 나 (D-34, D-59) | `FR-CAL-02 AC4 isTogether를 생략하면 false이고 note를 생략하면 null이다` |
| | | `FR-CAL-02 AC4 커플 공간에서 isTogether true로 만들면 함께 일정이 된다` |
| | | `FR-CAL-02 AC4 POST events는 event_reminders를 만들지 않고 응답의 알림은 null이다` (D-59, API.md 3.3) |
| | | `FR-CAL-02 AC4 개인 공간에서 isTogether true면 400 INVALID_TARGET이고 저장되지 않는다` (R-10 테스트와 같음) |
| | | `FR-CAL-02 AC4 일정의 대상 사용자는 받지 않는다: 본문의 다른 사용자 ID 필드는 일정의 작성자·대상을 바꾸지 않는다` (R-10, 확인 필요 2) |
| FR-CAL-02 AC5 | 여러 날 일정은 시작일~종료일 모든 날짜에 표시 | `FR-CAL-02 AC5 3일 일정은 시작일·중간일·종료일 어느 날을 조회해도 나온다` (표시는 앱) |

**FR-CAL-03 일정 수정·삭제** (D-12, D-52, D-77) — `GET /events/{id}`, `PATCH /events/{id}`, `DELETE /events/{id}`

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-CAL-03 AC1 | 내가 만든 일정은 수정·삭제 | `FR-CAL-03 AC1 작성자는 자기 일정의 제목·메모·일시·종일을 수정할 수 있다` (200, `updated_by`, `updated_at`) |
| | | `FR-CAL-03 AC1 작성자는 자기 일정을 삭제할 수 있고 event_reminders도 함께 지워진다` (204, CASCADE) |
| | | `FR-CAL-03 AC1 일정을 삭제해도 그 일정을 가리키는 알림 행은 지우지 않는다` (D-43, API.md 3.5. `notifications` 픽스처) |
| FR-CAL-03 AC2 | 상대가 만든 '함께'가 아닌 일정은 서버도 수정·삭제를 거부 (NFR-SEC-02) | `FR-CAL-03 AC2 상대의 개인 일정을 PATCH하면 403 NOT_AUTHOR이고 아무것도 바뀌지 않는다` |
| | | `FR-CAL-03 AC2 상대의 개인 일정을 DELETE하면 403 NOT_AUTHOR이고 일정이 남는다` |
| | | `FR-CAL-03 AC2 상대의 개인 일정도 GET으로 볼 수 있다` (같은 공간, R-01) |
| FR-CAL-03 AC3 | 작성자 닉네임 표시 | `FR-CAL-03 AC3 일정 응답에 작성자 ID가 있고 GET space의 구성원 닉네임으로 찾을 수 있다` (닉네임은 `GET /space`, 응답 모양은 확인 필요 1) |
| FR-CAL-03 AC4 | '함께' 일정은 작성자와 상대 모두 수정·삭제. 대상 변경은 작성자만 (R-13) | `FR-CAL-03 AC4 함께 일정은 상대도 수정할 수 있다` |
| | | `FR-CAL-03 AC4 함께 일정은 상대도 삭제할 수 있다` |
| | | `FR-CAL-03 AC4 상대가 함께 일정의 isTogether를 false로 바꾸면 403 NOT_AUTHOR이고 일정은 그대로다` |
| | | `FR-CAL-03 AC4 상대가 함께 일정을 isTogether true 그대로 보내며 수정하면 허용된다` (값을 바꾸지 않는 요청) |
| | | `FR-CAL-03 AC4 작성자는 함께 일정을 개인 일정으로, 개인 일정을 함께 일정으로 바꿀 수 있다` |
| FR-CAL-03 AC5 | 함께가 아닌 일정은 작성자만 | `FR-CAL-03 AC5 함께가 아닌 일정은 작성자만 수정·삭제한다` (AC2 테스트가 거부, AC1이 허용을 덮음) |
| (NFR-SYNC-03) | 동시 수정은 마지막 저장이 이긴다 | `NFR-SYNC-03 함께 일정을 두 사람이 동시에 수정해도 오류 없이 한쪽 값으로 저장된다` (동시성, 낙관적 락 없음) |

**FR-CAL-05 일정 알림** (D-16, D-59) — `PUT /events/{id}/reminder`, `DELETE /events/{id}/reminder`, `PATCH /events/{id}`의 재계산. 발송은 단위 6

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-CAL-05 AC1 | 상대 일정 포함 아무 일정에, 없음·정시·10분 전·1시간 전·1일 전 중 하나를 고르면 그 사용자 것만 생긴다 | `FR-CAL-05 AC1 시간 일정에 AT_TIME·MIN_10·HOUR_1·DAY_1을 고르면 event_reminders 행이 요청자 것으로 생기고 fire_at이 시작 시각 기준으로 계산된다` (확인 필요 5. 옵션마다 한 케이스) |
| | | `FR-CAL-05 AC1 상대가 만든 일정에도 내 알림을 설정할 수 있다` (R-01 통과, 작성자 무관) |
| | | `FR-CAL-05 AC1 알림을 다시 고르면 행이 하나로 유지되고 option과 fire_at이 바뀐다` (`uq_event_reminders_event_user`, 멱등) |
| | | `FR-CAL-05 AC1 알림 끄기(DELETE reminder)는 내 행만 지운다` (204, 상대의 행은 남음) |
| | | `FR-CAL-05 AC1 알림이 없는 일정에 DELETE reminder를 불러도 <확인 필요 1>` |
| | | `FR-CAL-05 AC1 허용하지 않는 option 값은 400 VALIDATION_FAILED (option INVALID)이고 option이 없으면 REQUIRED` (확인 필요 4) |
| FR-CAL-05 AC2 | 상대에게는 내 알림이 안 보인다 | `FR-CAL-05 AC2 A가 알림을 설정해도 B의 GET events/{id}와 GET events 응답의 알림은 null이다` |
| | | `FR-CAL-05 AC2 A와 B가 각자 설정하면 각자 자기 알림만 본다` |
| FR-CAL-05 AC3 | 종일 일정의 선택지는 없음·당일 오전 9시·1일 전 오전 9시 | `FR-CAL-05 AC3 종일 일정에 ALLDAY_9AM·ALLDAY_PREV_9AM을 고르면 fire_at이 요청자 시간대 기준 당일·전날 09:00이다` (확인 필요 5) |
| | | `FR-CAL-05 AC3 종일 일정에 시간 일정용 option을, 시간 일정에 종일용 option을 고르면 <확인 필요 4>` |
| FR-CAL-05 AC4 | 누구든 일시를 바꾸면 모든 사용자의 알림 시각이 다시 계산된다 | `FR-CAL-05 AC4 작성자가 일시를 바꾸면 알림을 건 두 사람의 fire_at이 새 일시 기준으로 다시 계산된다` |
| | | `FR-CAL-05 AC4 상대가 함께 일정의 일시를 바꿔도 알림을 건 두 사람의 fire_at이 다시 계산된다` |
| | | `FR-CAL-05 AC4 제목·메모만 바꾸면 fire_at은 그대로다` |
| | | `FR-CAL-05 AC4 일정을 종일↔시간으로 바꾸면 <확인 필요 6>` |
| | | `FR-CAL-05 AC4 이미 보낸 알림(sent_at)이 있는 일정의 일시를 바꾸면 <확인 필요 6>` |
| | | `FR-CAL-05 AC4 사용자의 시간대가 바뀌면 그 사용자의 종일 알림 fire_at만 새 시간대 기준으로 다시 계산된다` (`UserTimeZoneChanged` 리스너, 같은 트랜잭션. 시간 일정 알림은 절대 시각이라 그대로) |
| | | `FR-CAL-05 AC4 계산한 fire_at이 이미 지났으면 <확인 필요 5>` |
| FR-CAL-05 AC1 (발송) | 해당 시각에 푸시 | 단위 6 (1분 스케줄러, API.md 7장) |

**FR-NOTI-04 상대에게 알림 보내기** (D-35) — 일정 몫. 행 생성이 단위 3인지는 확인 필요 7

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-NOTI-04 AC1 | 작성 화면 스위치, 기본 켜짐 | 서버: `FR-NOTI-04 AC1 notifyPartner를 생략하면 true로 본다` (아래 AC2 테스트로 확인). 화면은 앱 |
| FR-NOTI-04 AC2 | 켜고 저장하면 상대에게 알림 (푸시는 상대 설정이 켜진 경우만) | `FR-NOTI-04 AC2 커플 공간에서 notifyPartner true로 만들면 상대에게만 PARTNER_CREATED 알림 행이 생긴다` (제목 "[작성자 닉네임]님이 일정을 추가했어요", 본문 "[날짜] · [일정 제목]", `target_type` EVENT, `target_id` 일정 ID, `space_id` 커플 공간. 서식은 확인 필요 7) |
| | | `FR-NOTI-04 AC2 notifyPartner false로 만들면 알림 행이 생기지 않는다` |
| | | `FR-NOTI-04 AC2 상대의 partner_created 설정이 꺼져 있어도 알림 행은 생긴다` (DB-07, 푸시만 설정을 본다. 푸시는 단위 6) |
| | | `FR-NOTI-04 AC2 일정 생성 이벤트는 커밋된 뒤에만 리스너에 전달된다` (단위 6의 푸시·SYNC `EVENT`/`CREATED` 연결점, 확인 필요 8) |
| FR-NOTI-04 AC3 | 개인 공간에서는 스위치 없음, 수정할 때는 알림 없음 | `FR-NOTI-04 AC3 개인 공간에서 notifyPartner true로 만들어도 알림 행이 생기지 않는다` |
| | | `FR-NOTI-04 AC3 PATCH events는 알림 행을 만들지 않는다` (수정·삭제는 알리지 않음, PRD 8장) |
| | | `FR-NOTI-04 AC2 PARTNER_CREATED 행의 space_id는 커플 공간이라 연결을 해제하면 지워진다` (D-45. 알림 삭제 자체는 단위 2 `CoupleUnlinkTest`가 확인, 여기서는 `space_id`가 채워지는지 확인) |

**단위 2에서 넘어온 것** (unit-2.md 남은 일, "단위 6과의 경계" 표)

| 항목 | 지금 | 단위 3에서 | 테스트 |
| --- | --- | --- | --- |
| `CoupleContentGateway.hasContent`의 `events` 개수 | `couple/application`에서 네이티브 SQL | 일정 서비스의 `existsInSpace(spaceId)`를 부르고, 게이트웨이는 `anniversaries`·`memos`만 센다 (게이트웨이 자체는 단위 4·5가 끝낼 때까지 남는다) | 기존 `FR-COUPLE-03 AC1·AC4`, `FR-COUPLE-05 AC4` 테스트가 그대로 통과. 추가: `FR-COUPLE-03 AC1 일정만 있는 개인 공간도 기록이 있는 공간으로 본다` |
| `CoupleContentGateway.moveAll`의 `events` | `update events set space_id` | 일정 서비스의 `moveSpace(from, to)` | `FR-COUPLE-03 AC1 import true면 일정이 author_id·is_together·일정 알림을 유지한 채 커플 공간으로 옮겨진다` (기존 테스트 보강: `event_reminders` 행이 남음) |
| `CoupleContentGateway.deleteEventReminders` | 해제 때 그 공간 일정의 알림을 네이티브 SQL로 삭제 | 일정 알림 서비스의 `deleteOfSpace(spaceId)` | 기존 `FR-COUPLE-05 AC1 … event_reminders … 지워지고` 테스트 통과 |
| `WithdrawalDataEraser`의 `delete from event_reminders where user_id` | 직접 SQL | 일정 알림 서비스의 `deleteAllOf(userId)`. `memo_favorites`는 단위 5 | 기존 `WithdrawalTest`의 `event_reminders` 삭제 확인 통과 |
| `UserTimeZoneChanged` (단위 1이 발행) | 듣는 쪽 없음 | 같은 트랜잭션 리스너가 그 사용자의 종일 알림 `fire_at`을 다시 계산 | 위 FR-CAL-05 AC4 시간대 테스트 |
| `SpaceAccessPolicy` | R-01·08·09·11·14·17·18 | R-02·R-10·R-13 메서드를 더한다 (공통 모듈은 도메인을 모르므로 `spaceId`, `authorId`, `isTogether` 값을 받는다) | 아래 2장 |
| `common/api/ErrorCode` | `NOT_AUTHOR`, `INVALID_TARGET`, `DATE_OUT_OF_RANGE` 이미 있음 | 변경 없음 | — |

### 2. 권한 규칙 (API.md 2장) — `SpaceAccessPolicy`

규칙마다 허용·거부 둘 이상 (CONVENTIONS). 3.3의 PATCH·DELETE 권한 칸에는 R-01이 없지만 2장 머리말("모든 요청은 먼저 활성 공간 검사")대로 R-01을 먼저 적용한다 (확인 필요 9).

| ID | 내용 | 테스트 |
| --- | --- | --- |
| R-01 | 일정 경로 6개(`GET /events/{id}`, `PATCH`, `DELETE`, `PUT reminder`, `DELETE reminder`, 그리고 `GET /events`의 결과)는 요청자의 활성 공간 일정만. 다른 공간·없는 ID는 같은 404 `NOT_FOUND` | 허용: `R-01 요청자의 공간 일정은 GET·PATCH·DELETE·PUT reminder·DELETE reminder가 된다` |
| | | 거부: `R-01 다른 커플 공간의 일정은 GET events/{id}가 404 NOT_FOUND다` |
| | | 거부: `R-01 다른 공간의 일정은 PATCH·DELETE·PUT reminder·DELETE reminder도 404이고 아무것도 바뀌지 않는다` |
| | | 거부: `R-01 없는 일정 ID와 다른 공간 일정은 본문까지 같은 404다` (존재를 드러내지 않음, DB-05) |
| | | 거부: `R-01 해제된 이전 커플 공간의 일정은 404다` (해제 뒤 개인 공간에서 부름) |
| | | 거부: `R-01 GET events는 다른 공간의 일정을 결과에 넣지 않는다` |
| | | 허용: `R-01 가져오기 대기 중인 개인 공간의 일정은 커플 공간에서 404이고 가져온 뒤에는 보인다` (활성 공간만 R-01의 기준) |
| R-02 | 일정 수정·삭제: 요청자 = `author_id` 또는 `is_together = true` | 허용: `R-02 작성자는 함께가 아닌 자기 일정을 수정·삭제할 수 있다` |
| | | 허용: `R-02 함께 일정은 작성자가 아니어도 수정·삭제할 수 있다` |
| | | 거부: `R-02 작성자가 아니고 함께도 아니면 PATCH·DELETE가 403 NOT_AUTHOR이다` |
| | | 거부: `R-02 거부되면 일정·알림이 하나도 바뀌지 않는다` |
| | | 거부: `R-02 조회와 알림 설정은 작성자가 아니어도 된다` (R-02는 수정·삭제만) |
| R-07 | 일정 알림: 본인 행만 만들고 바꾼다. 응답에는 요청자 알림만 | 허용: `R-07 PUT reminder는 요청자의 행만 만들고 바꾼다` |
| | | 허용: `R-07 일정 응답은 요청자 자신의 알림을 담는다` (`GET events`, `GET events/{id}`, `PATCH`) |
| | | 거부: `R-07 요청자는 다른 사용자의 알림 행을 만들거나 바꾸거나 지울 수 없다` (본문에 `userId`를 보내도 무시, 상대 행 불변) |
| | | 거부: `R-07 일정 응답에 상대의 알림 option·fireAt이 들어가지 않는다` |
| R-10 | 일정 대상: 본문은 `isTogether`만. 개인 공간에서 `isTogether = true`면 400 `INVALID_TARGET` | 허용: `R-10 커플 공간은 isTogether true·false 모두 만들 수 있다` |
| | | 허용: `R-10 개인 공간은 isTogether false로 만들 수 있다` |
| | | 거부: `R-10 개인 공간에서 POST events의 isTogether true는 400 INVALID_TARGET이고 저장되지 않는다` |
| | | 거부: `R-10 개인 공간에서 PATCH events로 isTogether를 true로 바꾸면 400 INVALID_TARGET` |
| R-13 | 일정 수정에서 `isTogether`를 바꾸는 요청은 작성자만. 값을 바꾸지 않으면 R-02만 | 허용: `R-13 작성자는 isTogether를 바꿀 수 있다` |
| | | 허용: `R-13 상대가 isTogether 값을 바꾸지 않으면 함께 일정의 다른 필드를 수정할 수 있다` |
| | | 거부: `R-13 상대가 함께 일정의 isTogether를 바꾸면 403 NOT_AUTHOR이고 다른 필드도 바뀌지 않는다` (같은 요청에 제목도 있으면 통째로 거부) |
| R-15 | 일정 `start`·`end` 날짜 1950-01-01 ~ 2050-12-31(시간 일정은 요청자 시간대 기준 날짜). 쓰기는 400 `DATE_OUT_OF_RANGE`, `GET /events`는 잘라서 조회 | 허용: `R-15 경계 날짜 1950-01-01과 2050-12-31은 일정으로 저장된다` (종일, 시간 일정 각각) |
| | | 거부: `R-15 시작 날짜가 1950-01-01보다 앞이면 POST events가 400 DATE_OUT_OF_RANGE (start OUT_OF_RANGE)다` |
| | | 거부: `R-15 종료 날짜가 2050-12-31보다 뒤면 400 DATE_OUT_OF_RANGE (end OUT_OF_RANGE)다` |
| | | 거부: `R-15 PATCH events로 날짜를 범위 밖으로 바꾸면 400 DATE_OUT_OF_RANGE이고 일정이 그대로다` |
| | | 허용/거부: `R-15 시간 일정의 날짜는 UTC가 아니라 요청자 시간대 기준이다` (UTC 2050-12-31T20:00Z는 서울 사용자에게 2051-01-01이라 거부, UTC 사용자에게는 허용. UTC 1949-12-31T15:00Z는 서울 사용자에게 1950-01-01이라 허용) |
| | | 허용: `R-15 GET events는 from이 1950-01-01보다 앞이어도 오류 없이 1950-01-01부터 조회한다` |
| | | 허용: `R-15 GET events는 to가 2050-12-31보다 뒤여도 오류 없이 2050-12-31까지 조회한다` |
| | | 허용: `R-15 잘라낸 조회 구간이 비면 빈 items를 돌려준다` (예: from=2051-01-01&to=2051-02-01) |
| | | 거부: `R-15 from > to와 3개월 초과 검사는 잘라내기 전 값으로 먼저 한다` (예: from=1948-01-01&to=1950-02-01은 잘라내면 1개월이어도 400 VALIDATION_FAILED) |

범위 밖 규칙: R-03·R-04·R-06(메모, 단위 5), R-05(직접 기념일, 단위 4), R-12(알림 조회, 단위 6), R-16(푸시 유효 세션, 단위 6). R-08·R-09·R-18은 단위 2, R-11·R-14·R-17은 단위 1에서 끝났다.

### 3. API 경로

모든 경로는 `/api/v1` 아래. API.md 3.3은 요청·응답 모양을 대부분 적지 않았고 API.md 7행은 3.1·3.2 밖의 상세를 서버 OpenAPI가 정한다고 한다. Docs에 없는 모양은 *(미정)* 으로 두고 확인 필요 1·2·3에 모았다.

| 메서드·경로 | 요청 | 성공 응답 | 오류 | 흐름·규칙 |
| --- | --- | --- | --- | --- |
| `GET /events?from=&to=` | 쿼리 `from`, `to` *(형식 미정)* | 200 `{items: [Event]}` *(Event 모양 미정)*. 각 일정에 요청자의 알림만 | 400 `VALIDATION_FAILED`(범위 관련, 확인 필요 3) | R-01, R-07, R-15, FR-CAL-01 |
| `POST /events` | `{title, note, isAllDay, start, end, isTogether, notifyPartner}` | 201 `Event` *(추정)*. 알림은 만들지 않는다 (D-59) | 400 `VALIDATION_FAILED`, 400 `DATE_OUT_OF_RANGE`, 400 `INVALID_TARGET` | R-01, R-10, R-15, FR-CAL-02, FR-NOTI-04 |
| `GET /events/{id}` | 없음 | 200 `Event` | 404 `NOT_FOUND` | R-01, R-07, FR-CAL-03 |
| `PATCH /events/{id}` | 일부 필드 *(부분 수정 규칙 미정, 확인 필요 2)* | 200 `Event` *(추정)* | 400 (위와 같음), 403 `NOT_AUTHOR`, 404 | R-01, R-02, R-10, R-13, R-15, FR-CAL-03, FR-CAL-05 AC4 |
| `DELETE /events/{id}` | 없음 | 204 | 403 `NOT_AUTHOR`, 404 | R-01, R-02, FR-CAL-03 |
| `PUT /events/{id}/reminder` | `{option}` | *(미정, 204 또는 200 추정)* | 400 `VALIDATION_FAILED`(확인 필요 4), 404 | R-01, R-07, FR-CAL-05 |
| `DELETE /events/{id}/reminder` | 없음 | 204 | 404 (일정 없음). 알림이 없을 때는 확인 필요 1 | R-01, R-07, FR-CAL-05 |
| `GET /me`, `GET /space`, 기타 | 바뀌지 않음 | — | — | — |

### 4. ERD 테이블과 제약

V1에 모두 있다. **새 마이그레이션은 없을 예정**이다 (V1의 `ck_events_time_columns`, `ix_events_space_start_at`, `ix_events_space_start_date`, `ck_event_reminders_option`, `uq_event_reminders_event_user`, `ix_event_reminders_unsent_fire_at`를 그대로 쓴다).

| 테이블 | 단위 3에서 하는 일 | 사용하는 제약·인덱스 |
| --- | --- | --- |
| events | 생성·조회·수정·삭제. 개인 공간은 `is_together = false`만 (서비스 규칙). `start`/`end` 날짜 범위는 서비스에서 검사 (DB-13). 가져오기 때 `space_id` 이동, 기록 유무 확인 (단위 2 연결점) | `ck_events_time_columns`(종일이면 날짜만, 아니면 일시만, 끝 >= 시작), `ix_events_space_start_at`, `ix_events_space_start_date` (범위 조회: `start <= 범위끝 AND end >= 범위시작`). FK `author_id`·`updated_by` RESTRICT, `space_id` CASCADE |
| event_reminders | 사용자별 알림 만들기·바꾸기·지우기, 일시 변경 재계산, 시간대 변경 재계산, 해제 때 공간 단위 삭제, 탈퇴 때 사용자 단위 삭제 (단위 2 연결점) | `uq_event_reminders_event_user`(일정당 사용자별 하나), `ck_event_reminders_option`(`AT_TIME`, `MIN_10`, `HOUR_1`, `DAY_1`, `ALLDAY_9AM`, `ALLDAY_PREV_9AM`), 부분 `ix_event_reminders_unsent_fire_at`(단위 6 스케줄러). "없음"은 행이 없는 것 |
| notifications | `PARTNER_CREATED` 행 (확인 필요 7). `space_id` = 커플 공간, `target_type` `EVENT`, `target_id` 일정 ID. 일정을 지워도 행은 지우지 않는다 (D-43) | `ck_notifications_type`, `ck_notifications_target_type` |
| users | 요청자의 `time_zone`(날짜 판단, 종일 알림 시각), 알림 문구의 작성자 닉네임, 상대 ID | — (일정 쓰기는 사용자 행을 잠그지 않는다, 아래 5장) |
| spaces, space_members | 활성 공간·종류(개인/커플)·상대 확인 (읽기만) | — |

| ERD 수명주기 | 단위 3 |
| --- | --- |
| 4.1 커플 연결 때 개인 공간 처리 (events 존재 확인) | 일정 몫: `existsInSpace` |
| 4.1 알림이 생길 때 (`PARTNER_CREATED` 행) | 행 생성만 (확인 필요 7). 푸시·SYNC는 단위 6 |
| 4.2 연결 해제 (`event_reminders` 삭제) | 일정 알림 서비스로 옮김 |
| 4.2 가져오기 (`events.space_id` 이동, 4.4 9단계) | 일정 서비스로 옮김 |
| 4.3 탈퇴 4단계 (`event_reminders` 삭제) | 일정 알림 서비스로 옮김 |
| 4.4 `purge_at` 지난 공간 삭제 (events CASCADE) | 단위 6 |
| 4.5 대상 일정 삭제와 알림 행 | 알림 행 유지 (D-43). 테스트로 확인 |

| DB- 결정 | 단위 3에서의 의미 |
| --- | --- |
| DB-05 | 다른 공간·없는 일정은 같은 404 (R-01), 본문까지 같게 |
| DB-06 | 일정 대상은 `is_together` 불리언 하나. 요청 본문에 대상 사용자 필드를 만들지 않는다 (R-10) |
| DB-07 | `PARTNER_CREATED` 행은 푸시 설정과 관계없이 만든다 |
| DB-13 | 날짜 범위는 서비스에서 검사, DB CHECK 없음 |
| DB-03 | 서버 1대. 일정 알림 발송 스케줄러 중복 방지는 단위 6 |

### 5. 동시성, 잠금, 구현 위험

단위 2의 잠금 순서(users UUID 오름차순 → invite_codes → spaces)를 지킨다. 일정 쓰기는 두 사람이 동시에 움직일 수 있지만 사용자·공간 행을 잠그지 않는다.

| 흐름 | 잠금 | 막는 경쟁 |
| --- | --- | --- |
| `PATCH /events/{id}` | 그 일정 행 `FOR UPDATE` → (잠근 뒤 R-02·R-13 다시 확인) → 그 일정의 `event_reminders` 재계산 | 함께 일정의 동시 수정(마지막 저장이 이김, NFR-SYNC-03), 수정과 `PUT reminder`가 겹쳐 새 일시와 옛 일시 기준 알림이 섞이는 것 |
| `PUT /events/{id}/reminder` | 그 일정 행 `FOR SHARE` 이상 → `INSERT … ON CONFLICT (event_id, user_id) DO UPDATE` | 같은 사용자의 겹친 설정(`uq_event_reminders_event_user` 위반이 500이 되지 않게), 수정과 겹침 |
| `DELETE /events/{id}` | 일정 행 `FOR UPDATE` | 삭제와 수정·알림 설정이 겹침 (CASCADE가 알림을 지움) |
| `POST /events` + `PARTNER_CREATED` 행 | 공간 행을 잠그지 않는다 | **공간 행을 잠근 채 알림 행을 넣으면 안 된다.** 알림 INSERT의 외래 키 검사가 상대 users 행에 `FOR KEY SHARE`를 요구하고, 상대의 탈퇴가 그 행을 `FOR UPDATE`로 잡은 채 공간 행을 기다리면 교착한다 (단위 2 2부 결정, ARCHITECTURE 4장) |

그 밖의 위험:

1. **해제와 `PUT reminder`의 경쟁.** 해제는 공간 행을 `UNLINKED`로 바꾸며 그 공간 `event_reminders`를 지운다. 일정 쪽은 공간 행을 잠그지 않으므로 해제가 커밋된 직후 R-01 검사를 통과한 `PUT reminder`가 해제된 공간의 일정에 알림 행을 다시 만들 수 있다. 단위 3은 R-01의 활성 공간 검사를 같은 트랜잭션 안에서 하고, 단위 6 스케줄러는 `spaces.status = ACTIVE`인 일정만 보내도록 남은 일에 적는다.
2. **시간대·날짜 변환 경계.** 시간 일정의 날짜 범위(R-15)와 조회 경계, 종일 알림 시각은 모두 `users.time_zone`으로 계산한다. 서머타임이 있는 시간대에서 "1일 전"(`DAY_1`)은 24시간 전으로 본다(추천, 확인 필요 5). `Clock` 빈으로 고정한 시각으로 테스트한다 (ARCHITECTURE 6장).
3. **범위 조회 쿼리.** 종일(`start_date`/`end_date`)과 시간(`start_at`/`end_at`) 일정이 서로 다른 컬럼·인덱스라 두 조건을 합쳐야 한다. 요청자 시간대의 `from` 0시 ~ `to` 다음 날 0시를 UTC 일시로 바꿔 시간 일정에 쓰고, 종일 일정에는 날짜 그대로 쓴다. 3개월 범위라 페이지는 없지만 한 달 일정이 많아도 메모리가 작은 서버(ARCHITECTURE 7장)에서 문제없도록 필요한 컬럼만 읽는다.
4. **JPA 쓰기 순서.** 재계산은 같은 트랜잭션의 일괄 UPDATE(`event_reminders.fire_at`)로 하고, 영속성 컨텍스트의 엔티티를 그 뒤에 쓰지 않는다 (단위 2 `clearAutomatically` 규칙). 일정 행 수정과 알림 재계산 순서에는 UNIQUE 충돌이 없다.
5. **의존 방향.** `couple` → `event`(서비스만), `user.WithdrawalDataEraser` → `event`(알림 서비스만), `event` → `space`·`user`·`notification`(서비스만). `event`가 `couple`을 알면 안 된다. 같은 방식으로 단위 2가 `user` → `couple`·`notification` 의존을 만들었다.

## 단위 6과의 경계 (지금 할 것과 미룰 것)

API 7장: "모든 알림 작업은 받는 사람마다 `notifications` 행을 먼저 만들고 푸시는 조건이 맞을 때만". 단위 2 선례: 행 생성과 커밋 뒤 도메인 이벤트 발행까지가 도메인 단위, 푸시·SYNC 발송은 단위 6 (unit-2.md "단위 6과의 경계").

| 일 | 단위 | 근거 |
| --- | --- | --- |
| `event_reminders` 만들기·바꾸기·지우기·재계산 | **3** | API 3.3, ERD 3.3, FR-CAL-05 |
| 1분마다 `fire_at <= now`이고 `sent_at` 없는 알림을 `EVENT_REMINDER` 행으로 만들고 `sent_at` 기록, 푸시 | 6 | API 7장 표, PLAN 단위 6 "스케줄러(일정·기념일 알림)" |
| `PARTNER_CREATED` 알림 행 (일정 생성 때, 상대) | **확인 필요 7** | API 7장 "상대 활동 알림 즉시", FR-NOTI-04, PLAN 단위 3 행에는 FR-NOTI가 없음 |
| 일정 `EventCreated`/`EventUpdated`/`EventDeleted` 도메인 이벤트 발행 (커밋 뒤 전달) | **3** (확인 필요 8) | ARCHITECTURE 4장. 단위 2의 `SpaceUpdated`와 같은 방식 |
| SYNC `EVENT`/`CREATED`·`UPDATED`·`DELETED` (공간의 다른 구성원), `NOTIFICATION` (행 생긴 본인), 푸시 | 6 | API 6장, 7장, R-16 |
| `GET /notifications`, `/notifications/unread-count`, 읽음 처리 | 6 | API 3.5 |
| 일정 삭제 때 알림 행 유지 | **3** (삭제하지 않음) | D-43 |

## 결정

구현하면서 정한 것 중 문서에 없는 것. 문서에 있어야 할 결정이면 여기 적지 말고 작업을 멈추고 보고한다.

결정 후보 (범위 승인 때 함께 확인. 확인 필요가 Docs로 해결되면 Docs가 맞다):

- 패키지: `event/api`(컨트롤러, 요청·응답 DTO), `event/application`(`EventService`, `EventReminderService`, `EventReminderCalculator`, 도메인 이벤트), `event/domain`(`Event`, `EventReminder`, 리포지토리, `ReminderOption`).
- `updated_by`: 생성 때는 null, 수정할 때 수정자로 기록한다 (ERD "마지막 수정자").
- `PUT reminder`는 멱등 upsert, `DELETE reminder`는 알림이 없어도 204 (확인 필요 1의 추천).
- 일정 변경 도메인 이벤트는 트랜잭션 안에서 발행하고 단위 3에는 리스너가 없다 (단위 6). `PARTNER_CREATED` 알림 ID는 이벤트에 담는다 (단위 2 `CoupleLinked.notificationIds`와 같다).
- `SpaceAccessPolicy`에 일정용 메서드를 더하되, 일정 엔티티를 몰라서 값(`authorId`, `isTogether`, 일정의 `spaceId`)만 받는다. R-01은 기존 `requireInActiveSpace`를 쓴다.
- 요청 본문은 단위 2와 같이 `@RequestBody Map`으로 받아 "필드 없음"과 `null`을 구분하고, R-01·R-02·R-13·R-10(403·404·400 INVALID_TARGET)이 값 검사(400)보다 먼저 나가게 한다 (확인 필요 2·9의 추천).
- `GET /events` 정렬은 `(시작 일시 또는 날짜, 종일 먼저, id)` 오름차순이다 (확인 필요 3).
- 테스트 픽스처: `IntegrationTestSupport`가 이미 `events`, `event_reminders`를 INSERT하는 헬퍼를 갖고 있다 (365행, 400행 근처). 일정 API 테스트는 이 헬퍼를 넓혀 쓴다.

## 리뷰 기록

| 회차 | Critical | Major | 비고 |
| --- | --- | --- | --- |

## 남은 일

- 단위 4·5: `CoupleContentGateway`에 남은 `anniversaries`·`memos` 네이티브 SQL을 각 도메인 서비스로 옮기고 이 클래스를 없앤다. `WithdrawalDataEraser`의 `memo_favorites` 삭제는 단위 5
- 단위 6: 1분 일정 알림 스케줄러(`EVENT_REMINDER` 행, `sent_at`, 푸시, `spaces.status = ACTIVE` 일정만), 일정 도메인 이벤트를 듣는 SYNC `EVENT`, `PARTNER_CREATED` 푸시(확인 필요 7의 결정에 따라 행 생성도)
- Android: 일정 응답 모양·오류 코드·알림 option 값은 확인 필요 1·4의 결정을 따른다 (Docs가 정한 뒤)

## 확인 필요

문서끼리 다르거나 비어 있는 곳. 추측하지 않고 선택지를 적는다. Docs를 고쳐야 하는 항목은 Docs 레포 `/prd-sync`로. 행 번호는 `/home/claude/docs-main`(커밋 `afc3d1b`) 기준이다.

**구현 전에 정해야 하는 것 (1~6)**

1. **일정 API의 응답 모양과 상태가 없다.** `API.md` 7행은 3.1·3.2 밖의 상세를 OpenAPI가 정한다고 하고, 3.3(233~239행)은 `POST /events` 요청 필드 이름만 있다. 비어 있는 것: `Event` 응답 필드(`id`, `title`, `note`, `isAllDay`, `start`, `end`, `isTogether`, `authorId`, 요청자의 알림, 시각), `start`·`end`의 형식(1장 17행은 "날짜 `YYYY-MM-DD`, 일시 ISO 8601 UTC"만 말해 종일이면 날짜, 아니면 일시로 읽히지만 명시가 없다), `POST /events` 201(1.1 41행 "생성은 201")·`PATCH` 200·`DELETE` 204는 1.1로 읽히지만 `PUT /events/{id}/reminder`의 성공 상태와 본문은 없다, `DELETE /events/{id}/reminder`가 알림이 없을 때 어떻게 되는지, 응답 안 "요청자의 알림"(R-07 89행, 233행)의 모양. 단위 2 때 같은 문제를 Docs v0.8이 3.2에 표로 정했다.
   - A) `/prd-sync`로 API.md 3.3에 3.1·3.2처럼 적는다 (추천). 제안: `Event` = `{id, title, note, isAllDay, start, end, isTogether, authorId, updatedBy, reminder: {option, fireAt} \| null, createdAt, updatedAt}`, `start`·`end`는 종일이면 `YYYY-MM-DD`, 시간 일정이면 UTC 일시. `GET /events` 200 `{items: [Event]}`, `POST` 201 `Event`, `GET`·`PATCH` 200 `Event`, `DELETE` 204, `PUT reminder` 200 `Event`(앱이 다시 그릴 수 있게) 또는 204, `DELETE reminder` 알림이 없어도 204(멱등)
   - B) 서버가 정해 OpenAPI와 이 기록의 "결정"에만 적는다
2. **일정 쓰기 요청의 필드 규칙이 없다.** `POST`·`PATCH` 모두(API.md 234, 236행, 5장 411행 "종료가 시작보다 이름"). 정해지지 않은 것: (a) `PATCH`가 부분 수정인지(없는 필드는 그대로)와 `note: null`이 메모를 지우는지, (b) 필수 필드(`title`, `isAllDay`, `start`, `end`)와 기본값(`isTogether` false는 ERD 247행에 있고 `notifyPartner` true는 API.md 234행에 있음, `note` 생략은 null?), (c) 제목이 공백만 있을 때(닉네임은 API.md 123행 `PATCH /me`에서 앞뒤 공백을 지운 뒤 빈 값이 REQUIRED인데 제목은 그런 규칙이 없음), 앞뒤 공백 처리, (d) `isAllDay`가 `true`인데 시간이 있는 값을 보내거나 그 반대(`INVALID`?), `PATCH`로 종일 ↔ 시간을 바꿀 때 `start`·`end`를 같이 보내야 하는지, (e) 본문에 대상 사용자 필드(`targetUserId` 등)나 모르는 필드를 보내면 무시인지 400인지(R-10 92행 "대상 사용자는 받지 않는다"), (f) `end` 오류의 `field` 이름(`end`), 범위 밖 `DATE_OUT_OF_RANGE`의 `field`(`start`·`end` 각각, 둘 다 틀리면 둘 다 담는지).
   - A) 부분 수정, `note`만 `null`로 지움, `title`·`isAllDay`·`start`·`end`는 POST 필수(PATCH는 있으면 검사), 제목은 앞뒤 공백을 지운 뒤 빈 값이면 `REQUIRED`(NFR-LIMIT-01 길이는 지운 뒤 센다), 종일/시간 불일치는 400 `VALIDATION_FAILED`(`start` 또는 `end`, `INVALID`), 종일↔시간 전환은 `isAllDay`와 `start`·`end`를 함께 보내야 한다, 모르는 필드는 무시(대상 사용자는 받지 않으므로 효과 없음), 오류는 틀린 필드를 모두 `details.fields`에 담는다. 이 내용을 API.md 3.3과 5장에 적는다 (추천)
   - B) 서버가 정해 OpenAPI와 "결정"에만 적는다
3. **`GET /events` 쿼리 규칙.** `API.md` 233행은 "날짜 범위의 일정 (최대 3개월)"만 말한다. (a) `from`·`to`는 날짜(`YYYY-MM-DD`)인지, `to`를 포함하는지, 없거나 형식이 틀리면(`REQUIRED`/`INVALID`) 어떻게 하는지, (b) "3개월"의 정의(`to`가 `from` + 3개월 이내? 일수?), (c) **5장 411행 `VALIDATION_FAILED` 항목은 `GET /memos/dated`와 `GET /anniversaries`의 범위 오류만 적고 `GET /events`가 빠져 있다.** R-15(97행)와 1장 18행은 `GET /events`도 "from > to와 3개월 초과 검사"를 한다고 읽힌다, (d) 시간 일정의 경계를 어느 시간대의 날짜로 자르는지(R-15는 쓰기에서 요청자 `time_zone`이라고만 말함), (e) 결과 정렬.
   - A) `/prd-sync`로 5장에 `GET /events`를 더하고 3.3에 적는다 (추천). 제안: `from`·`to` 모두 필수 `YYYY-MM-DD`, `to` 포함, 3개월 초과는 `to > from.plusMonths(3)`, 오류는 `VALIDATION_FAILED`(`from`/`to`, `REQUIRED`·`INVALID`, 순서·길이는 `to`, `INVALID`), 날짜 경계는 요청자 `users.time_zone`, 정렬은 시작 순(종일 먼저)
   - B) 서버가 `GET /memos/dated`와 같은 규칙으로 정하고 OpenAPI와 "결정"에만 적는다
4. **알림 `option` 값과 일정 종류의 짝.** `API.md` 238행은 `{option}`만, ERD 269행은 CHECK 값 6개(`AT_TIME`, `MIN_10`, `HOUR_1`, `DAY_1`, `ALLDAY_9AM`, `ALLDAY_PREV_9AM`), PRD FR-CAL-05 AC1·AC3(324~326행)과 SCREENS 101행은 시간 일정 4종·종일 일정 2종을 말한다. 비어 있는 것: 요청 `option`이 ERD 값 그대로(문자열)인지, 일정 종류와 안 맞는 값(종일 일정에 `MIN_10` 등)이나 없는·모르는 값의 오류 코드, "없음"을 `PUT`으로 보낼 수 있는지(아니면 `DELETE`뿐).
   - A) 요청 `option`은 ERD 값 6개 문자열 그대로. 일정 종류와 안 맞거나 모르는 값은 400 `VALIDATION_FAILED`(`option`, `INVALID`), 없으면 `REQUIRED`. "없음"은 `DELETE`뿐이고 `NONE` 같은 값은 없다. API.md 3.3에 적는다 (추천)
   - B) 일정 종류와 안 맞는 값은 가장 가까운 값으로 바꿔 저장 (예: 종일 일정의 `MIN_10` → `ALLDAY_9AM`). 문서에 근거가 없어 비추천
5. **`fire_at` 계산식과 이미 지난 시각.** ERD 270행은 "보낼 시각"이라고만 하고 계산이 없다. 정해지지 않은 것: `AT_TIME` = 시작 시각, `MIN_10` = 10분 전, `HOUR_1` = 1시간 전, `DAY_1` = 24시간 전(서머타임 날은 로컬 하루와 달라진다), `ALLDAY_9AM` = 시작 날짜 오전 9시, `ALLDAY_PREV_9AM` = 시작 날짜 전날 오전 9시 — 후자 둘의 오전 9시가 **알림을 받는 사람 각자의 `users.time_zone`** 기준인지(API.md 7장 기념일 알림이 "각 사용자의 시간대로 오전 9시"라고 한 것과 같게 읽힌다). 또 계산한 `fire_at`이 이미 지났을 때(일정이 5분 뒤인데 "1시간 전"을 고름, 일시 변경 재계산 결과가 과거) 문서에 규칙이 없다.
   - A) 계산식은 위 추천대로 확정하고(`DAY_1`은 시작 시각에서 24시간을 뺌), 이미 지난 `fire_at`은 행을 저장하되 `sent_at = 지금`으로 기록해 보내지 않는다. 푸시 본문이 "1시간 뒤 · …"처럼 거짓이 되지 않고, 일시 변경 재계산에도 같은 규칙을 쓴다 (추천)
   - B) 이미 지난 시각은 `PUT reminder`에서 400 `VALIDATION_FAILED`(`option`, `INVALID`)로 거부. 일시 변경 재계산에서는 과거가 되면 알림 행을 지운다
   - C) 그대로 저장해 다음 1분 스케줄러가 바로 보낸다
6. **일시·종류·시간대가 바뀔 때 `event_reminders`의 나머지 처리.** `API.md` 236행과 FR-CAL-05 AC4는 "일시를 바꾸면 알림 건 모든 사용자의 `fire_at`을 다시 계산"까지만 말한다. 비어 있는 것: (a) 이미 `sent_at`이 있는 알림은 새 일시에서 다시 보내는지(`sent_at` 초기화), (b) `PATCH`로 종일 ↔ 시간을 바꾸면 기존 `option`이 새 종류에 안 맞는데(예: `MIN_10`인데 종일로 바꿈) 어떻게 하는지, (c) 사용자 시간대가 바뀔 때(`UserTimeZoneChanged`, ERD 270행 "사용자 시간대가 바뀌면 다시 계산") 어떤 알림을 다시 계산하는지. 시간 일정 옵션은 절대 시각이라 영향이 없고 종일 옵션만 영향을 받는다.
   - A) (a) 일시가 바뀌면 `sent_at`을 비워 새 `fire_at`이 미래일 때 다시 보낸다. 일시가 안 바뀌면 건드리지 않는다. (b) 안 맞게 된 `option` 행은 삭제하고 앱이 다시 고르게 한다(응답의 `reminder`가 null이 됨). (c) 시간대가 바뀌면 그 사용자의 종일 옵션 중 `sent_at`이 없는 행만 새 시간대로 다시 계산한다 (추천)
   - B) (a) `sent_at`은 건드리지 않는다(한 번 보낸 알림은 다시 보내지 않음). (b) 가장 가까운 옵션으로 바꾼다. (c) 모든 종일 옵션을 다시 계산한다

**단위 6과의 경계 (7~8)**

7. **`PARTNER_CREATED` 알림 행을 어느 단위가 만드나.** `docs/PLAN.md` 13행(단위 3)은 "일정, 일정 알림, 날짜 범위 / FR-CAL, API 3.3"이고 FR-NOTI를 적지 않았다. 16행(단위 6)은 "알림 목록, 알림 설정, 푸시(FCM)·동기화 신호, 스케줄러, 정리 작업"이다. 그런데 `API.md` 234행 `POST /events`의 FR 칸에는 FR-NOTI-04가 있고, 7장 467행은 일정을 만들 때 "즉시" 상대의 `notifications` 행을 만든다고 한다. `notifyPartner`는 일정 생성 요청에서만 받으므로 생성 트랜잭션에서 행을 만들지 않으면 값이 사라진다. 또 문구 "[날짜] · [일정 제목]"(PRD 489행 예시 "10월 17일 (토) · 1박 2일 여행")의 서식과 시간대가 정해지지 않았다(여러 날 일정은 시작일? 받는 사람의 시간대? 연도 표시는?).
   - A) 단위 3이 `NotificationService`에 `createPartnerCreated`를 더해 행을 만들고 `EventCreated` 이벤트에 알림 ID를 담는다. 푸시·SYNC·설정 확인은 단위 6 (단위 2의 `COUPLE_LINKED` 선례, 추천). 서식은 "M월 d일 (E)" 한국어, 시작 날짜, 받는 사람의 `users.time_zone`, 연도는 쓰지 않는다 (PRD 예시와 같게). `/prd-sync`로 PRD 8장에 서식 한 줄을 적는다
   - B) 행 생성도 단위 6 (`EventCreated`에 `notifyPartner`만 담음). 단위 3은 값을 이벤트로만 전달. 단위 3이 AC 테스트를 덜 하지만 단위 6이 생성 시점 정보(닉네임, 시작 날짜)를 이벤트에서 다시 읽어야 한다
8. **일정 SYNC 신호의 연결점.** `API.md` 6장은 일정이 바뀌면 공간의 다른 구성원에게 SYNC `EVENT`(`CREATED`·`UPDATED`·`DELETED`, `id`는 일정 ID)를 보낸다고 한다. FCM 발송기는 아직 없다(단위 6). 일정 알림(`event_reminders`) 설정 변경은 개인 값이라 신호가 필요 없다고 읽히지만 6장이 따로 말하지 않는다.
   - A) 단위 3은 트랜잭션 안에서 `EventCreated`·`EventUpdated`·`EventDeleted`를 발행하는 데까지(커밋 뒤 전달 테스트 포함). 발송은 단위 6. `PUT`·`DELETE reminder`는 신호도 이벤트도 없다 (추천, 단위 2의 `SpaceUpdated`와 같음)
   - B) 단위 3에서 FCM 발송기를 만든다 (단위 6 범위가 단위 3으로 당겨짐)

**문서 표기 (9~13, 구현은 추천대로 진행 가능, 승인만 필요)**

9. **R-01 표기.** `API.md` 236~237행 `PATCH`·`DELETE`의 권한 칸은 R-02뿐이고 R-01이 없다(`GET`·`PUT reminder`에는 있음). 2장 79행 머리말은 "모든 요청은 먼저 활성 공간 검사 → 404"라고 한다. 검사 순서를 문서가 말하지 않는다.
   - A) 머리말대로 R-01(404) → R-02(403 `NOT_AUTHOR`) → R-13(403 `NOT_AUTHOR`) → R-10(400 `INVALID_TARGET`) → 값 검사(400). 단위 2의 R-08 → 400 순서와 같다. 3.3 권한 칸에 R-01을 더한다 (추천)
   - B) 값 검사(400)를 먼저 한다 (권한 없는 사용자에게도 필드 오류가 나가서 비추천)
10. **R-13 판정 기준.** R-13(95행)은 "`isTogether` 값을 바꾸는 요청"이라 한다. 요청에 `isTogether`가 현재 저장값과 같은 값으로 들어오면 "바꾸지 않는 요청"(R-02만)으로 읽는다. 이 해석을 문서에 적어 두는 것이 좋다.
    - A) 현재 저장값과 비교한다. 같으면 R-02만, 다르면 작성자만 (추천, R-13 본문 마지막 문장과 같음)
    - B) 요청에 `isTogether` 필드가 있기만 하면 작성자만. '함께' 일정을 앱이 항상 `isTogether`를 같이 보내면 상대의 수정이 모두 막히므로 비추천
11. **`DELETE /events/{id}/reminder`의 멱등.** (1번과 겹침) 알림이 없을 때 204(멱등) 또는 404. 5장 `NOT_FOUND` 정의는 "없거나 다른 공간의 리소스"라서 일정이 있고 알림만 없으면 404가 맞는지 불분명하다.
    - A) 204 (끄기 의도는 이미 이뤄짐, 추천) B) 404 `NOT_FOUND`
12. **`PUT reminder`의 대상 일정이 이미 지난 일정일 때.** 일정이 이미 끝난 뒤에도 알림을 설정할 수 있는지 문서가 말하지 않는다. 5번 A(지난 시각은 보내지 않음)와 맞물린다.
    - A) 허용하고 5번 A로 처리 (추천) B) 일정이 끝난(종료가 지금보다 이른) 일정이면 400 `VALIDATION_FAILED`
13. **`updated_by`와 응답의 작성자 닉네임.** ERD 253행 `updated_by`는 null 허용인데 생성 때 값을 정하지 않았다. FR-CAL-03 AC3은 작성자 닉네임 표시를 말하지만 `Event`에 닉네임을 넣을지는 3.2 `Space.members`로 찾는다고 읽힌다(탈퇴한 작성자는 `GET /space`에 없음 → 앱이 "탈퇴한 사용자"로 보여야 하는지).
    - A) `updated_by`는 생성 때 null, 수정 때 기록. `Event`에는 `authorId`만 넣고 닉네임은 앱이 `GET /space`로 찾는다. 작성자가 구성원에 없으면(상대가 탈퇴해 해제된 뒤에는 그 공간이 활성 공간이 아니므로 사실상 생기지 않음) 앱이 처리 (추천)
    - B) `Event`에 `authorNickname`도 넣는다 (익명화된 "탈퇴한 사용자"도 그대로). Docs에 필드를 더해야 한다
