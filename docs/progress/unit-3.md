# 단위 3: 캘린더 — 일정, 일정 알림, 날짜 범위 (event)

> 브랜치: `feat/3-calendar` · PR: https://github.com/DuriseoApp/Server/pull/8 · 상태: **구현 1부·2부 완료, 리뷰 2회차 머지 가능** (CI 571개 통과, 두 번. 2회차 Minor 정리는 CI 확인 대기: 이 환경은 Gradle 플러그인·Docker에 닿지 않는다)
> 기준: PRD v0.9 (D-89~D-103 포함. 처음 범위를 잡을 때는 v0.8이었다. 처음 범위 목록: FR-CAL-01 AC2·AC6, FR-CAL-02, FR-CAL-03, FR-CAL-05, FR-NOTI-04 일부, NFR-SEC-02, NFR-TIME-01, NFR-LIMIT-01, NFR-SYNC-01·03, D-10, D-12, D-13, D-16, D-34, D-35, D-52, D-59, D-64, D-74, D-77), API.md 1장·1.1·2장(R-01·R-02·R-07·R-10·R-13·R-15)·3.3(일정 6개 경로)·3.5·5장·6장·7장, ERD 3.3(events·event_reminders)·4.1·4.2·4.3·4.5·5장(DB-05·06·07·13)
> Docs 기준 커밋: DuriseoApp/Docs main `728ea08` (PRD v0.9, Docs PR #10 머지). 처음 범위를 잡을 때는 `afc3d1b`(PRD v0.8)였다. 읽은 경로는 `/home/claude/docs-main` (`../Docs` 대신)
> 앞 단위: 단위 2 머지됨 (main `7016114`)

## 확인 필요 해결 (PRD v0.9, Docs PR #10)

아래 "확인 필요" 1~13은 모두 Docs v0.9(PRD D-89~D-103, API.md 3.3, ERD 3.2)에 반영돼 해결됐다. **이 절과 Docs가 다르면 Docs가 맞다.** 범위 표의 `<확인 필요 N>`은 이 절의 결정으로 채웠다. 일정 알림(4~6, 11, 12번)과 `PARTNER_CREATED`(7번), SYNC 도메인 이벤트(8번)는 2부에서 구현했다. 처음 추천안과 달라진 곳은 굵게 적는다.

1. 응답 모양·상태: API.md 3.3 `Event`·`EventReminder`. `GET /events` 200 `{items}`, `POST` 201, `GET`·`PATCH` 200 `Event`, `DELETE` 204, `PUT reminder` 200 `Event`, `DELETE reminder` 204. `Event` = `id`, `title`, `note`, `isAllDay`, `start`, `end`, `isTogether`, `authorId`, `updatedBy`, `reminder`(`{option, fireAt}` 또는 null, 요청자 것만), `createdAt`, `updatedAt`. `start`·`end`는 종일이면 `YYYY-MM-DD`, 아니면 UTC 일시. `sent_at`은 내보내지 않는다 (추천과 같음)
2. 쓰기 규칙: API.md 3.3 "쓰기 규칙". `POST` 필수 `title`·`isAllDay`·`start`·`end`, `isTogether` 생략 false, `notifyPartner` 생략 true, `note` 생략·null은 null. `PATCH`는 부분 수정이고 `note: null`만 메모를 지운다(그 밖의 null은 `REQUIRED`). 제목은 앞뒤 공백을 지운 뒤 빈 값이면 `REQUIRED`, 코드 포인트 50개 초과 `TOO_LONG`(D-89). 종일/시간 짝이 안 맞으면 `start`/`end` `INVALID`, `PATCH`에서 `isAllDay`를 바꾸면 `start`·`end`를 함께 보낸다. 모르는 필드와 대상 사용자 필드는 무시. 틀린 필드를 모두 담는다
3. `GET /events` 쿼리: API.md 3.3 "조회 규칙". `from`·`to` 필수 `YYYY-MM-DD`, `to` 포함. 없으면 `REQUIRED`, 형식이 아니면 `INVALID`, `from` > `to`이거나 `to` > `from.plusMonths(3)`이면 (`to`, `INVALID`). 이 검사는 받은 값 그대로 먼저 하고 그 뒤 1950-01-01 ~ 2050-12-31로 잘라낸다. 시간 일정은 요청자 시간대 `from` 00:00 ~ `to` 다음 날 00:00으로 본다. 정렬은 시작 날짜 → 종일 먼저 → `start_at` → `id`
4. 알림 `option`: ERD 값 6개 그대로, 종류와 안 맞거나 모르는 값은 `option` `INVALID`, 없으면 `REQUIRED`, "없음"은 `DELETE`뿐
5. `fire_at`: D-90. `DAY_1`은 24시간 전, 종일 알림은 **알림 주인**의 `users.time_zone` 09:00. 계산한 시각이 지났으면 행은 저장하고 `sent_at` = 지금
6. 다시 계산: D-91. **`isAllDay`가 바뀌면 그 일정의 알림을 모든 사용자 것 삭제**(처음 추천은 안 맞는 행만 삭제), 종류가 같고 `start`가 바뀌면 모든 행의 `fire_at`·`sent_at`을 다시 정함, `end`·제목·메모·대상만 바뀌면 그대로. 시간대가 바뀌면 그 사용자의 종일 옵션 중 `sent_at IS NULL`인 행만
7. `PARTNER_CREATED` 행: D-92. 단위 3이 행을 만든다. 제목 "[작성자 닉네임]님이 일정을 추가했어요", 본문 "[날짜] · [일정 제목]", 날짜는 "M월 d일 (E)" 시작 날짜, 시간 일정은 받는 사람의 시간대. 개인 공간이거나 `notifyPartner = false`면 만들지 않는다
8. SYNC: 일정 알림 `PUT`·`DELETE`는 신호가 없고 `PATCH`의 알림 재계산도 신호는 `EVENT`/`UPDATED` 하나. 단위 3은 도메인 이벤트 발행까지, 발송은 단위 6 (리드 결정)
9. 검사 순서: `PATCH` R-01 → R-02 → R-13 → R-10 → 값 검사 → 날짜 범위, `DELETE` R-01 → R-02, `POST` R-10 → 값 검사 → 날짜 범위, `PUT`·`DELETE reminder` R-01 → 값 검사. `isTogether`가 null이거나 boolean이 아니면 R-13·R-10은 건너뛰고 값 검사가 응답한다. 3.3 권한 칸에 R-01이 더해졌다
10. R-13: 요청의 `isTogether`를 **저장된 값과 비교**해 바꾸는 요청인지 정한다 (추천 A)
11. `DELETE reminder`: 알림이 없어도 204
12. 끝난 일정에 `PUT reminder`: 허용, 지난 시각은 5번 규칙
13. `updated_by`: 생성 때 null, 수정할 때 수정자. `Event`에는 `authorId`만, 닉네임은 응답에 없다 (추천 A)

v0.9가 단위 3에 더한 것:

- **`spaces.linked_at`** (ERD 3.2, D-94): 새 마이그레이션 `V2`. 커플 연결·복구 때 `linked_at` = 지금 (API.md 4.4 4단계, ERD 4.1·4.2). 처음 범위의 "새 마이그레이션 없음"은 이 때문에 틀리게 됐다
- **`MeSpace.linkedAt`·`importCounts`, `Space.linkedAt`** (API.md 3.1·3.2, D-93): 기념일은 직접 기념일만 센다. `importCounts`는 `importPending`이 false면 null이고 요청 때 센다

## 요약

- 새 API 경로 **7개** (API.md 3.3 표의 일정 행 7개): `GET /events`, `POST /events`, `GET /events/{id}`, `PATCH /events/{id}`, `DELETE /events/{id}`, `PUT /events/{id}/reminder`, `DELETE /events/{id}/reminder`. 같은 표의 `/anniversaries` 계열은 단위 4
- FR **5개**를 다룬다: FR-CAL-01(AC2 서버 몫, AC6), FR-CAL-02, FR-CAL-03, FR-CAL-05, FR-NOTI-04(행 생성만). FR-CAL-04는 단위 2에서 끝났다.
- R- 규칙 **6개**: R-01(일정 경로 적용), R-02, R-07, R-10, R-13, R-15(일정 쓰기·`GET /events` 잘라내기).
- 새 `ErrorCode` 없음 (`NOT_AUTHOR`, `INVALID_TARGET`, `DATE_OUT_OF_RANGE`, `VALIDATION_FAILED`, `NOT_FOUND`가 이미 있다). **새 마이그레이션은 `V2__add_spaces_linked_at.sql` 하나** (v0.9가 더한 `spaces.linked_at`. 일정 테이블은 V1에 `events`, `event_reminders`, 제약·인덱스 모두 있다).
- 단위 2에서 넘어온 일: `CoupleContentGateway`의 events·`event_reminders` 네이티브 SQL 3곳과 `WithdrawalDataEraser`의 `event_reminders` 삭제를 일정 도메인 서비스로 옮기고, 단위 1이 남긴 `UserTimeZoneChanged`를 듣는 리스너를 만든다.
- 처음 범위를 잡을 때 있던 **확인 필요 13건은 모두 Docs v0.9로 해결됐다** (위 절).

## 범위

### 1. 기능 (FR)과 수용 기준

AC 하나에 테스트 하나 이상 (CONVENTIONS). "앱 동작"은 서버 API가 없어 서버 테스트가 없다 (단위 1 결정). JVM 메서드 이름에 `/`를 못 쓰므로 경로는 `POST events`처럼 적는다 (단위 1 결정). `<확인 필요 N>`은 위 "확인 필요 해결" 절로 채웠다. 표의 테스트 이름은 처음 계획이고, 1부에서 만든 실제 이름은 "구현 1부"를 본다. 테스트 클래스는 `event/` 아래 `EventQueryTest`, `EventWriteTest`, `EventPermissionTest`, `EventReminderTest`, `EventDateRangeTest`로 나눈다 (예정).

**FR-CAL-01 캘린더 홈** (D-30, D-74) — 서버 몫은 AC2의 일정 조회와 AC6

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-CAL-01 AC1 | 오늘이 속한 달로 시작 | 앱 동작 |
| FR-CAL-01 AC2 | 일정이 있는 날 표시, 날짜를 누르면 그 날 목록. 서버는 날짜 범위의 일정을 준다 (기념일·메모는 단위 4·5, 정렬 표시는 앱) | `FR-CAL-01 AC2 GET events는 범위 안에 걸치는 일정만 돌려주고 범위 밖 일정은 빼고 돌려준다` |
| | | `FR-CAL-01 AC2 GET events는 종일 일정과 시간 일정을 함께 돌려준다` |
| | | `FR-CAL-01 AC2 GET events는 요청자의 공간 일정만 돌려준다` (다른 공간·해제된 이전 공간 일정은 없음. R-01) |
| | | `FR-CAL-01 AC2 시간 일정의 범위 경계는 요청자 시간대 기준 날짜로 판단한다` (UTC 15:30 = 서울 다음 날 0:30 일정이 서울 사용자의 다음 날 조회에 걸림) |
| | | `FR-CAL-01 AC2 from이 to보다 뒤면 400 VALIDATION_FAILED이고 3개월을 넘어도 400이다` (`to` `INVALID`, 3개월은 `from.plusMonths(3)`) |
| | | `FR-CAL-01 AC2 from이나 to가 없거나 날짜 형식이 아니면 400 VALIDATION_FAILED` (`REQUIRED`/`INVALID`) |
| FR-CAL-01 AC3 | D+N (기념일) | 단위 4 |
| FR-CAL-01 AC4 | 알림 버튼 점 | 단위 6 (`GET /notifications/unread-count`) |
| FR-CAL-01 AC5 | 개인 공간 앱바 | 앱 동작 |
| FR-CAL-01 AC6 | 1950-01-01 ~ 2050-12-31. 쓰기는 400 `DATE_OUT_OF_RANGE`, 범위 조회는 잘라서 조회 (D-74). 일정 몫만. 메모 날짜·기념일은 단위 5·4 | 아래 R-15 테스트 |

**FR-CAL-02 일정 작성** (D-10, D-13, D-34, D-59, D-64) — `POST /events`

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-CAL-02 AC1 | 제목·시작·종료로 저장하면 현재 공간에 일정이 생긴다. 제목 필수 | `FR-CAL-02 AC1 제목과 시작·종료를 보내면 요청자의 활성 공간에 일정이 만들어진다` (201, `author_id`, `space_id`, `is_together` false, `updated_by` null) |
| | | `FR-CAL-02 AC1 제목이 없거나 빈 값이면 400 VALIDATION_FAILED (title REQUIRED)` (공백만 있는 값도 `REQUIRED`, 앞뒤 공백은 지운 값으로 저장, D-89) |
| | | `FR-CAL-02 AC1 제목이 50자를 넘으면 400 VALIDATION_FAILED (title TOO_LONG)이고 코드 포인트로 센다` (이모지 포함, NFR-LIMIT-01, D-82) |
| | | `FR-CAL-02 AC1 일정 메모가 1000자를 넘으면 400 VALIDATION_FAILED (note TOO_LONG)` |
| | | `FR-CAL-02 AC1 여러 날 일정은 종료 날짜가 시작보다 뒤인 일정으로 저장된다` (D-64) |
| FR-CAL-02 AC2 | 종일이면 시간 없이 시작일·종료일만 저장 | `FR-CAL-02 AC2 종일 일정은 start_date·end_date만 저장하고 start_at·end_at은 null이다` (`start`/`end`는 종일이면 `YYYY-MM-DD`, 아니면 UTC 일시) |
| | | `FR-CAL-02 AC2 종일 일정에 시간이 있는 값을 보내면 400 VALIDATION_FAILED` (`start`/`end` `INVALID`) |
| FR-CAL-02 AC3 | 종료가 시작보다 이르면 저장되지 않는다 | `FR-CAL-02 AC3 종료가 시작보다 이른 시간 일정은 400 VALIDATION_FAILED (end INVALID)이고 저장되지 않는다` |
| | | `FR-CAL-02 AC3 종료 날짜가 시작 날짜보다 이른 종일 일정도 400 VALIDATION_FAILED (end INVALID)` |
| | | `FR-CAL-02 AC3 종료가 시작과 같은 일정은 저장된다` (DB `end_at >= start_at`) |
| FR-CAL-02 AC4 | 대상(나·함께, 기본 나), 일정 메모, 일정 알림(기본 없음)은 선택. 개인 공간은 항상 나 (D-34, D-59) | `FR-CAL-02 AC4 isTogether를 생략하면 false이고 note를 생략하면 null이다` |
| | | `FR-CAL-02 AC4 커플 공간에서 isTogether true로 만들면 함께 일정이 된다` |
| | | `FR-CAL-02 AC4 POST events는 event_reminders를 만들지 않고 응답의 알림은 null이다` (D-59, API.md 3.3) |
| | | `FR-CAL-02 AC4 개인 공간에서 isTogether true면 400 INVALID_TARGET이고 저장되지 않는다` (R-10 테스트와 같음) |
| | | `FR-CAL-02 AC4 일정의 대상 사용자는 받지 않는다: 본문의 다른 사용자 ID 필드는 일정의 작성자·대상을 바꾸지 않는다` (R-10, 모르는 필드는 무시) |
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
| FR-CAL-05 AC1 | 상대 일정 포함 아무 일정에, 없음·정시·10분 전·1시간 전·1일 전 중 하나를 고르면 그 사용자 것만 생긴다 | `FR-CAL-05 AC1 시간 일정에 AT_TIME·MIN_10·HOUR_1·DAY_1을 고르면 event_reminders 행이 요청자 것으로 생기고 fire_at이 시작 시각 기준으로 계산된다` (옵션마다 한 케이스, D-90) |
| | | `FR-CAL-05 AC1 상대가 만든 일정에도 내 알림을 설정할 수 있다` (R-01 통과, 작성자 무관) |
| | | `FR-CAL-05 AC1 알림을 다시 고르면 행이 하나로 유지되고 option과 fire_at이 바뀐다` (`uq_event_reminders_event_user`, 멱등) |
| | | `FR-CAL-05 AC1 알림 끄기(DELETE reminder)는 내 행만 지운다` (204, 상대의 행은 남음) |
| | | `FR-CAL-05 AC1 알림이 없는 일정에 DELETE reminder를 불러도 204다` (멱등) |
| | | `FR-CAL-05 AC1 허용하지 않는 option 값은 400 VALIDATION_FAILED (option INVALID)이고 option이 없으면 REQUIRED` |
| FR-CAL-05 AC2 | 상대에게는 내 알림이 안 보인다 | `FR-CAL-05 AC2 A가 알림을 설정해도 B의 GET events/{id}와 GET events 응답의 알림은 null이다` |
| | | `FR-CAL-05 AC2 A와 B가 각자 설정하면 각자 자기 알림만 본다` |
| FR-CAL-05 AC3 | 종일 일정의 선택지는 없음·당일 오전 9시·1일 전 오전 9시 | `FR-CAL-05 AC3 종일 일정에 ALLDAY_9AM·ALLDAY_PREV_9AM을 고르면 fire_at이 요청자 시간대 기준 당일·전날 09:00이다` (확인 필요 5) |
| | | `FR-CAL-05 AC3 종일 일정에 시간 일정용 option을, 시간 일정에 종일용 option을 고르면 400 VALIDATION_FAILED (option INVALID)이다` |
| FR-CAL-05 AC4 | 누구든 일시를 바꾸면 모든 사용자의 알림 시각이 다시 계산된다 | `FR-CAL-05 AC4 작성자가 일시를 바꾸면 알림을 건 두 사람의 fire_at이 새 일시 기준으로 다시 계산된다` |
| | | `FR-CAL-05 AC4 상대가 함께 일정의 일시를 바꿔도 알림을 건 두 사람의 fire_at이 다시 계산된다` |
| | | `FR-CAL-05 AC4 제목·메모만 바꾸면 fire_at은 그대로다` |
| | | `FR-CAL-05 AC4 일정을 종일↔시간으로 바꾸면 그 일정의 모든 사용자 알림이 지워지고 응답의 reminder는 null이다` (D-91) |
| | | `FR-CAL-05 AC4 이미 보낸 알림(sent_at)이 있는 일정의 시작을 바꾸면 새 fire_at이 미래일 때 sent_at이 비워져 다시 보낸다` (D-91) |
| | | `FR-CAL-05 AC4 사용자의 시간대가 바뀌면 그 사용자의 종일 알림 fire_at만 새 시간대 기준으로 다시 계산된다` (`UserTimeZoneChanged` 리스너, 같은 트랜잭션. 시간 일정 알림은 절대 시각이라 그대로) |
| | | `FR-CAL-05 AC4 계산한 fire_at이 이미 지났으면 행은 저장되고 sent_at이 지금으로 기록된다` (D-90) |
| FR-CAL-05 AC1 (발송) | 해당 시각에 푸시 | 단위 6 (1분 스케줄러, API.md 7장) |

**FR-NOTI-04 상대에게 알림 보내기** (D-35) — 일정 몫. 행 생성은 단위 3 (D-92)

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-NOTI-04 AC1 | 작성 화면 스위치, 기본 켜짐 | 서버: `FR-NOTI-04 AC1 notifyPartner를 생략하면 true로 본다` (아래 AC2 테스트로 확인). 화면은 앱 |
| FR-NOTI-04 AC2 | 켜고 저장하면 상대에게 알림 (푸시는 상대 설정이 켜진 경우만) | `FR-NOTI-04 AC2 커플 공간에서 notifyPartner true로 만들면 상대에게만 PARTNER_CREATED 알림 행이 생긴다` (제목 "[작성자 닉네임]님이 일정을 추가했어요", 본문 "[날짜] · [일정 제목]", `target_type` EVENT, `target_id` 일정 ID, `space_id` 커플 공간. 서식은 확인 필요 7) |
| | | `FR-NOTI-04 AC2 notifyPartner false로 만들면 알림 행이 생기지 않는다` |
| | | `FR-NOTI-04 AC2 상대의 partner_created 설정이 꺼져 있어도 알림 행은 생긴다` (DB-07, 푸시만 설정을 본다. 푸시는 단위 6) |
| | | `FR-NOTI-04 AC2 일정 생성 이벤트는 커밋된 뒤에만 리스너에 전달된다` (단위 6의 푸시·SYNC `EVENT`/`CREATED` 연결점) |
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

규칙마다 허용·거부 둘 이상 (CONVENTIONS). 3.3의 PATCH·DELETE 권한 칸에는 R-01이 없지만 2장 머리말("모든 요청은 먼저 활성 공간 검사")대로 R-01을 먼저 적용한다 (v0.9가 3.3 권한 칸에 R-01을 더했다).

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

모든 경로는 `/api/v1` 아래. API.md 3.3은 요청·응답 모양을 대부분 적지 않았고 API.md 7행은 3.1·3.2 밖의 상세를 서버 OpenAPI가 정한다고 한다. v0.9가 3.3에 `Event`·쓰기 규칙·조회 규칙·검사 순서를 정했다 (위 "확인 필요 해결" 1~3·9번).

| 메서드·경로 | 요청 | 성공 응답 | 오류 | 흐름·규칙 |
| --- | --- | --- | --- | --- |
| `GET /events?from=&to=` | 쿼리 `from`, `to` (필수, `YYYY-MM-DD`, `to` 포함) | 200 `{items: [Event]}`. 각 일정에 요청자의 알림만 | 400 `VALIDATION_FAILED`(`REQUIRED`/`INVALID`, 순서·3개월 초과는 `to` `INVALID`) | R-01, R-07, R-15, FR-CAL-01 |
| `POST /events` | `{title, note, isAllDay, start, end, isTogether, notifyPartner}` | 201 `Event`. 알림은 만들지 않는다 (D-59) | 400 `VALIDATION_FAILED`, 400 `DATE_OUT_OF_RANGE`, 400 `INVALID_TARGET` | R-01, R-10, R-15, FR-CAL-02, FR-NOTI-04 |
| `GET /events/{id}` | 없음 | 200 `Event` | 404 `NOT_FOUND` | R-01, R-07, FR-CAL-03 |
| `PATCH /events/{id}` | 일부 필드 부분 수정 (`note: null`만 메모 지우기) | 200 `Event` | 400 (위와 같음), 403 `NOT_AUTHOR`, 404 | R-01, R-02, R-10, R-13, R-15, FR-CAL-03, FR-CAL-05 AC4 |
| `DELETE /events/{id}` | 없음 | 204 | 403 `NOT_AUTHOR`, 404 | R-01, R-02, FR-CAL-03 |
| `PUT /events/{id}/reminder` | `{option}` | 200 `Event` (바뀐 `reminder` 포함) | 400 `VALIDATION_FAILED`(`option` `INVALID`/`REQUIRED`), 404 | R-01, R-07, FR-CAL-05 |
| `DELETE /events/{id}/reminder` | 없음 | 204 | 404 (일정 없음). 알림이 없어도 204 (멱등) | R-01, R-07, FR-CAL-05 |
| `GET /me`, `GET /space`, 기타 | 바뀌지 않음 | — | — | — |

### 4. ERD 테이블과 제약

일정 테이블·제약은 V1에 모두 있다. 새 마이그레이션은 `V2__add_spaces_linked_at.sql` 하나다(`spaces.linked_at`, D-94). 일정 쪽은 (V1의 `ck_events_time_columns`, `ix_events_space_start_at`, `ix_events_space_start_date`, `ck_event_reminders_option`, `uq_event_reminders_event_user`, `ix_event_reminders_unsent_fire_at`를 그대로 쓴다.)

| 테이블 | 단위 3에서 하는 일 | 사용하는 제약·인덱스 |
| --- | --- | --- |
| events | 생성·조회·수정·삭제. 개인 공간은 `is_together = false`만 (서비스 규칙). `start`/`end` 날짜 범위는 서비스에서 검사 (DB-13). 가져오기 때 `space_id` 이동, 기록 유무 확인 (단위 2 연결점) | `ck_events_time_columns`(종일이면 날짜만, 아니면 일시만, 끝 >= 시작), `ix_events_space_start_at`, `ix_events_space_start_date` (범위 조회: `start <= 범위끝 AND end >= 범위시작`). FK `author_id`·`updated_by` RESTRICT, `space_id` CASCADE |
| event_reminders | 사용자별 알림 만들기·바꾸기·지우기, 일시 변경 재계산, 시간대 변경 재계산, 해제 때 공간 단위 삭제, 탈퇴 때 사용자 단위 삭제 (단위 2 연결점) | `uq_event_reminders_event_user`(일정당 사용자별 하나), `ck_event_reminders_option`(`AT_TIME`, `MIN_10`, `HOUR_1`, `DAY_1`, `ALLDAY_9AM`, `ALLDAY_PREV_9AM`), 부분 `ix_event_reminders_unsent_fire_at`(단위 6 스케줄러). "없음"은 행이 없는 것 |
| notifications | `PARTNER_CREATED` 행. `space_id` = 커플 공간, `target_type` `EVENT`, `target_id` 일정 ID. 일정을 지워도 행은 지우지 않는다 (D-43) | `ck_notifications_type`, `ck_notifications_target_type` |
| users | 요청자의 `time_zone`(날짜 판단, 종일 알림 시각), 알림 문구의 작성자 닉네임, 상대 ID | `FOR SHARE`(일정 생성과 알림 설정이 요청자·상대 행을 먼저 잡는다, 아래 5장). `FOR UPDATE`는 쓰지 않는다 |
| spaces, space_members | 활성 공간·종류(개인/커플)·상대 확인 (읽기만) | — |

| ERD 수명주기 | 단위 3 |
| --- | --- |
| 4.1 커플 연결 때 개인 공간 처리 (events 존재 확인) | 일정 몫: `existsInSpace` |
| 4.1 알림이 생길 때 (`PARTNER_CREATED` 행) | 행 생성만 (D-92). 푸시·SYNC는 단위 6 |
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

단위 2의 잠금 순서(users UUID 오름차순 → invite_codes → spaces)를 지킨다. 일정 쓰기는 두 사람이 동시에 움직일 수 있지만 공간 행을 잠그지 않는다. 사용자 행은 `FOR UPDATE`가 아니라 `FOR SHARE`로만 잡는다(생성은 요청자·상대, 알림 설정은 요청자).

| 흐름 | 잠금 | 막는 경쟁 |
| --- | --- | --- |
| `PATCH /events/{id}` | 그 일정 행 `FOR UPDATE` → (잠근 뒤 R-02·R-13 다시 확인) → 그 일정의 `event_reminders` 재계산 | 함께 일정의 동시 수정(마지막 저장이 이김, NFR-SYNC-03), 수정과 `PUT reminder`가 겹쳐 새 일시와 옛 일시 기준 알림이 섞이는 것 |
| `PUT /events/{id}/reminder` | 그 일정 행 `FOR UPDATE`(`EventLocker`) → 요청자의 행을 읽어 바꾸거나 넣는다(`ON CONFLICT`는 쓰지 않았다. 같은 일정을 잠가 한 줄로 서므로 유일 제약 위반이 나지 않는다) | 같은 사용자의 겹친 설정(`uq_event_reminders_event_user` 위반이 500이 되지 않게), 수정과 겹침 |
| `DELETE /events/{id}` | 일정 행 `FOR UPDATE` | 삭제와 수정·알림 설정이 겹침 (CASCADE가 알림을 지움) |
| `POST /events` + `PARTNER_CREATED` 행 | 공간 행을 잠그지 않는다. 행을 넣기 전에 요청자·상대 사용자 행을 `FOR SHARE`로 먼저 잡는다(구현 2부 "정한 것", 리뷰 1회차 뒤 `FOR KEY SHARE`에서 바꿈) | **공간 행을 잠근 채 사용자 행을 기다리면 안 된다**(잠금 순서 users → invite_codes → spaces, 단위 2 2부 결정, ARCHITECTURE 4장). 사전 `FOR SHARE`가 하는 일은 "해제·탈퇴(`FOR NO KEY UPDATE`)와 한 줄로 세우기"다 |

그 밖의 위험:

1. **연결 해제와 겹친 쓰기의 행이 해제 정리 뒤에 남는 경쟁** (리뷰 1회차 Major). 해제는 사용자 행을 `PESSIMISTIC_WRITE`(PostgreSQL `FOR NO KEY UPDATE`)로 잡은 채 공간을 `UNLINKED`로 바꾸고 그 공간의 알림 행을 지운다. 일정 쪽이 R-01·공간 읽기 뒤에 사용자 행을 기다리면 해제 커밋 뒤에 해제된 공간으로 행이 들어간다. 대책: `POST /events`는 요청자·상대를 `FOR SHARE`로 잡은 뒤 활성 공간·상대를 다시 읽고, 바뀌었으면 `PartnerLockRetry` 안에서 롤백하고 처음부터 다시 한다(재시도를 다 쓰면 다시 읽은 값으로 새 공간에 쓴다). `PUT`·`DELETE reminder`는 R-01 전에 요청자 행을 `FOR SHARE`로 잡아, 해제 커밋 뒤에는 R-01에서 404가 난다. 단위 6 스케줄러에 맡기지 않는다. 처음에는 `FOR KEY SHARE`였는데, 해제의 `PESSIMISTIC_WRITE`가 PostgreSQL에서 `FOR NO KEY UPDATE`라 충돌하지 않아 CI 동시 테스트(`R-01 알림을 설정하는 요청과 연결 해제가 동시에 와도 …`)가 남은 알림 행을 잡았다. `FOR SHARE`로 바꿨다.
   - 위험: 재시도 3번을 다 쓴 뒤 새 상대를 순서 밖에서 `lockShare`하면, 해제·탈퇴와 겹칠 때 PostgreSQL 교착 판정으로 500이 날 수 있다(`lockForUpdateWithPartner`의 재시도 소진과 같은 선례, 드묾).
2. **시간대·날짜 변환 경계.** 시간 일정의 날짜 범위(R-15)와 조회 경계, 종일 알림 시각은 모두 `users.time_zone`으로 계산한다. 서머타임이 있는 시간대에서 "1일 전"(`DAY_1`)은 24시간 전이다 (D-90). `Clock` 빈으로 고정한 시각으로 테스트한다 (ARCHITECTURE 6장).
3. **범위 조회 쿼리.** 종일(`start_date`/`end_date`)과 시간(`start_at`/`end_at`) 일정이 서로 다른 컬럼·인덱스라 두 조건을 합쳐야 한다. 요청자 시간대의 `from` 0시 ~ `to` 다음 날 0시를 UTC 일시로 바꿔 시간 일정에 쓰고, 종일 일정에는 날짜 그대로 쓴다. 3개월 범위라 페이지는 없지만 한 달 일정이 많아도 메모리가 작은 서버(ARCHITECTURE 7장)에서 문제없도록 필요한 컬럼만 읽는다.
4. **JPA 쓰기 순서.** 재계산은 같은 트랜잭션에서 알림 엔티티를 고쳐 쓰고(`reschedule`), 종일↔시간 전환의 삭제만 일괄 DELETE(`flushAutomatically`)다. 일정 행 수정과 알림 재계산 순서에는 UNIQUE 충돌이 없다.
5. **의존 방향.** `couple` → `event`(서비스만), `user.WithdrawalDataEraser` → `event`(알림 서비스만), `event` → `space`·`user`·`notification`(서비스만). `event`가 `couple`을 알면 안 된다. 같은 방식으로 단위 2가 `user` → `couple`·`notification` 의존을 만들었다.

## 단위 6과의 경계 (지금 할 것과 미룰 것)

API 7장: "모든 알림 작업은 받는 사람마다 `notifications` 행을 먼저 만들고 푸시는 조건이 맞을 때만". 단위 2 선례: 행 생성과 커밋 뒤 도메인 이벤트 발행까지가 도메인 단위, 푸시·SYNC 발송은 단위 6 (unit-2.md "단위 6과의 경계").

| 일 | 단위 | 근거 |
| --- | --- | --- |
| `event_reminders` 만들기·바꾸기·지우기·재계산 | **3** | API 3.3, ERD 3.3, FR-CAL-05 |
| 1분마다 `fire_at <= now`이고 `sent_at` 없는 알림을 `EVENT_REMINDER` 행으로 만들고 `sent_at` 기록, 푸시 | 6 | API 7장 표, PLAN 단위 6 "스케줄러(일정·기념일 알림)" |
| `PARTNER_CREATED` 알림 행 (일정 생성 때, 상대) | **3** (D-92) | API 7장 "상대 활동 알림 즉시", FR-NOTI-04, PLAN 단위 3 행에는 FR-NOTI가 없음 |
| 일정 `EventCreated`/`EventUpdated`/`EventDeleted` 도메인 이벤트 발행 (커밋 뒤 전달) | **3** (리드 결정) | ARCHITECTURE 4장. 단위 2의 `SpaceUpdated`와 같은 방식 |
| SYNC `EVENT`/`CREATED`·`UPDATED`·`DELETED` (공간의 다른 구성원), `NOTIFICATION` (행 생긴 본인), 푸시 | 6 | API 6장, 7장, R-16 |
| `GET /notifications`, `/notifications/unread-count`, 읽음 처리 | 6 | API 3.5 |
| 일정 삭제 때 알림 행 유지 | **3** (삭제하지 않음) | D-43 |

## 결정

구현하면서 정한 것 중 문서에 없는 것. 문서에 있어야 할 결정이면 여기 적지 말고 작업을 멈추고 보고한다.

범위 승인 때 정한 후보 중 v0.9로 확정된 것은 위 "확인 필요 해결"에 있다. 아래 후보는 1부·2부를 합친 계획이다:

- 패키지: `event/api`(컨트롤러, 요청·응답 DTO), `event/application`(`EventService`, `EventReminderService`, `EventReminderCalculator`, 도메인 이벤트), `event/domain`(`Event`, `EventReminder`, 리포지토리, `ReminderOption`).
- `updated_by`: 생성 때는 null, 수정할 때 수정자로 기록한다 (ERD "마지막 수정자").
- `PUT reminder`는 멱등 upsert, `DELETE reminder`는 알림이 없어도 204 (D-91, API.md 3.3).
- 일정 변경 도메인 이벤트는 트랜잭션 안에서 발행하고 단위 3에는 리스너가 없다 (단위 6). `PARTNER_CREATED` 알림 ID는 이벤트에 담는다 (단위 2 `CoupleLinked.notificationIds`와 같다).
- `SpaceAccessPolicy`에 일정용 메서드를 더하되, 일정 엔티티를 몰라서 값(`authorId`, `isTogether`, 일정의 `spaceId`)만 받는다. R-01은 기존 `requireInActiveSpace`를 쓴다.
- 요청 본문은 단위 2와 같이 `@RequestBody Map`으로 받아 "필드 없음"과 `null`을 구분하고, R-01·R-02·R-13·R-10(403·404·400 INVALID_TARGET)이 값 검사(400)보다 먼저 나가게 한다 (API.md 3.3 "검사 순서").
- `GET /events` 정렬은 `(시작 일시 또는 날짜, 종일 먼저, id)` 오름차순이다 (확인 필요 3).
- 테스트 픽스처: `IntegrationTestSupport`가 이미 `events`, `event_reminders`를 INSERT하는 헬퍼를 갖고 있다 (365행, 400행 근처). 일정 API 테스트는 이 헬퍼를 넓혀 쓴다.

## 구현 1부 (완료, CI 통과: 테스트 500개)

1부는 PR #8의 CI에서 통과했다(컴파일 오류 5건은 리드가 41a2ea4에서 고쳤다: 생성자 필드를 더하면 호출부도 모두 찾아 고친다).

### 만든 것

- 마이그레이션: `src/main/resources/db/migration/V2__add_spaces_linked_at.sql` (ERD 3.2 순서: 컬럼 추가 → 기존 COUPLE 행을 `created_at`으로 채움 → V1의 `ck_spaces_couple_only_columns` 재생성(`linked_at is null` 추가) → `ck_spaces_couple_linked_at`). V1은 고치지 않았다
- `space`·`user`: `Space.linkedAt`, `SpaceRepository.reactivate`가 `linked_at`도 지금으로, `SpaceService.createCoupleSpace`가 `linkedAt` 설정, `MeSpace.linkedAt`·`importCounts`(`ImportCounts`), `Space` 응답 `linkedAt`
- `event/domain`: `Event`, `EventReminder`(읽기용), `ReminderOption`, `EventRepository`, `EventReminderRepository`
- `event/application`: `EventService`(목록·생성·조회·수정·삭제), `EventWriteRules`(쓰기 값 검사), `EventView`
- `event/api`: `EventController`(5개 경로), `EventResponse`
- `common/access/SpaceAccessPolicy`: `requireEventAuthorOrTogether`(R-02), `requireEventTargetChangeByAuthor`(R-13), `requireEventTargetAllowed`(R-10)

### 1부에서 정한 것 (문서에 없는 구현 선택)

- **일정 쓰기는 공간 행을 잠그지 않는다** (리드 결정, 교착 방지). 잠금 순서는 단위 2의 users → invite_codes → spaces를 그대로 두고, 일정은 일정 행만 `FOR UPDATE`로 잠근다. `PATCH`·`DELETE`는 일정 행을 엔티티 로딩 없이 공간 ID만 읽어 R-01을 먼저 검사하고, 일정 행을 잠근 뒤 잠근 값으로 R-01·R-02·R-13·R-10을 다시 확인한다. 같은 일정을 두 사람이 동시에 고치면 나중 커밋이 이긴다 (NFR-SYNC-03)
- `importCounts`의 개수는 `CoupleContentGateway.countsOf`가 네이티브 SQL로 센다 (임시). 기념일은 직접 기념일 행만 센다. 단위 4·5가 기념일·메모 쪽을 각 서비스로 옮길 때 같이 옮긴다
- UUID 형식이 아닌 일정 ID는 404 `NOT_FOUND` (없는 일정과 같은 응답, R-01)
- `VALIDATION_FAILED`가 하나라도 있으면 그것만 응답하고 `DATE_OUT_OF_RANGE`는 그 뒤에 본다 (한 응답에 두 코드를 섞지 않는다)
- `PATCH`의 날짜 범위 검사(R-15)는 요청에 있는 날짜에만 한다. 저장된 날짜는 다시 거부하지 않는다 (요청자와 상대의 시간대가 달라 저장된 시간 일정이 요청자 시간대로는 범위 밖일 수 있다)
- 시간대 값이 잘못돼 있으면(`users.time_zone`) UTC로 대신한다
- `notifyPartner`는 1부에서 값만 검사했고 2부에서 `PARTNER_CREATED` 행을 만들지 정하는 데 쓴다
- 일정 `title`은 앞뒤 공백을 지운 값을 저장한다. 목록 정렬은 `(날짜, 종일 먼저, start_at, id)`

## 구현 2부 (완료, CI 확인 대기)

### 만든 것

- `event/application`:
  - `EventReminderService`: `PUT`·`DELETE /events/{id}/reminder`, 일정 수정 때 다시 계산(`deleteAllOfEvent`·`recalculateAllOfEvent`), `UserTimeZoneChanged` 리스너(`@EventListener`, `PATCH /me`와 같은 트랜잭션)
  - `EventReminderCalculator`: `fire_at` 계산식(D-90)과 `sent_at` 규칙
  - `EventLocker`: R-01 + 일정 행 `FOR UPDATE`를 `PATCH`·`DELETE`·알림이 함께 쓴다
  - `EventContentService`: 다른 도메인이 부르는 일정·일정 알림 문(개수, 가져오기 이동, 공간·사용자 단위 알림 삭제)
  - `EventCreated`·`EventUpdated`·`EventDeleted`: 커밋 뒤 전달용 도메인 이벤트
  - `UserZones`: `users.time_zone` 해석(잘못된 값은 UTC)
- `EventService`: `PARTNER_CREATED` 행 만들기, 일정 변경 이벤트 발행, `PATCH`의 알림 처리
- `EventController`: `PUT`·`DELETE /events/{id}/reminder`
- `NotificationService.createPartnerCreated`, `NotificationTexts.partnerCreatedTitle`·`partnerCreatedBody`·`monthDayWeekday`
- 단위 2 연결점 이전: `CoupleContentGateway`의 events·`event_reminders` SQL(`hasContent`·`countsOf`·`moveAll`·`deleteEventReminders`)과 `WithdrawalDataEraser`의 `event_reminders` 삭제를 `EventContentService`로 옮겼다. 기념일·메모 SQL은 단위 4·5 때까지 게이트웨이에 남는다
- `UserService.lockShare`, `UserRepository.lockShare` (아래 "2부에서 정한 것")

### 2부에서 정한 것 (문서에 없는 구현 선택)

- **`PARTNER_CREATED` 행은 상대의 `partner_created` 설정과 관계없이 만든다.** API.md 7장 머리말("모든 알림 작업은 받는 사람마다 `notifications` 행을 먼저 만들고, 푸시는 ... 그 종류의 푸시 설정이 켜져 있을 때만")과 ERD DB-07이 그렇다. 설정을 꺼 둔 사람은 푸시만 안 받고 알림 목록에는 남는다. 리드 지시("설정이 꺼져 있으면 안 만듦")와 달라 문서를 따랐고 보고에 적었다
- **일정 만들기는 행을 넣기 전에 요청자와 커플 상대의 `users` 행을 UUID 오름차순으로 `FOR SHARE`로 먼저 잡는다**(처음엔 `FOR KEY SHARE`, 위험 1 참고). 공간 행은 여전히 잠그지 않는다. 이 사전 잠금은 해제·탈퇴(`lockForUpdateWithPartner`, `FOR NO KEY UPDATE`)와 한 줄로 서게 하고, 사용자 행을 공간 행보다 먼저 잡는 순서를 지킨다. `docs/ARCHITECTURE.md` 4장에 한 줄 더했다. 테스트: `FR-AUTH-06 AC1 일정을 만드는 요청과 상대의 탈퇴가 동시에 와도 교착 없이 끝난다`
- `PUT reminder`·`DELETE reminder`는 일정 행을 `FOR UPDATE`로 잠근다(`EventLocker`). 같은 사용자의 `PUT` 두 개가 겹쳐도 유일 제약 위반 없이 한 줄로 서고, `PATCH`의 다시 계산과도 겹치지 않는다
- `PATCH`가 `isAllDay`를 바꾸면 `deleteAllOfEvent`, 같은 종류에서 `start`가 실제로 바뀌면 `recalculateAllOfEvent`다. `start`를 같은 값으로 다시 보내는 것은 바뀐 것이 아니다. `end`·제목·메모·`isTogether`만 바뀌면 알림은 건드리지 않는다
- 다시 계산과 `PUT`은 `fire_at` <= 지금이면 `sent_at` = 지금(같은 시각도 지난 것), 미래면 null이다 (D-90). 지난 `fire_at`도 응답에 그대로 나간다
- 시간대 변경 리스너는 `UserTimeZoneChanged.timeZone`(새 값)으로 계산하고, 종일 옵션 중 `sent_at IS NULL`인 행만 고친다. 시간 일정 알림과 이미 `sent_at`이 찍힌 행은 그대로다 (API.md 3.3 "다시 계산" 마지막 줄)
- `PARTNER_CREATED`의 날짜: 종일은 `start_date`, 시간 일정은 받는 사람의 `users.time_zone`으로 바꾼 `start_at`의 날짜. 서식은 로케일 데이터에 기대지 않고 직접 쓴다(`월화수목금토일`). 본문의 제목은 저장된(앞뒤 공백을 지운) 제목이다
- 도메인 이벤트는 일정을 쓰는 트랜잭션 안에서 발행한다. `EventCreated`는 상대 알림이 생겼을 때만 `partnerUserId`·`notificationId`를 담는다(단위 6이 `NOTIFICATION` 신호와 푸시에 쓴다). `PUT`·`DELETE reminder`는 발행하지 않는다 (API.md 6장)
- `GET /events/{id}`·목록은 그대로다. 가져오기(`POST /couple/import`)가 옮기는 일정은 알림 행도 그대로 따라간다(`event_id`가 같다)

### 2부에서 만든 테스트

- `event/EventReminderTest`: FR-CAL-05 AC1(설정·다시 고르기·멱등·동시 PUT·끄기·option REQUIRED/INVALID), AC3(종일 옵션·알림 주인 시간대·종류 불일치), AC4(작성자·상대 일시 변경, 종일↔시간 전환 삭제, `sent_at` 되살리기, 거부된 PATCH, 삭제), AC5(지난 `fire_at`, 경계, 끝난 일정), AC6(시간대 변경), R-01, R-07, NFR-SYNC-01
- `event/EventPartnerNotificationTest`: FR-NOTI-04 AC2·AC3(행 내용, 설정 꺼짐, 날짜 서식·시간대, 롤백), D-45, FR-AUTH-06 AC1(탈퇴와 동시 생성), NFR-SYNC-01(커밋 뒤 전달, 거부 시 미발행)
- `support/FakeCouple.kt`의 `EventChangeRecorder`, `IntegrationTestSupport`의 `putReminder`·`deleteReminder`, `EventTestSupport`의 알림 도우미
- `event/EventWriteTest`의 "삭제해도 알림 행은 지우지 않는다" 테스트는 이제 만들 때 생기는 실제 `PARTNER_CREATED` 행으로 확인한다

### 만든 테스트 (`src/test/kotlin/com/duriseo/server/`)

- `event/EventWriteTest`: FR-CAL-02 AC1~AC4, FR-CAL-03 AC1·AC3, NFR-SYNC-03
- `event/EventQueryTest`: FR-CAL-01 AC2, FR-CAL-02 AC5, R-01(목록)
- `event/EventPermissionTest`: R-01, R-02, R-13, R-10, R-07, FR-CAL-03 AC2·AC4·AC5, FR-CAL-05 AC2
- `event/EventDateRangeTest`: R-15 (쓰기·조회·시간대·우선순위)
- `event/EventTestSupport`: 공통 도구
- `space/SpaceLinkedAtTest`: FR-SET-01 AC1, FR-COUPLE-06 AC1, FR-COUPLE-03 AC1·AC2
- `db/SchemaMigrationTest`: 버전 "2", `linked_at` 제약 테스트 추가
- `common/access/SpaceAccessPolicyTest`: R-02·R-13·R-10 허용·거부 6개
- `support/IntegrationTestSupport`: 일정 도우미(`getEvents`·`createEvent` 등), `linked_at` 반영

## 리뷰 기록

| 회차 | Critical | Major | 비고 |
| --- | --- | --- | --- |
| 1 | 0 | 1 | Minor 6. 처리는 아래 |
| 2 | 0 | 0 | 머지 가능. Minor 5. 처리는 아래 |

**1회차 처리**

- Major 연결 해제와 겹친 쓰기: 위 "동시성" 위험 1. 코드 `EventService.create`(`TransactionTemplate` + `PartnerLockRetry`, `lockShare` 뒤 공간·상대 재확인(처음엔 `FOR KEY SHARE`였으나 해제의 `FOR NO KEY UPDATE`와 충돌하지 않아 `FOR SHARE`로 바꿨다)), `EventReminderService.lockRequester`. 테스트: `D-45 일정을 만드는 요청과 연결 해제가 동시에 와도 …`, `R-01 알림을 설정하는 요청과 연결 해제가 동시에 와도 …`
- Minor 일시 형식: `YYYY-MM-DDTHH:MM:SSZ`만 받는다(리드 결정). `Z`가 아닌 오프셋과 소수 초는 (`start`/`end`, `INVALID`). 테스트: `FR-CAL-02 AC2 Z가 아닌 오프셋과 소수 초가 있는 일시는 …`
- Minor UUID 잠금 순서: Kotlin `sorted()`(부호 있는 비교)를 PostgreSQL `order by`와 같은 부호 없는 비교(`common/persistence/UuidOrder.kt`의 `sortedAsPostgres`)로 바꿨다. `UserService.lockForUpdateWithPartner`, `CoupleLinker.lockUsersInOrder`에 적용
- Minor 빈 본문 `PATCH`: 저장값과 같은 값만 보내면 `updated_by`·`updated_at`을 바꾸지 않고 `EventUpdated`도 없다(200 `Event`는 그대로). 테스트: `FR-CAL-03 AC1 아무 필드도 바뀌지 않는 PATCH는 …`, `… 하나라도 바뀌면 …`
- Minor 계획된 테스트 2개: `FR-COUPLE-03 AC1 일정만 있는 개인 공간도 기록이 있는 공간으로 본다`(`CoupleJoinTest`), `FR-COUPLE-03 AC1 import true면 일정이 author_id·is_together·일정 알림을 유지한 채 커플 공간으로 옮겨진다`(`CoupleImportTest`)
- Minor 진행 기록의 오래된 문장("새 마이그레이션 없음", "사용자 행을 잠그지 않는다")을 고쳤다

**2회차 처리**

- 사전 잠금을 `FOR KEY SHARE`에서 `FOR SHARE`(`UserService.lockShare`, 커밋 411f85c)로 바꾼 것을 주석·문서에 맞췄다: `EventService` 머리말, `UserService.lockForUpdateWithPartner`·`lockShare`, `UserRepository.lockShare`, `EventReminderService`, ARCHITECTURE 4장, 이 문서 5장 표·위험 1·2부 "정한 것". PostgreSQL 외래 키 검사는 `FOR KEY SHARE`이고 해제·탈퇴의 `PESSIMISTIC_WRITE`는 `FOR NO KEY UPDATE`다
- 위험 목록에 재시도 소진 뒤 순서 밖 `lockShare`의 교착 판정 500 가능성을 한 줄 더했다
- `PATCH /me`(`UserService.updateProfile`)는 첫 줄에서 사용자 행을 `FOR UPDATE`로 잠가 같은 사용자의 `PUT reminder`와 한 줄로 선다. 테스트: `FR-CAL-05 AC6 시간대 변경과 알림 설정이 동시에 와도 새 알림은 새 시간대로 계산돼 있다`
- 상태·남은 일 첫 줄을 CI 571 통과(두 번)로 고쳤다

## Docs 후속 (Docs 레포 `/prd-sync`가 할 일)

- Android: 일시는 `YYYY-MM-DDTHH:MM:SSZ`만 받는다. 밀리초나 `Z`가 아닌 오프셋이 붙으면 400 `VALIDATION_FAILED`다
- API.md 1장에 일시 형식은 `YYYY-MM-DDTHH:MM:SSZ`만이고 `Z`가 아닌 오프셋과 소수 초는 받지 않는다고 명시 (서버는 `start`/`end` `INVALID`로 거부한다)


## 남은 일

- CI 571개 통과(두 번). 2회차 Minor 정리분(주석·문서·`PATCH /me` 잠금과 테스트 1개)은 CI에서 `./gradlew build` 초록불 확인 (이 환경은 Gradle 플러그인·Docker에 닿지 않는다)
- 단위 4·5: `CoupleContentGateway`에 남은 `anniversaries`·`memos` 네이티브 SQL(`hasContent`·`countsOf`·`moveAll`)을 각 도메인 서비스로 옮기고 이 클래스를 없앤다. `WithdrawalDataEraser`의 `memo_favorites` 삭제는 단위 5
- 단위 6: 1분 일정 알림 스케줄러(`EVENT_REMINDER` 행, `sent_at IS NULL`이고 `fire_at <= now`, 푸시), 일정 도메인 이벤트(`EventCreated`·`EventUpdated`·`EventDeleted`)를 듣는 SYNC `EVENT`, `PARTNER_CREATED` 푸시(상대의 `partner_created` 설정과 유효 세션을 본다)와 `NOTIFICATION` 신호
- Android: 일정 응답 모양·오류 코드·알림 option 값은 API.md 3.3 (v0.9)를 따른다

## 확인 필요

없음. 처음 범위를 잡을 때 올린 13건은 모두 Docs v0.9(PRD D-89~D-103, API.md 3.3, ERD 3.2)로 해결됐다. 해결 내용은 맨 앞 "확인 필요 해결" 절을 본다. 구현 1부·2부에서 문서와 달라 멈춘 곳은 없다. 다만 리드 지시와 문서가 다른 곳이 하나 있어 문서를 따랐다 (위 "2부에서 정한 것"의 `partner_created` 설정).
