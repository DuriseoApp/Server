# 단위 4: 기념일 — 자동 기념일 계산, 직접 기념일, 목록 2종 (anniversary)

> 브랜치: `feat/4-anniversary` · PR: #9(초안) · 상태: **구현 2부 완료, CI 확인 대기** (확인 필요 1~5는 PRD v0.10 / API.md로 확정돼 반영함)
> 기준: PRD v0.10 (2부), PRD v0.9 (1부), API.md 1장·1.1·2장(R-01·R-05·R-15)·3.3 기념일·5장·6장·7장(기념일 알림 줄), ERD 3.3(anniversaries)·4.1·4.2·4.3·4.4·5장(DB-01·05·13)
> Docs 기준 커밋: DuriseoApp/Docs main `8e37acc` (PRD v0.10, 2부), `728ea08` (PRD v0.9, 1부). 읽은 경로는 `/home/claude/docs-main` (`../Docs` 대신)
> 앞 단위: 단위 3 머지됨 (main `ebc4f71`, 개발 서버 배포)

## 요약

- 새 API 경로 **6개** (API.md 3.3 "기념일" 표): `GET /anniversaries?from=&to=`, `GET /anniversaries/upcoming`, `GET /anniversaries/past`, `POST /anniversaries`, `PATCH /anniversaries/{id}`, `DELETE /anniversaries/{id}`
- FR **4개**: FR-ANNIV-02(자동 기념일), FR-ANNIV-03(직접 기념일), FR-ANNIV-04(목록), FR-CAL-01 AC6(기념일 몫). FR-ANNIV-01(D-day)과 FR-CAL-01 AC3·AC5는 앱 동작이라 서버 몫 없음 (`spaces.start_date`는 단위 2의 `GET /space`·`GET /me`가 이미 준다). 이월분으로 FR-COUPLE-03 AC1·AC2(기념일 몫)
- R- 규칙 **3개**: R-01, R-05(새 `SpaceAccessPolicy` 메서드), R-15(`anniversaries.date`, 범위 조회 잘라내기)
- 새 `ErrorCode` 없음 (`VALIDATION_FAILED`, `DATE_OUT_OF_RANGE`, `NOT_AUTHOR`, `NOT_FOUND`는 단위 3까지 있음). **새 마이그레이션 없음** (V1에 `anniversaries`, `ix_anniversaries_space` 있음)
- 단위 3에서 넘어온 일: `CoupleContentGateway`의 anniversaries SQL 3곳(`hasContent`·`countsOf`·`moveAll`)을 기념일 서비스로 옮긴다. `WithdrawalDataEraser`에는 anniversaries SQL이 **없다** (코드에는 `memo_favorites`만 있다. 탈퇴는 익명화라 `author_id`를 남긴다)
- **확인 필요 8건** (아래). 1~5번은 구현 전에 Docs 보강 또는 추천안 승인이 필요하다

## 확인 필요

문서끼리 다르거나 비어 있는 곳이다. 추측하지 않고 선택지와 추천안을 적었다.

1. **직접 기념일의 요청·응답 모양과 쓰기 규칙이 없다.** API.md 3.3 기념일 표(345~349행)는 `POST`·`PATCH`의 필드, 응답 객체, 기본값을 적지 않았다. 일정은 `Event`·"쓰기 규칙"이 있다 (API.md 251~292행). 알 수 있는 것은 ERD 3.3 `anniversaries`(`name` 30자, `date`, `repeats_yearly` 기본 true), NFR-LIMIT-01(이름 30자, 코드 포인트 D-82), `past` 항목 `{anniversaryId, name, date, authorId}`뿐이다. 비어 있는 것: 필수 필드, `repeatsYearly` 생략 값, `PATCH`의 `null` 처리, 이름 앞뒤 공백(D-89는 일정 제목만), 검사 순서(`INVALID`·`REQUIRED` 필드명), 응답에 `createdAt`·`updatedAt`·`updatedBy`가 있는지(ERD에 `updated_by` 컬럼이 없다)
   - A (추천): 일정 규칙을 그대로 따른다. 요청 `{name, date, repeatsYearly?}`, `POST` 필수 `name`·`date`(없거나 null이면 `REQUIRED`), `repeatsYearly` 생략 true(null이면 `REQUIRED`), 이름은 앞뒤 공백을 지운 뒤 빈 값 `REQUIRED`·30자 초과 `TOO_LONG`, `date`는 `YYYY-MM-DD` 아니면 `INVALID`, `PATCH`는 부분 수정(어떤 필드든 null이면 `REQUIRED`), 모르는 필드 무시, 검사 순서 R-01 → R-05 → 값 검사 → `DATE_OUT_OF_RANGE`(값 오류가 있으면 그것만). 응답 `Anniversary` = `{id, name, date, repeatsYearly, authorId, createdAt, updatedAt}`. 근거: API.md 283~291행(일정 규칙)과 FR-ANNIV-03 AC2 "D-12와 같은 규칙", API.md 538행(`DATE_OUT_OF_RANGE`에 `POST/PATCH /anniversaries` 포함)
   - B: 이 단위를 멈추고 Docs `/prd-sync`로 3.3 기념일에 `Anniversary` 정의와 쓰기 규칙을 먼저 넣는다. Android도 같은 모양을 읽으므로 A를 서버가 정하면 Docs와 어긋날 위험이 있다. 일정 때(단위 3)는 B로 갔다
2. **`GET /anniversaries?from=&to=`(캘린더용) 항목 모양과 오류 규칙이 없다.** 345행은 "`kind: AUTO` 또는 `CUSTOM`", "D-N 계산 포함"만 적었다. 필드 이름(`daysLeft`?), 과거 날짜의 값(음수? D+?), `from`·`to` 없음/형식 오류/`from` > `to`의 코드(일정 조회 규칙 274행은 `REQUIRED`/`INVALID`/`to` `INVALID`로 정했으나 기념일 표는 "3개월 초과 400"만 적었다)가 비어 있다
   - A (추천): `upcoming` 항목과 같은 모양 `{anniversaryId, kind, name, date, daysLeft, repeatsYearly, authorId}`를 쓰고(자동은 `anniversaryId`·`authorId` null, `repeatsYearly` false), `daysLeft` = 날짜 − 오늘(요청자 시간대)로 과거면 음수. 오류는 일정 조회 규칙(274~275행)과 같다. 응답 `{items}`(1.1). 근거: 346행 항목 정의, 43행 페이지 없는 목록
   - B: `daysLeft`를 캘린더용에서는 빼고(앱이 계산) 필드만 `{anniversaryId, kind, name, date, repeatsYearly, authorId}`로 한다. 345행의 "D-N 계산 포함"과 맞지 않는다
3. **자동 기념일 이름 문자열과 경계 규칙이 비어 있다.** 규칙은 FR-ANNIV-02 AC1·AC2(100일 = 처음 만난 날 + 99일, 1000일까지 100일 단위 10개, N주년 = 처음 만난 날 + N년, 2050-12-31까지), D-23, ERD DB-01에 있다. 비어 있는 것:
   - 응답 `name` 값. PRD 8장 511행 예시 "오늘은 200일이에요"와 FR-ANNIV-02 AC1 "N주년"만 있다. 추천: `"100일"` ~ `"1000일"`, `"1주년"`, `"2주년"`…
   - 처음 만난 날이 2월 29일일 때 비윤년 주년 날짜 (FR-ANNIV-03 AC3은 직접 기념일만 말한다). 추천: 2월 28일 (직접 기념일 AC3과 같은 규칙)
   - 같은 날 자동 기념일이 둘 이상일 수 있다 (예: 일수 기념일과 주년이 겹침). `upcoming` 커서가 `(date, kind, anniversaryId)`인데 자동은 `anniversaryId`가 null이라 같은 날 자동 둘을 가를 수 없다. 추천: 같은 날 자동끼리는 일수 → 주년 순으로 정렬하고 커서에 순번을 더한다 (커서는 앱에게 불투명한 문자열이므로 모양을 바꿔도 API 계약은 그대로). 이 경우 Docs 346행의 "커서는 (date, kind, anniversaryId)"와 구현이 달라지므로 Docs 문장 수정이 필요하다
   - B: 위 셋을 Docs에서 정한 뒤 시작한다
4. **커서·`size` 규칙이 없다.** `cursor`, `size`를 받는 경로(`upcoming`, `past`)의 `size` 기본값·최대값, 잘못된 커서의 응답, 커서 인코딩이 API.md 어디에도 없다 (1장 32행, 1.1 42행은 `nextCursor`만). 단위 5(메모)·6(알림)도 같은 규칙을 쓴다
   - A (추천): 공통 규칙으로 정한다. `size` 기본 20·최대 100(범위 밖은 `size` `INVALID`), 커서는 불투명 문자열(서버가 정한 정렬 키를 인코딩), 못 읽는 커서는 400 `VALIDATION_FAILED`(`cursor`, `INVALID`). 이 값들은 임의 선택이라 Docs에 올려 달라고 요청한다
   - B: 값을 Docs에서 먼저 정한다
5. **R-05 경로에 R-01이 적혀 있지 않다.** API.md 349행 권한 칸은 `R-05, R-15`뿐이고 2장 머리말(79행)은 "모든 요청은 먼저 활성 공간 검사"다. 단위 3에서 일정 `PATCH`·`DELETE`에 R-01을 더한 것(v0.9)과 같은 틈이다
   - A (추천): R-01 → R-05 순서(다른 공간·없는 ID는 404, 같은 공간의 남의 기념일은 403 `NOT_AUTHOR`). 근거: 2장 머리말, 단위 3 선례
   - B: Docs에 R-01을 더해 달라고 요청한 뒤 A로 구현
6. **SYNC `ANNIVERSARY` 신호의 몫.** API.md 6장은 `ANNIVERSARY`/`CREATED`·`UPDATED`·`DELETED`를 정의하고 발송은 PLAN 단위 6이다. 단위 3 선례(도메인 이벤트 발행까지 단위 3, 발송 단위 6)를 따르면 `AnniversaryCreated`·`AnniversaryUpdated`·`AnniversaryDeleted`를 커밋 뒤 전달용으로 발행만 한다. 처음 만난 날이 바뀌면 자동 기념일이 다시 계산되지만 문서에 별도 신호가 없고 `SPACE`/`UPDATED`(단위 2의 `SpaceUpdated`)가 이미 있다
   - A (추천): 단위 3과 같이 도메인 이벤트 발행까지만. 자동 기념일 재계산용 신호는 만들지 않는다(`SPACE`/`UPDATED`를 받은 앱이 다시 불러온다고 본다 — 이 가정은 Docs에 없으므로 단위 6 범위 점검에서 확인)
   - B: 신호 발송까지 단위 4에서 한다 (FCM 연동이 단위 6에 있어 불가능에 가깝다)
7. **기념일 알림 스케줄러 경계.** API.md 7장 593행(매시 정각, 사용자 시간대 오전 9시, `ANNIVERSARY` 행·문구)은 PLAN 단위 6 "스케줄러(일정·기념일 알림)"에 있다. 직접 기념일을 저장할 때 상대에게 가는 알림은 없다 (`notifyPartner`는 일정·메모뿐, 7장 594행)
   - A (추천): 단위 4는 "특정 날짜의 자동·직접 기념일을 구하는" 순수 계산기(`AnniversaryCalculator`)까지만 만든다. 알림 행·문구·스케줄러는 단위 6. 계산기는 7장 본문의 N(자동은 처음 만난 날 = D+1인 일수, 직접 반복은 올해 − 등록 연도 + 1)을 구할 수 있게 둔다
   - B: 알림 문구 계산도 단위 4에서 만든다. 단위 6과 규칙이 갈릴 위험
8. **리드 지시와 코드의 차이 (확인 요청이 아닌 사실 정정).** 범위 요청에 "`WithdrawalDataEraser`의 anniversaries SQL을 옮긴다"가 있으나 코드에는 anniversaries SQL이 없다 (`user/application/WithdrawalDataEraser.kt`는 `memo_favorites` 삭제만 grep에 걸린다). ERD 241행: 콘텐츠 `author_id`는 RESTRICT이고 탈퇴는 익명화라 기념일은 사용자 단위로 지우지 않는다. 그래서 탈퇴 쪽 이월은 없다. 이월은 `CoupleContentGateway`뿐이다

## 범위

### 1. 기능 (FR)과 수용 기준

JVM 메서드 이름에 `/`를 못 쓰므로 경로는 `GET anniversaries`처럼 적는다 (단위 1 결정). "앱 동작"은 서버 테스트가 없다. 테스트 클래스 계획(`anniversary/` 아래): `AutoAnniversaryCalculatorTest`(순수 단위), `AnniversaryQueryTest`, `AnniversaryWriteTest`, `AnniversaryPermissionTest`, `AnniversaryDateRangeTest`. `<확인 필요 N>`은 위 절의 번호다.

**FR-ANNIV-02 자동 기념일** (D-20, D-23, D-74) — 저장하지 않고 `spaces.start_date`로 계산 (DB-01)

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-ANNIV-02 AC1 | 100일~1000일(100일 단위)과 매년 N주년이 자동 기념일. 주년은 2050-12-31까지 | `FR-ANNIV-02 AC1 처음 만난 날이 있는 커플은 100일부터 1000일까지 10개의 일수 기념일이 계산된다` |
| | | `FR-ANNIV-02 AC1 N주년은 처음 만난 날에서 N년 뒤이고 2050-12-31 이후는 계산하지 않는다` |
| | | `FR-ANNIV-02 AC1 GET anniversaries upcoming에는 커플 공간의 자동 기념일이 kind AUTO로 나온다` |
| | | `FR-ANNIV-02 AC1 GET anniversaries 범위 조회에도 자동 기념일이 kind AUTO로 나온다` |
| FR-ANNIV-02 AC2 | 처음 만난 날 2026-01-01이면 100일 = 2026-04-10, 1주년 = 2027-01-01 | `FR-ANNIV-02 AC2 처음 만난 날 2026-01-01의 100일은 2026-04-10이고 1주년은 2027-01-01이다` (처음 만난 날 = 1일) |
| | | `FR-ANNIV-02 AC2 1000일은 처음 만난 날 + 999일이다` |
| FR-ANNIV-02 AC3 | 수정·삭제 불가. 처음 만난 날을 바꾸면 다시 계산 | `FR-ANNIV-02 AC3 자동 기념일은 PATCH·DELETE 경로가 없어 id로 가리킬 수 없다` (자동은 `anniversaryId` null, 임의 UUID는 404) |
| | | `FR-ANNIV-02 AC3 PATCH space로 처음 만난 날을 바꾸면 다음 조회의 자동 기념일 날짜가 새 날짜 기준이다` (저장 행 없음) |
| FR-ANNIV-02 (경계) | `<확인 필요 3>` 2월 29일 시작의 비윤년 주년, 같은 날 겹침 | `FR-ANNIV-02 AC1 처음 만난 날이 2월 29일이면 비윤년 주년은 2월 28일이다` (3번 승인 뒤), `FR-ANNIV-02 AC1 같은 날 자동 기념일 둘은 페이지 경계에서도 빠지거나 겹치지 않는다` |

**FR-ANNIV-03 직접 기념일** (D-20, D-12) — `POST /anniversaries`, `PATCH /anniversaries/{id}`, `DELETE /anniversaries/{id}`. 규칙은 `<확인 필요 1>`

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-ANNIV-03 AC1 | 개인·커플 공간 모두, 이름·날짜·매년 반복으로 저장하면 만들어지고 캘린더·목록에 표시 | `FR-ANNIV-03 AC1 커플 공간에서 이름과 날짜로 만들면 201이고 공간·작성자가 요청자 기준으로 저장된다` |
| | | `FR-ANNIV-03 AC1 개인 공간에서도 직접 기념일을 만들 수 있다` |
| | | `FR-ANNIV-03 AC1 repeatsYearly를 켜고 끄고 저장하면 그대로 저장되고 생략하면 true다` |
| | | `FR-ANNIV-03 AC1 이름이 없거나 공백뿐이면 400 VALIDATION_FAILED (name REQUIRED)` |
| | | `FR-ANNIV-03 AC1 이름이 30자를 넘으면 400 VALIDATION_FAILED (name TOO_LONG)이고 코드 포인트로 센다` (NFR-LIMIT-01, D-82) |
| | | `FR-ANNIV-03 AC1 date가 없거나 날짜 형식이 아니면 400 VALIDATION_FAILED (date REQUIRED/INVALID)` |
| | | `FR-ANNIV-03 AC1 만든 직접 기념일이 GET anniversaries 범위 조회와 upcoming에 CUSTOM으로 나온다` |
| | | `FR-ANNIV-03 AC1 작성자는 이름·날짜·repeatsYearly를 PATCH로 수정할 수 있다` |
| | | `FR-ANNIV-03 AC1 작성자는 직접 기념일을 삭제할 수 있다 (204)` |
| FR-ANNIV-03 AC2 | 수정·삭제는 작성자만 (R-05) | `FR-ANNIV-03 AC2 상대가 만든 기념일을 PATCH하면 403 NOT_AUTHOR이고 바뀌지 않는다` |
| | | `FR-ANNIV-03 AC2 상대가 만든 기념일을 DELETE하면 403 NOT_AUTHOR이고 남는다` |
| | | `FR-ANNIV-03 AC2 상대의 기념일도 목록에는 보이고 authorId로 작성자를 알 수 있다` |
| FR-ANNIV-03 AC3 | 2월 29일 반복은 비윤년에 2월 28일 | `FR-ANNIV-03 AC3 2월 29일에 매년 반복하는 기념일은 윤년이 아닌 해에 2월 28일로 나온다` |
| | | `FR-ANNIV-03 AC3 2월 29일 반복 기념일은 윤년에는 2월 29일로 나온다` |

**FR-ANNIV-04 기념일 목록** (D-71, D-75, D-54) — `GET /anniversaries/upcoming`, `GET /anniversaries/past`, 캘린더용 `GET /anniversaries?from=&to=`. 오류·커서는 `<확인 필요 2·4>`

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-ANNIV-04 AC1 | 오늘(포함)부터 2050-12-31까지 모두 가까운 순, 남은 일수(오늘은 0), 반복은 해마다 한 항목 | `FR-ANNIV-04 AC1 upcoming은 오늘 포함 오늘부터 2050-12-31까지의 자동·직접 기념일을 날짜순으로 돌려준다` |
| | | `FR-ANNIV-04 AC1 daysLeft는 요청자 시간대의 오늘 기준이고 오늘 기념일은 0이다` (`Clock` 고정, 서울과 UTC 사용자) |
| | | `FR-ANNIV-04 AC1 매년 반복하는 직접 기념일은 해마다 한 항목씩 나온다` |
| | | `FR-ANNIV-04 AC1 매년 반복하는 직접 기념일의 첫 발생일은 등록한 date의 해이고 date보다 이른 해는 나오지 않는다` |
| | | `FR-ANNIV-04 AC1 지난 날짜의 1회성 직접 기념일은 upcoming에 나오지 않는다` |
| | | `FR-ANNIV-04 AC1 upcoming은 cursor와 size로 이어 받으면 빠지거나 겹치는 항목 없이 nextCursor가 마지막에 null이다` (`<확인 필요 4>`) |
| | | `FR-ANNIV-04 AC1 같은 날 자동과 직접 기념일은 kind 순서로 안정적으로 정렬된다` |
| | | `FR-ANNIV-04 AC1 GET anniversaries 범위 조회는 범위 안 항목만 날짜순으로 돌려준다 (기념일 없는 날은 빠진다)` |
| | | `FR-ANNIV-04 AC1 GET anniversaries는 from·to가 없거나 형식이 아니거나 from이 to보다 뒤거나 3개월을 넘으면 400 VALIDATION_FAILED` (`<확인 필요 2>`) |
| FR-ANNIV-04 AC2 | 개인 공간은 직접 기념일만 | `FR-ANNIV-04 AC2 개인 공간 사용자의 upcoming과 범위 조회에는 AUTO가 없고 CUSTOM만 나온다` |
| FR-ANNIV-04 AC3 | 지난 1회성 직접 기념일은 "지난 기념일"에 최근 순, 수정·삭제는 작성자 | `FR-ANNIV-04 AC3 past는 repeatsYearly가 false이고 date가 오늘보다 앞인 직접 기념일만 최근 순으로 돌려준다` |
| | | `FR-ANNIV-04 AC3 오늘 날짜인 1회성 기념일은 past가 아니라 upcoming에 있다` (경계) |
| | | `FR-ANNIV-04 AC3 매년 반복하는 지난 기념일은 past에 나오지 않는다` |
| | | `FR-ANNIV-04 AC3 past의 totalCount는 페이지 크기와 관계없이 조건에 맞는 전체 개수다` |
| | | `FR-ANNIV-04 AC3 past의 오늘 판단은 요청자 시간대 기준이다` (서울 사용자와 UTC 사용자) |
| | | `FR-ANNIV-04 AC3 past에서 받은 anniversaryId로 작성자는 PATCH·DELETE할 수 있다` |
| FR-ANNIV-04 AC4 | 처음 만난 날이 없는 커플은 자동 기념일 없음 | `FR-ANNIV-04 AC4 처음 만난 날이 없는 커플 공간에는 AUTO가 없고 CUSTOM만 나온다` |

**FR-CAL-01 캘린더 홈** — 기념일 몫은 AC6, AC3·AC5는 앱 동작 (D-14, D-54, D-74)

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-CAL-01 AC3 | 커플 상단 D+N | 앱 동작 (`spaces.start_date`는 `GET /space` 응답, 단위 2) |
| FR-CAL-01 AC6 | 직접 기념일 날짜 1950-01-01 ~ 2050-12-31. 쓰기는 400 `DATE_OUT_OF_RANGE`, 범위 조회는 잘라서 (D-74) | 아래 R-15 테스트 |

**단위 3에서 넘어온 것** (unit-3.md 남은 일)

| 항목 | 지금 | 단위 4에서 | 테스트 |
| --- | --- | --- | --- |
| `CoupleContentGateway.hasContent`의 anniversaries 개수 | `couple/application`에서 네이티브 SQL | 기념일 서비스(`AnniversaryContentService.countIn(spaceIds)`, `EventContentService`와 같은 모양)를 부른다. 게이트웨이는 `memos`만 센다 | 기존 `FR-COUPLE-03 AC1·AC4`, `FR-COUPLE-05 AC4` 통과. 추가: `FR-COUPLE-03 AC1 기념일만 있는 개인 공간도 기록이 있는 공간으로 본다` |
| `countsOf`의 anniversaries 부분 | 네이티브 SQL | `AnniversaryContentService.countIn`. 직접 기념일 행만 센다 (자동은 저장하지 않으므로 세지 않는다, API.md 170행) | 기존 `SpaceLinkedAtTest`의 `importCounts.anniversaries` 통과. 추가: `FR-COUPLE-03 AC2 importCounts.anniversaries는 자동 기념일을 세지 않는다` (처음 만난 날이 있는 대기 공간은 없으므로 개인 공간의 직접 기념일 행 수와 같음을 확인) |
| `moveAll`의 anniversaries | `update anniversaries set space_id` | `AnniversaryContentService.moveAll(from, to)` | 기존 `CoupleImportTest`의 기념일 이동·`author_id` 유지 통과 |
| `WithdrawalDataEraser` | anniversaries SQL 없음 (확인 필요 8) | 없음 | — |
| `SpaceAccessPolicy` | R-01·02·07~11·13·14·17·18 | R-05 메서드 추가. `authorId` 값만 받는다 (공통 모듈은 도메인을 모름) | 아래 2장 |
| `common/api/ErrorCode` | 필요한 코드 모두 있음 | 변경 없음 | — |

### 2. 권한 규칙 (API.md 2장) — `SpaceAccessPolicy`

규칙마다 허용·거부 둘 이상 (CONVENTIONS).

| ID | 내용 | 테스트 |
| --- | --- | --- |
| R-01 | 기념일 경로 5개(`GET /anniversaries` 3종은 요청자의 활성 공간 결과만, `PATCH`·`DELETE`는 활성 공간 기념일만). 다른 공간·없는 ID는 같은 404 `NOT_FOUND` | 허용: `R-01 요청자의 공간 기념일은 PATCH·DELETE가 된다` |
| | | 거부: `R-01 다른 커플 공간의 기념일은 PATCH·DELETE가 404 NOT_FOUND이고 아무것도 바뀌지 않는다` |
| | | 거부: `R-01 없는 기념일 ID와 다른 공간 기념일과 UUID 형식이 아닌 ID는 본문까지 같은 404다` (DB-05) |
| | | 거부: `R-01 해제된 이전 커플 공간의 기념일은 404다` |
| | | 거부: `R-01 GET anniversaries 세 경로는 다른 공간의 직접 기념일을 결과에 넣지 않는다` |
| | | 허용: `R-01 가져오기 대기 중인 개인 공간의 기념일은 커플 공간에서 안 보이고 가져온 뒤에는 보인다` |
| | | 허용/거부: `R-01 POST anniversaries는 요청자의 활성 공간에 만들어지고 본문의 spaceId는 무시된다` |
| R-05 | 직접 기념일 수정·삭제: 요청자 = `author_id` (`<확인 필요 5>` R-01 다음) | 허용: `R-05 작성자는 자기 기념일을 수정·삭제할 수 있다` |
| | | 거부: `R-05 작성자가 아니면 같은 공간이어도 PATCH·DELETE가 403 NOT_AUTHOR이다` |
| | | 거부: `R-05 거부되면 기념일이 하나도 바뀌지 않는다` |
| | | 허용: `R-05 작성자가 아니어도 조회는 된다` (R-05는 수정·삭제만) |
| | | 거부: `R-05 검사 순서: 다른 공간의 기념일은 403이 아니라 404다` |
| R-15 | `anniversaries.date` 1950-01-01 ~ 2050-12-31. 쓰기는 400 `DATE_OUT_OF_RANGE`, `GET /anniversaries`는 잘라서 조회 | 허용: `R-15 경계 날짜 1950-01-01과 2050-12-31은 직접 기념일로 저장된다` |
| | | 거부: `R-15 date가 1950-01-01보다 앞이면 POST anniversaries가 400 DATE_OUT_OF_RANGE (date OUT_OF_RANGE)다` |
| | | 거부: `R-15 date가 2050-12-31보다 뒤면 400 DATE_OUT_OF_RANGE다` |
| | | 거부: `R-15 PATCH anniversaries로 date를 범위 밖으로 바꾸면 400 DATE_OUT_OF_RANGE이고 기념일이 그대로다` |
| | | 허용: `R-15 GET anniversaries는 from이 1950-01-01보다 앞이어도 오류 없이 1950-01-01부터 조회한다` |
| | | 허용: `R-15 GET anniversaries는 to가 2050-12-31보다 뒤여도 2050-12-31까지 조회한다` (자동 N주년이 2050-12-31까지만 나옴을 함께 확인) |
| | | 허용: `R-15 잘라낸 조회 구간이 비면 빈 items를 돌려준다` |
| | | 거부: `R-15 from > to와 3개월 초과 검사는 잘라내기 전 값으로 먼저 한다` |
| | | 거부: `R-15 VALIDATION_FAILED가 하나라도 있으면 DATE_OUT_OF_RANGE는 같은 응답에 섞이지 않는다` (`<확인 필요 1>`) |

범위 밖 규칙: R-02·R-10·R-13(일정), R-03·R-04·R-06(메모, 단위 5), R-12·R-16(단위 6). R-08·R-09·R-18은 단위 2, R-11·R-14·R-17은 단위 1에서 끝났다.

### 3. API 경로

모든 경로는 `/api/v1` 아래. API.md 3.3 기념일 표는 `upcoming`·`past` 항목과 `totalCount`만 정의하고 나머지 모양은 `<확인 필요 1·2·4>`다.

| 메서드·경로 | 요청 | 성공 응답 | 오류 | 규칙 |
| --- | --- | --- | --- | --- |
| `GET /anniversaries?from=&to=` | `from`, `to` (`YYYY-MM-DD`, 3개월 이하) | 200 `{items}`. `kind` AUTO·CUSTOM 날짜순 (확인 필요 2) | 400 `VALIDATION_FAILED` | R-01, R-15, FR-ANNIV-01~04, FR-CAL-01 |
| `GET /anniversaries/upcoming?cursor=&size=` | 쿼리 | 200 `{items:[{anniversaryId, kind, name, date, daysLeft, repeatsYearly, authorId}], nextCursor}` | 400 (커서·size, 확인 필요 4) | R-01, FR-ANNIV-04 AC1·AC2·AC4, D-75 |
| `GET /anniversaries/past?cursor=&size=` | 쿼리 | 200 `{items:[{anniversaryId, name, date, authorId}], nextCursor, totalCount}` | 400 (같음) | R-01, FR-ANNIV-04 AC3, D-71 |
| `POST /anniversaries` | `{name, date, repeatsYearly?}` (확인 필요 1) | 201 `Anniversary` (확인 필요 1) | 400 `VALIDATION_FAILED`, 400 `DATE_OUT_OF_RANGE` | R-01, R-15, FR-ANNIV-03 |
| `PATCH /anniversaries/{id}` | 부분 수정 (확인 필요 1) | 200 `Anniversary` | 400 (위와 같음), 403 `NOT_AUTHOR`, 404 | R-01, R-05, R-15, FR-ANNIV-03 |
| `DELETE /anniversaries/{id}` | 없음 | 204 | 403 `NOT_AUTHOR`, 404 | R-01, R-05, FR-ANNIV-03 |

### 4. ERD 테이블과 제약

| 테이블 | 단위 4에서 하는 일 | 사용하는 제약·인덱스 |
| --- | --- | --- |
| anniversaries | 직접 기념일 생성·수정·삭제·조회. 범위 검사는 서비스 (DB-13). 가져오기 때 `space_id` 이동, 기록 유무·개수 확인 (단위 2 연결점) | V1 그대로: `ix_anniversaries_space(space_id)`, FK `space_id` CASCADE, `author_id` RESTRICT. `updated_at`은 수정 때 갱신. 새 마이그레이션 없음 (범위 조회는 공간별 행 수가 작아 인덱스 추가 없이 `space_id`로 읽고 메모리에서 발생일을 계산한다. 필요해지면 새 Flyway 파일) |
| spaces | `start_date`(읽기만), 종류(개인/커플) | — |
| users | 요청자 `time_zone`(오늘 판단: `upcoming`·`past`·`daysLeft`) | 읽기만. 쓰기 경로의 사전 잠금은 아래 5장 |

| ERD 수명주기 | 단위 4 |
| --- | --- |
| 4.1 커플 연결 때 개인 공간 처리 (anniversaries 존재 확인) | 기념일 몫: `countIn` |
| 4.2 가져오기 (`anniversaries.space_id` 이동, 4.4 9단계) | 기념일 서비스로 옮김 |
| 4.2 연결 해제 | 기념일은 보관 (지우지 않음, 복구 때 다시 보임). 기념일 알림 행은 단위 6 |
| 4.3 탈퇴 | 지우지 않는다 (`author_id` 유지, 익명화). 이월 없음 |
| 4.4 `purge_at` 지난 공간 삭제 (anniversaries CASCADE) | 단위 6 |

| DB- 결정 | 단위 4에서의 의미 |
| --- | --- |
| DB-01 | 자동 기념일은 저장하지 않는다. `spaces.start_date`로 조회마다 계산 |
| DB-05 | 다른 공간·없는 기념일은 같은 404 (R-01), 본문까지 같게 |
| DB-13 | `date` 범위는 서비스에서 검사, DB CHECK 없음 |
| DB-03 | 서버 1대. 기념일 알림 스케줄러는 단위 6 |

### 5. 동시성, 잠금, 구현 위험

**일정 쪽 잠금 규칙이 기념일 쓰기에 필요한가 (판단).** 일정의 사전 `FOR SHARE`(요청자·상대 사용자 행)는 두 가지 이유였다: ① 상대의 `PARTNER_CREATED` 알림 행, ② 연결 해제가 지우는 `event_reminders` 같은 파생 행이 해제 정리 뒤에 남는 경쟁 (unit-3.md 5장 위험 1). 기념일에는 상대 알림과 파생 행이 없다. 그러나 다른 경쟁이 하나 남는다.

| 흐름 | 잠금 | 이유 |
| --- | --- | --- |
| `POST /anniversaries` | 요청자 `users` 행 `FOR SHARE` → 활성 공간 다시 읽기 → 바뀌었으면 처음부터(`PartnerLockRetry` 방식 재사용 여부는 구현자가 `EventService.create`를 읽고 정한다. 상대 행이 필요 없어 더 단순하다). 공간 행은 잠그지 않는다 | **필요하다고 판단한다.** 커플 연결(`/couple/join`)은 `hasContent`로 개인 공간에 기록이 있는지 본 뒤 비어 있으면 그 개인 공간을 지운다. 기념일 `INSERT`가 R-01 통과 뒤 이 삭제와 겹치면 FK 위반(500)이거나 방금 만든 기념일이 CASCADE로 사라진다. 연결·해제·탈퇴가 사용자 행을 `FOR UPDATE`/`FOR NO KEY UPDATE`로 잡으므로 사전 `FOR SHARE`가 한 줄로 세운다. 일정 `POST`가 같은 이유로 이미 가진 보호다 |
| `PATCH`·`DELETE /anniversaries/{id}` | 그 기념일 행 `FOR UPDATE` → 잠근 값으로 R-01·R-05 다시 확인 → 쓰기 | 같은 기념일의 동시 수정·삭제를 한 줄로 세운다(마지막 저장이 이김). 일정과 달리 파생 행이 없어 사용자 행 사전 잠금은 필요 없다고 본다. 가져오기 `moveAll`이 `space_id`를 옮기는 중의 수정은 행 잠금이 직렬화한다 |
| `GET`들 | 잠금 없음, 읽기 전용 트랜잭션 | 자동 기념일은 `spaces.start_date`를 읽어 계산하므로 처음 만난 날 변경(`PATCH /space`)과 겹쳐도 한쪽 값으로 일관된다 |

- 잠금 순서(users UUID 오름차순 → invite_codes → spaces)를 지킨다. 공간 행을 잠근 채 사용자 행을 기다리지 않는다.
- 이 판단은 단위 3 기록과 문서만으로 한 것이다. 구현 전에 `EventService.create`·`UserService.lockShare`를 읽고 재확인하며, 달라야 하면 보고한다. 동시성 테스트는 `D-45`/`R-01 … 동시에 와도 …` 방식으로 하나 이상 둔다: `R-01 기념일을 만드는 요청과 커플 연결이 동시에 와도 기념일이 사라지거나 500이 나지 않는다`.

그 밖의 위험:

1. **오늘 판단은 요청자 시간대.** `upcoming`·`past`·`daysLeft`는 `users.time_zone`의 오늘(`Clock` 빈)이다. 잘못된 시간대는 UTC로 (`UserZones`, 단위 3 결정). 자동 기념일의 날짜 자체는 시간대와 무관한 `LocalDate`다. 테스트는 서울과 UTC 사용자로 같은 순간에 오늘이 다른 경우를 쓴다.
2. **발생일 계산 범위.** 반복 기념일·주년은 2050-12-31까지 최대 약 100개다. `upcoming`은 페이지가 있으므로 전체를 메모리에서 만들고 정렬한 뒤 커서 뒤부터 자른다(공간당 직접 기념일 수가 작다는 가정). 직접 기념일이 매우 많은 경우는 문서에 상한이 없다 — 상한이 필요한지는 Docs에 없다.
3. **의존 방향.** `couple` → `anniversary`(서비스만), `anniversary` → `space`·`user`(서비스만). `anniversary`가 `couple`을 알면 안 된다. `CoupleContentGateway`는 단위 5까지 `memos`만 남긴다.
4. **자동 기념일의 `start_date`는 커플 공간에만 있다.** `Space.startDate`는 `SpaceDetailService`가 커플일 때만 노출한다. 계산기는 공간 종류를 보고 개인 공간이면 자동을 만들지 않는다.

## 단위 6과의 경계 (지금 할 것과 미룰 것)

| 일 | 단위 | 근거 |
| --- | --- | --- |
| 직접 기념일 CRUD, 자동 기념일 계산, 목록 3종 | **4** | API 3.3, FR-ANNIV |
| `AnniversaryCreated`/`Updated`/`Deleted` 도메인 이벤트 발행(커밋 뒤 전달) | **4** (확인 필요 6) | 단위 3 선례, ARCHITECTURE 4장 |
| SYNC `ANNIVERSARY` 발송 | 6 | API 6장 |
| 매시 정각 기념일 알림 스케줄러, `ANNIVERSARY` 행·문구·푸시 | 6 (확인 필요 7) | API 7장 593행, PLAN 단위 6 |
| 기념일 계산기(특정 날짜의 기념일 구하기) | **4** (단위 6이 재사용) | 확인 필요 7 |

## 구현 1부 (문서로 이미 확정된 부분만, CI 확인 대기)

사용자가 확인 필요 1~8을 추천안대로 하기로 했고 Docs는 PRD v0.10 + `/prd-sync`로 반영 중이다. 머지 전에는 문서로 확정된 부분만 구현했다. 이 환경은 Gradle 배포판을 받지 못해(프록시 403) 컴파일·테스트를 돌리지 못했다. CI에서 확인한다.

### 만든 것

- `anniversary/domain`: `Anniversary`, `AnniversaryRepository`
- `anniversary/application`:
  - `AnniversaryCalculator`(순수 계산: 자동 기념일 100일 단위·매년 N주년, 직접 기념일 발생일, 1950~2050 범위), `AutoAnniversary`·`AutoAnniversaryType`
  - `AnniversaryContentService`(`countIn`, `moveAll`, `EventContentService`와 같은 모양)
  - `AnniversaryQueryService`(`upcoming`, `past`: 전체 목록. 커서·`size` 없음)
  - `AnniversaryService`(`delete`, POST용 `inLockedActiveSpace`), `AnniversaryLocker`, `AnniversaryDeleted`
- `anniversary/api/AnniversaryController`: **`DELETE /anniversaries/{id}`만**
- `common/access/SpaceAccessPolicy.requireAnniversaryAuthor` (R-05)
- `couple/application/CoupleContentGateway`: 기념일 SQL 3곳(`hasContent`·`countsOf`·`moveAll`)을 `AnniversaryContentService` 호출로 바꿨다. 생성자에 `AnniversaryContentService`가 더해졌다(호출부는 Spring 주입뿐이라 고칠 곳 없음). 해제·가져오기 동작은 그대로
- `UserZones`를 `event/application`에서 `common/time`으로 옮겼다 (일정·기념일이 함께 쓴다). 쓰는 곳 `EventService`, `EventReminderService`의 import를 고쳤다

### 테스트 (`src/test/kotlin/com/duriseo/server/`)

- `anniversary/AnniversaryCalculatorTest`(Spring 없음): FR-ANNIV-02 AC1·AC2, FR-ANNIV-03 AC1·AC3, FR-ANNIV-04 AC1, R-15 (12개)
- `anniversary/AnniversaryQueryTest`: FR-ANNIV-02 AC3, FR-ANNIV-04 AC1·AC2·AC3·AC4, R-01(목록)
- `anniversary/AnniversaryPermissionTest`: R-01(DELETE 허용·거부·해제된 공간·본문까지 같은 404), R-05(허용·거부·거부 시 불변·검사 순서), FR-ANNIV-03 AC2, FR-ANNIV-02 AC3(자동은 가리킬 ID 없음), POST 사전 잠금 블록
- `anniversary/AnniversaryTestSupport`: 도구. `support/IntegrationTestSupport`: `insertAnniversary`에 날짜·반복 여부 인자, `deleteAnniversary`
- `couple/CoupleJoinTest`: `FR-COUPLE-03 AC1 기념일만 있는 개인 공간도 기록이 있는 공간으로 본다`. 이월분(`CoupleImportTest`, `SpaceLinkedAtTest`)은 기존 테스트가 그대로 덮는다

### 1부에서 정한 것 (문서에 없는 구현 선택)

- **`POST /anniversaries` 사전 잠금은 필요하다. 다만 일정 방식 그대로가 아니라 더 단순하다.** `EventService.create`는 상대의 `PARTNER_CREATED` 행 때문에 상대 사용자 행도 잡아야 해서, 상대를 잠금 없이 먼저 읽고 잠근 뒤 바뀌었는지 보는 `PartnerLockRetry` 재시도가 들어 있다. 기념일은 상대 알림이 없어 요청자 사용자 행 하나만 `lockShare`한 뒤 활성 공간을 읽으면 된다(READ COMMITTED에서 잠금을 얻은 뒤 읽은 값이 최신). 그래서 `PartnerLockRetry`는 쓰지 않는다. `AnniversaryService.inLockedActiveSpace`가 이것을 하고 POST가 들어올 때 부른다. 동시성 테스트(`R-01 기념일을 만드는 요청과 커플 연결이 동시에 와도 …`)는 POST와 함께 만든다
- `PATCH`·`DELETE`는 기념일 행 `FOR UPDATE`(`AnniversaryLocker`) 후 R-01·R-05를 다시 확인한다. 사용자 행 사전 잠금은 하지 않는다 (위 5장 판단 그대로)
- `DELETE`는 `AnniversaryDeleted`를 같은 트랜잭션에서 발행한다. 듣는 쪽은 단위 6. 이벤트가 커밋 뒤에만 전달되는지 보는 테스트는 `Created`/`Updated`와 함께 POST·PATCH 때 더한다 (기념일용 기록 도구가 아직 없다)
- `upcoming` 정렬: 날짜 → 자동 먼저 → 자동은 일수 먼저·횟수 → 직접은 ID 문자열. `past` 정렬: 날짜 내림차순 → ID 내림차순. 이 같은 날 순서와 커서 모양은 확인 필요 3·4가 정하면 바꾼다
- 자동 기념일 N주년은 `LocalDate.plusYears`를 쓴다(2월 29일 시작이면 비윤년 2월 28일). 확인 필요 3의 추천안과 같지만 Docs에 올라가기 전이다. 바꿀 곳은 `AnniversaryCalculator.yearsLater` 한 곳

## 구현 2부 (PRD v0.10, API.md 3.3 기념일 그대로)

확인 필요 1~5가 Docs에 올라가 있어 비어 있던 곳을 문서대로 채웠다. 이 환경은 Gradle·Docker가 없어 CI로만 확인한다.

### 만든 것

- `anniversary/api/AnniversaryController`: `GET /anniversaries`, `GET /anniversaries/upcoming`, `GET /anniversaries/past`, `POST /anniversaries`(201), `PATCH /anniversaries/{id}`(200)를 더했다 (DELETE는 1부). 쿼리·본문 검사는 서비스가 하도록 `String?`·`Map`으로 받는다
- `anniversary/api/AnniversaryResponse`: `Anniversary`(`updatedBy` 없음), `AnniversaryOccurrence`(값이 없는 필드도 `null`), `past` 항목·`{items, nextCursor, totalCount}`
- `anniversary/application`:
  - `AnniversaryWriteRules`(쓰기 규칙: 이름 trim·30자·`REQUIRED`/`TOO_LONG`, 날짜 `INVALID`, `repeatsYearly` 타입, `VALIDATION_FAILED` 먼저 → `DATE_OUT_OF_RANGE`)
  - `AnniversaryService.create`(`inLockedActiveSpace` 안에서 검사·저장, `AnniversaryCreated` 발행), `update`(`AnniversaryLocker` → R-05 → 값 검사, `AnniversaryUpdated` 발행)
  - `AnniversaryQueryService`: `listBetween`(캘린더용, 일정 조회 규칙과 같은 검사 → 1950~2050 잘라내기), `upcomingPage`, `past`(커서·`size`·`totalCount`)
  - `AnniversaryCursor`: `cursor`·`size` 검사(기본 20, 1~100, `size`/`cursor` `INVALID`)와 커서 인코딩
  - `AnniversaryCreated`, `AnniversaryUpdated`, `AnniversaryView`
- `AutoAnniversary.name`("100일"~"1000일", "1주년"…, D-105)과 `rank`(일수 0, 주년 1, 직접 2)
- `AnniversaryRepository`: `findPast`를 `findPastFirstPage`·`findPastAfter`(네이티브, PostgreSQL `uuid` 비교)와 `countPast`로 바꿨다

### 테스트 (`src/test/kotlin/com/duriseo/server/`)

- `anniversary/AnniversaryWriteTest`(29개): POST(FR-ANNIV-03 AC1, D-104 이름 trim·공백만·30/31자·이모지, 필수·형식·`repeatsYearly`, R-15 경계·값 오류 우선, 모르는 필드 무시, R-01 활성 공간), PATCH(부분 수정, `null`→`REQUIRED`, R-15, 같은 값이면 무변경·이벤트 없음, R-05 403, R-01 같은 404 본문, 404→403→400 순서, 해제된 공간), 이벤트 커밋 뒤 전달(`NFR-SYNC-01` 생성·수정·삭제, 거부된 요청은 이벤트 없음), 동시성 `R-01 기념일을 만드는 요청과 커플 연결이 동시에 와도 기념일이 사라지거나 500이 나지 않는다`
- `anniversary/AnniversaryListTest`(27개): 캘린더용(모양·`daysLeft` 음수·반복·R-01·AUTO 없음 AC2·AC4·`REQUIRED`/`INVALID`/3개월 오류·R-15 잘라내기·빈 구간), D-105 이름, FR-ANNIV-02 AC5 2월 29일, FR-ANNIV-04 AC5 같은 날 순서(부호 없는 uuid), `upcoming` 기본 20개·페이지 이어 붙이기(여러 `size`)·같은 날이 경계에 걸림·커서 항목 삭제·항목 모양, `past` 모양·빈 목록·페이지 이어 붙이기·같은 날·R-01, 잘못된 `size`·`cursor`(다른 경로의 커서 포함)
- `support`: `AnniversaryChangeRecorder`(커밋 뒤 전달), `IntegrationTestSupport`의 기념일 요청 도구와 `insertAnniversary(id=)`
- 1부 테스트는 `UpcomingAnniversary`→`AnniversaryOccurrence`로 이름이 바뀐 것 외에 그대로다 (`AnniversaryQueryTest`는 전체 목록 `upcoming(userId)`와 `past` 첫 페이지를 부른다)

### 2부에서 정한 것 (문서에 없는 구현 선택)

- **`PATCH`가 저장된 값과 같은 값만 보내면 아무것도 바꾸지 않는다** (`updated_at` 그대로, `AnniversaryUpdated` 없음). API.md 3.3 기념일은 이 경우를 적지 않았다. 일정 `PATCH`와 같은 방식을 썼다. 다르게 하려면 `AnniversaryService.update` 한 곳이다
- **커서 모양**: `base64url("u|<date>|<순번>|<id 또는 빈 값>")`(upcoming), `base64url("p|<date>|<id>")`(past). 문서는 "불투명"이라고만 한다. 다른 경로의 커서나 모양이 틀린 커서는 `cursor` `INVALID`다. 한 요청에서 `size`와 `cursor`가 모두 틀리면 둘 다 담는다(`size` 먼저)
- **`upcoming`은 전체를 메모리에서 만들고 커서 뒤부터 자른다.** 커서는 키 비교라 커서가 가리키던 항목이 지워져도 이어진다. 직접 기념일이 매우 많을 때의 상한은 문서에 없다
- **uuid 비교는 부호 없는 바이트 순**이다 (API.md 3.3 리드 보충). 메모리 정렬은 `compareIds`(`Long.compareUnsigned`), `past`는 PostgreSQL이 비교한다. 1부는 `UUID.toString()` 문자열 순서와 JPQL `order by a.id`였는데 같은 결과지만 이제 문서의 근거를 따른다
- **캘린더용 쿼리 검사는 `EventService.parseRange`와 같은 코드를 복사**했다(일정 조회 규칙과 같다는 문서 문장대로). 공통으로 빼는 것은 이번 범위 밖이다
- 1부의 "`upcoming` 정렬: 날짜 → 자동 먼저 → …" 선택은 문서(Q-15 기본값, D-106)와 같아서 바뀐 것은 이름뿐이다. 자동 기념일 `name`은 null이 아니라 D-105 문자열이다. 2월 29일 주년(`yearsLater`)은 1부 구현이 FR-ANNIV-02 AC5와 같아 코드는 그대로다 (주석만 고침)

### 문서와 다르거나 비어 있던 곳

- 문서와 1부 구현의 차이는 위 `name`(1부는 null)뿐이었다. 문서가 모순이거나 비어서 멈춘 곳은 없다.
- API.md 3.3은 `PATCH`의 "변경 없음" 처리, 커서 내부 모양, `upcoming`의 직접 기념일 개수 상한을 적지 않았다 (위 구현 선택).

## 결정

구현하면서 정한 것 중 문서에 없는 것. 문서에 있어야 할 결정이면 여기 적지 말고 작업을 멈추고 보고한다. (승인 전 계획. 1부에서 정한 것은 위 "구현 1부")

- 패키지: `anniversary/api`(컨트롤러, DTO), `anniversary/application`(`AnniversaryService`, `AnniversaryQueryService`, `AnniversaryCalculator`, `AnniversaryContentService`, 도메인 이벤트), `anniversary/domain`(`Anniversary`, `AnniversaryRepository`).
- 요청 본문은 단위 2·3과 같이 `@RequestBody Map`으로 받아 "필드 없음"과 `null`을 구분한다. `SpaceAccessPolicy`에는 `requireAnniversaryAuthor(authorId)`(R-05)만 더한다. R-01은 기존 `requireInActiveSpace`.
- 테스트 픽스처: `IntegrationTestSupport.insertAnniversary`(469행 근처, 날짜가 2026-10-27로 고정)를 날짜·반복 여부·작성자를 받게 넓힌다.

## 리뷰 기록

| 회차 | Critical | Major | 비고 |
| --- | --- | --- | --- |
| 1회차 Sonnet | 0 | 1 | Critical 0, Major 1, Minor 5 → Major·Minor 1·2·5 수정 |

## 남은 일

- CI 초록불 확인, 사람 리뷰와 머지 (Gradle·Docker가 없는 환경이라 로컬 실행은 못 했다)
- 단위 5: `CoupleContentGateway`의 `memos` SQL과 `WithdrawalDataEraser`의 `memo_favorites` 삭제를 옮기고 게이트웨이를 없앤다
- 단위 6: 기념일 알림 스케줄러, SYNC `ANNIVERSARY`, 도메인 이벤트 리스너
- Docs 후속은 없다 (확인 필요 1~4는 v0.10에 반영됨). 위 "2부에서 정한 것" 중 `PATCH` 무변경 처리·`upcoming` 개수 상한은 문서에 없으니 필요하면 `/prd-sync`로 정한다
