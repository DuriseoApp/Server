# 단위 5: 메모 — 메모, 날짜, 고정, 즐겨찾기 (memo)

> 브랜치: `feat/5-memo` · PR: (링크) · 상태: 진행 중 (2026-11-08 범위 승인, 확인 필요 1~7 추천안대로)
> 기준: PRD v0.12 (6.5 FR-MEMO, 7장, 8장, D-17·D-18·D-21·D-24·D-38·D-123·D-130~D-135), API.md 1장·1.1·2장(R-01·R-03·R-04·R-06·R-15)·3.3 "커서 정렬 키"·3.4·5장·6장·7장 "상대 활동 알림", ERD 3장(memos, memo_favorites, notifications)·4.1·4.3
> Docs 기준 커밋: DuriseoApp/Docs main `cee0885` (PR #17 머지 뒤)
> 앞 단위: 단위 4 머지됨 (main `2808e1b`, 개발 서버 배포)

## 요약

- 새 API 경로 **11개** (API.md 3.4): `GET /memos`, `GET /memos/pinned`, `GET /memos/dated`, `POST /memos`, `GET /memos/{id}`, `PATCH /memos/{id}`, `DELETE /memos/{id}`, `PUT·DELETE /memos/{id}/pin`, `PUT·DELETE /memos/{id}/favorite`. 체크리스트 경로는 삭제됨(만들지 않음)
- FR **5개**: FR-MEMO-01·02·03·05·06 (04는 삭제됨). 함께: FR-CAL-01 AC2·AC6(메모 몫), FR-NOTI-04(메모 몫, 알림 행), FR-COUPLE-03 AC1·AC2(메모 몫, 이월)
- R- 규칙: R-01, R-03, R-04, R-06, R-15
- 새 `ErrorCode` 없음. **새 마이그레이션 없음** (V1에 `memos`, `memo_favorites`, 인덱스 3개 있음)
- 알림 행: `PARTNER_CREATED`(메모), `MEMO_PINNED`를 이 단위에서 만든다(단위 3 선례). 푸시·SYNC 발송은 단위 6
- 도메인 이벤트: `MemoCreated`·`MemoUpdated`(수정·고정·해제)·`MemoDeleted`를 발행만 한다(단위 3·4 선례, 즐겨찾기는 없음 D-134)

## 확인 필요

문서에 없거나 문장만으로는 정해지지 않는 곳이다. 추천안은 기존 규칙(일정 쓰기 규칙 등)을 그대로 따른다.

| # | 빈 곳 | 추천안 | 근거 |
| --- | --- | --- | --- |
| 1 | `POST`·`PATCH /memos`의 필드 타입·`null` 규칙 (3.4는 제목·본문 공백 규칙만 적음) | 일정 쓰기 규칙과 같게: `title`·`body`가 문자열이 아니면 `INVALID`, `POST`에서 `null`은 생략과 같음(빈 값). `memoDate`가 `YYYY-MM-DD`가 아니면 (`memoDate`, `INVALID`). `notifyPartner` 생략 true, `null`은 `REQUIRED`, boolean 아니면 `INVALID`. `PATCH`는 `notifyPartner` 무시(수정은 알리지 않음, FR-NOTI-04 AC3). 모르는 필드 무시 | API.md 3.3 "쓰기 규칙" |
| 2 | 공백뿐인 본문이 5,000자를 넘을 때 | 빈 값(`null`)으로 보고 `TOO_LONG`을 내지 않는다(공백 처리 뒤 길이 검사). 공백 기준은 일정 제목과 같은 `trim` | 제목 "길이는 지운 뒤 센다"와 같은 방향 |
| 3 | 고정·해제가 `updated_at`을 바꾸는지 | 바꾸지 않는다. 고정은 내용 수정이 아니다. SYNC용 `MemoUpdated`는 발행한다 | D-21 "고정은 내용 수정이 아니라 공지 지정", D-134 |
| 4 | `GET /memos`의 `view`가 모르는 값일 때, 다른 `view`의 커서를 보낼 때 | (`view`, `INVALID`) 400. 커서에 `view`를 담고 다르면 (`cursor`, `INVALID`) | 1.1 "서버가 읽지 못하면 `cursor` `INVALID`" |
| 5 | `GET /memos/dated`의 `from`·`to` 오류 필드 | 일정 조회 규칙과 같게: 없으면 `REQUIRED`, 형식 `INVALID`, `from` > `to`·3개월 초과(`from.plusMonths(3)`)는 (`to`, `INVALID`) | API.md 3.3 "조회 규칙" |
| 6 | 알림 "본문 첫 줄"(D-131)에서 고른 줄의 뒤쪽 공백 | 고른 줄도 앞뒤 공백을 지운 뒤 50 코드 포인트 규칙(넘으면 49자 + "…")을 쓴다 | D-131은 본문 전체의 앞뒤 공백만 말함 |
| 7 | 기록 가져오기 때 개인 공간의 고정 메모 | `pinned_at`·`pinned_by` 그대로 옮긴다(커플 공간 고정 영역에 보임, 알림 없음) | API.md 4.4 9단계 "`space_id`만 바꾼다" |

## 범위

JVM 메서드 이름에 `/`를 못 쓰므로 경로는 `GET memos pinned`처럼 적는다 (단위 1 결정). 테스트 클래스(`memo/` 아래): `MemoWriteTest`, `MemoListTest`, `MemoPinTest`, `MemoFavoriteTest`, `MemoPermissionTest`, `MemoDateRangeTest`, `MemoPartnerNotificationTest`, `MemoFirstLineTest`(순수 단위).

### 1. 기능 (FR)과 수용 기준

**FR-MEMO-01 메모 목록** — `GET /memos?view=&cursor=&size=`

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-MEMO-01 AC1 | 고정되지 않은 메모가 작성일 최신순 (`view` 기본 `newest`) | `FR-MEMO-01 AC1 GET memos는 고정되지 않은 메모를 created_at 최신순으로 준다` |
| FR-MEMO-01 AC2 | `oldest`는 오래된순, `favorites`는 내 즐겨찾기만 최신순, 고정 메모는 어느 보기에도 없음 (D-130) | `FR-MEMO-01 AC2 view oldest는 created_at 오래된순이다` |
| | | `FR-MEMO-01 AC2 view favorites는 내가 즐겨찾기한 고정되지 않은 메모만 최신순이다` |
| | | `FR-MEMO-01 AC2 고정된 메모는 newest oldest favorites 어디에도 나오지 않는다` |
| | `totalCount`(요청한 보기, 고정 제외), `favoriteCount`(내 즐겨찾기 중 고정 제외) | `FR-MEMO-01 AC2 totalCount는 view별 고정 제외 개수이고 favoriteCount는 고정 제외 내 즐겨찾기 개수다` |
| | 커서 (`created_at`, `id`): newest·favorites는 작은 것, oldest는 큰 것. 같은 `created_at`도 빠지거나 겹치지 않음 | `FR-MEMO-01 AC2 같은 created_at 메모가 페이지 경계에 걸려도 빠지거나 겹치지 않는다` (보기 3개 각각) |
| | `size` 1~100 기본 20, 잘못된 커서·`size`·`view` | `FR-MEMO-01 AC2 size가 범위 밖이거나 cursor를 읽지 못하면 400 VALIDATION_FAILED` · `FR-MEMO-01 AC2 view가 모르는 값이면 400 VALIDATION_FAILED (view INVALID)` `<확인 필요 4>` |
| FR-MEMO-01 AC3 | 항목에 제목·작성자·작성일·메모 날짜 (`Memo` 응답 모양) | `FR-MEMO-01 AC3 Memo 응답은 id title body memoDate authorId isPinned pinnedAt isFavorite createdAt updatedAt을 null 포함해 준다` |

**FR-MEMO-02 메모 작성** — `POST /memos`

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-MEMO-02 AC1 | 제목·본문 중 하나 이상이면 현재 공간에 생성 (개인·커플) | `FR-MEMO-02 AC1 제목만 있어도 본문만 있어도 201이고 공간·작성자가 요청자 기준이다` |
| FR-MEMO-02 AC2 | 둘 다 비면 400 (`title`·`body` `REQUIRED`), 제목 trim 저장, 본문 공백 보존·공백뿐이면 null (D-132) | `FR-MEMO-02 AC2 제목과 본문이 모두 비거나 공백뿐이면 400 VALIDATION_FAILED (title body REQUIRED)` |
| | | `FR-MEMO-02 AC2 제목은 앞뒤 공백을 지워 저장하고 본문은 공백과 줄바꿈을 그대로 저장한다` |
| | | `FR-MEMO-02 AC2 공백뿐인 본문은 null로 저장한다` |
| | 길이: 제목 trim 뒤 50, 본문 5000 코드 포인트 (NFR-LIMIT-01, D-82) | `FR-MEMO-02 AC2 제목 51자나 본문 5001자는 TOO_LONG이고 코드 포인트로 센다` `<확인 필요 2>` |
| | 필드 타입·`null` | `FR-MEMO-02 AC2 필드 타입이 틀리면 INVALID이고 notifyPartner null은 REQUIRED다` `<확인 필요 1>` |
| FR-MEMO-02 AC3 | 메모 날짜 선택(생략·null = 없음) | `FR-MEMO-02 AC3 memoDate를 생략하면 null이고 YYYY-MM-DD가 아니면 400 (memoDate INVALID)` |
| FR-MEMO-02 AC4 | 날짜 메모는 캘린더에도 (`GET /memos/dated`) | 아래 FR-CAL-01 AC2 |
| FR-MEMO-02 AC5 | 탭 전환·입력 제한 | 앱 동작 (서버 몫은 AC2 길이 검사) |

**FR-MEMO-03 메모 수정·삭제** — `GET`·`PATCH`·`DELETE /memos/{id}`

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-MEMO-03 AC1 | 작성자는 제목·본문·날짜 수정, 삭제 | `FR-MEMO-03 AC1 작성자는 PATCH로 제목 본문 memoDate를 바꾸고 null로 지울 수 있다` |
| | 부분 수정, 결과가 둘 다 비면 `REQUIRED` | `FR-MEMO-03 AC1 PATCH 결과가 제목 본문 둘 다 비면 400 VALIDATION_FAILED이고 바뀌지 않는다` |
| | | `FR-MEMO-03 AC1 작성자는 메모를 삭제할 수 있고 즐겨찾기 행도 함께 지워진다 (204)` |
| | 상세는 요청자 기준 `isFavorite` | `FR-MEMO-03 AC1 GET memos id는 요청자 기준 isFavorite을 준다` |
| FR-MEMO-03 AC2 | 상대 메모 수정·삭제 거부 (R-03) | R-03 거부 테스트 |
| FR-MEMO-03 AC3 | 없으면 404, 권한 없으면 403 | R-01·R-03 테스트 (앱 처리는 앱 몫) |
| FR-MEMO-03 AC4 | 같은 값만 보내면 200 + 현재 값, `updated_at` 유지, 이벤트 없음 (D-123) | `FR-MEMO-03 AC4 저장값과 같은 값만 보내면 updated_at이 그대로이고 MemoUpdated가 없다` |
| | 검사 순서 R-01 → R-03 → 값 → 날짜 범위 | `FR-MEMO-03 AC1 PATCH 검사 순서는 NOT_FOUND NOT_AUTHOR VALIDATION_FAILED DATE_OUT_OF_RANGE다` |

**FR-MEMO-05 고정 핀** — `GET /memos/pinned`, `PUT·DELETE /memos/{id}/pin`

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-MEMO-05 AC1 | 누구든 아무 메모나 고정·해제, 개수 제한 없음 (R-04) | `FR-MEMO-05 AC1 상대가 만든 메모도 고정하고 해제할 수 있다` |
| FR-MEMO-05 AC2·AC3 | 고정 전체를 `pinned_at` 내림차순 → `id` 내림차순 | `FR-MEMO-05 AC3 GET memos pinned는 고정 메모 전체를 최근 고정순으로 준다` |
| FR-MEMO-05 AC4 | 일반 목록에 중복 없음 | FR-MEMO-01 AC2 테스트 |
| FR-MEMO-05 AC5 | 멱등: 다시 고정해도 `pinned_at`·`pinned_by`·알림 그대로, 해제도 멱등 (D-134) | `FR-MEMO-05 AC5 이미 고정된 메모를 다시 고정하면 204이고 pinned_at pinned_by가 그대로이며 알림이 없다` |
| | | `FR-MEMO-05 AC5 고정되지 않은 메모를 해제해도 204이고 아무것도 바뀌지 않는다` |
| | 고정 → 해제 → 고정은 새 `pinned_at` | `FR-MEMO-05 AC5 해제 뒤 다시 고정하면 pinned_at이 새 시각이다` |
| | 고정·해제는 `updated_at`을 바꾸지 않음 `<확인 필요 3>` | `FR-MEMO-05 AC1 고정과 해제는 updated_at을 바꾸지 않는다` |
| | 동시 고정 두 건은 한 번만 전환 | `FR-MEMO-05 AC5 두 사람이 동시에 고정해도 고정 시각과 알림은 하나다` |

**FR-MEMO-06 즐겨찾기** — `PUT·DELETE /memos/{id}/favorite`

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-MEMO-06 AC1 | 내 즐겨찾기는 나에게만 보임 (R-06) | `FR-MEMO-06 AC1 A가 즐겨찾기한 메모는 B의 응답에서 isFavorite false이고 B의 favorites 보기에 없다` |
| FR-MEMO-06 AC2 | 멱등, 이벤트 없음 (D-134) | `FR-MEMO-06 AC2 이미 즐겨찾기한 메모를 다시 즐겨찾기하거나 없는 것을 해제해도 204이고 이벤트가 없다` |

**함께 다루는 FR**

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-CAL-01 AC2 | `GET /memos/dated`: 범위(최대 3개월)에 `memoDate`가 있는 메모, `memo_date` ↑ → `created_at` ↓ → `id` ↓, 고정 여부와 관계없음 | `FR-CAL-01 AC2 GET memos dated는 범위 안 날짜 메모를 memo_date 오름차순으로 준다` |
| | 오류 필드 `<확인 필요 5>` | `FR-CAL-01 AC2 GET memos dated의 from to가 없거나 틀리거나 3개월을 넘으면 400 VALIDATION_FAILED` |
| FR-CAL-01 AC6 | 쓰기 `memoDate` 범위 밖은 `DATE_OUT_OF_RANGE`, 조회는 잘라내기 (R-15) | R-15 테스트 |
| FR-NOTI-04 (메모) | 커플 공간 + `notifyPartner` true면 상대에게 `PARTNER_CREATED` 행: 제목 "[작성자]님이 메모를 남겼어요", 본문 [메모 제목 또는 첫 줄], `target_type` MEMO, `target_id`, `space_id` | `FR-NOTI-04 AC2 커플 공간에서 notifyPartner 켜고 메모를 만들면 상대에게 PARTNER_CREATED 행이 생긴다` |
| | 끄면·개인 공간이면·수정이면 없음 | `FR-NOTI-04 AC2 notifyPartner false나 개인 공간이면 알림 행이 없다` · `FR-NOTI-04 AC3 메모 수정은 알림 행을 만들지 않는다` |
| PRD 8장 메모 고정 (D-135) | 미고정 → 고정일 때마다 고정하지 않은 쪽에 `MEMO_PINNED` 행: 제목 "[고정한 사람]님이 메모를 고정했어요", 본문 [메모 제목 또는 첫 줄]. 자기 메모도, 반복도 매번. 해제·개인 공간은 없음 | `D-135 메모를 고정하면 고정하지 않은 쪽에 MEMO_PINNED 행이 생기고 고정 해제 고정을 반복하면 매번 생긴다` · `D-135 개인 공간 고정과 해제는 알림 행이 없다` |
| D-131 첫 줄 | 본문 trim → 첫 비어 있지 않은 줄 → 50 코드 포인트, 넘으면 49 + "…" `<확인 필요 6>` | `D-131 제목이 없으면 본문 첫 비어 있지 않은 줄을 쓰고 50자를 넘으면 49자와 말줄임표다` |
| FR-COUPLE-03 AC1·AC2 (이월) | 메모 개수·옮기기를 메모 서비스로 (`ImportCounts.memos`) `<확인 필요 7>` | 기존 `CoupleImportTest`가 그대로 통과 + `FR-COUPLE-03 AC1 가져오기는 메모와 즐겨찾기 고정 상태를 그대로 옮긴다` |

### 2. 권한 (R-)

| ID | 내용 | 테스트 |
| --- | --- | --- |
| R-01 | 모든 메모 경로: 다른 공간·없는 ID는 404, 목록은 활성 공간만 | 허용: `R-01 같은 공간 메모는 상세 수정 삭제 고정 즐겨찾기를 할 수 있다` / 거부: `R-01 다른 공간 메모는 GET PATCH DELETE pin favorite 모두 404이고 바뀌지 않는다` · `R-01 목록 pinned dated는 다른 공간 메모를 넣지 않는다` · `R-01 해제한 공간의 메모는 404다` |
| R-03 | 제목·본문·날짜 수정, 삭제는 작성자만 (`SpaceAccessPolicy` 새 메서드) | 허용: FR-MEMO-03 AC1 / 거부: `R-03 상대 메모를 PATCH하거나 DELETE하면 403 NOT_AUTHOR이고 바뀌지 않는다` |
| R-04 | 고정·해제는 구성원이면 허용 (R-01만) | 허용: FR-MEMO-05 AC1 / 거부: R-01 거부 테스트 |
| R-06 | 즐겨찾기는 본인 행만, 응답에 남의 즐겨찾기 없음 | 허용: FR-MEMO-06 AC2 / 거부: FR-MEMO-06 AC1 (상대 즐겨찾기가 `isFavorite`·`favoriteCount`·`favorites`에 안 섞임) |
| R-15 | `memoDate` 1950-01-01 ~ 2050-12-31 (쓰기 400 `DATE_OUT_OF_RANGE`), `dated` 조회는 잘라내기 | 거부: `R-15 memoDate가 범위 밖이면 POST PATCH 모두 400 DATE_OUT_OF_RANGE` · `R-15 값 오류와 범위 밖이 같이 있으면 VALIDATION_FAILED만 준다` / 허용: `R-15 dated 조회의 from to가 범위 밖이면 잘라서 조회한다` |

### 3. 앞 단위에서 넘어온 일

| 할 일 | 확인 |
| --- | --- |
| `CoupleContentGateway`의 `memos` 네이티브 SQL 3곳(`hasContent`·`countsOf`·`moveAll`)을 메모 도메인 서비스(`MemoContentService`)로 옮기고 클래스를 없앤다. 부르는 곳(`CoupleLinker`·`CoupleImportService`·`CoupleUnlinkService`·`MeService`)은 각 도메인 서비스를 직접 부른다. ARCHITECTURE 2장 임시 예외 문장을 지운다 | 기존 커플·내 정보 테스트가 그대로 통과, `grep CoupleContentGateway` 없음 |
| `WithdrawalDataEraser`의 `memo_favorites` 삭제를 메모 서비스로 옮긴다 | 기존 탈퇴 테스트 + `ERD 4.3 탈퇴하면 그 사용자의 즐겨찾기가 지워지고 메모는 남는다` |

### 4. 구현 메모 (문서에 있는 규칙의 구현 방식)

- 잠금: `POST /memos`는 일정·기념일과 같이 요청자 사용자 행 `FOR SHARE`(`UserService.lockShare`) 뒤 R-01. `PATCH`·`DELETE`·고정·해제는 메모 행 `FOR UPDATE` 후 재검사(기념일 PATCH·DELETE와 같음). 즐겨찾기 추가는 `INSERT … ON CONFLICT DO NOTHING`
- 이벤트: `MemoCreated`·`MemoUpdated`·`MemoDeleted`는 트랜잭션 안에서 발행, 듣는 쪽은 단위 6. 같은 값 `PATCH`, 멱등으로 아무것도 안 바뀐 고정·해제, 즐겨찾기는 발행하지 않는다
- 날짜 범위 조회 검사·잘라내기는 일정 조회와 같은 코드를 쓴다

## 결정

구현하면서 정한 것 중 문서에 없는 것. 문서에 있어야 할 결정이면 여기 적지 말고 작업을 멈추고 보고한다.

- 고정(`PUT /memos/{id}/pin`)은 알림 행을 만들 수 있어 `POST`와 같이 요청자 사용자 행을 `FOR SHARE`로 먼저 잡은 뒤 메모 행을 `FOR UPDATE`로 잠근다(users → 콘텐츠 행). 즐겨찾기 추가(PUT)도 같은 순서다: 탈퇴가 `memo_favorites`를 지운 뒤 커밋되는 추가가 탈퇴 사용자의 행을 남기지 않게 한다. 고정 해제·즐겨찾기 해제·`PATCH`·`DELETE`는 메모 행만 잠근다. 즐겨찾기는 메모 행도 잠근다: 삭제·이동과 겹쳐 FK 위반(500)이 나지 않게 한다
- `POST /memos`는 상대 사용자 행을 잡지 않는다(일정과 다름). 해제·탈퇴가 두 사람의 사용자 행을 모두 잠그므로 요청자 행 하나로 한 줄이 서고, 잠근 뒤 상대를 읽으므로 `PartnerLockRetry`가 필요 없다
- 커서 내용: `view|created_at(ISO 8601, 마이크로초까지)|id`를 base64url로 감쌌다. `view`가 다르면 `cursor` `INVALID` (확인 필요 4)
- `PATCH`/`POST`에서 "제목·본문 둘 다 비면 `REQUIRED`"는 제목·본문 값 자체에 오류(`INVALID`, `TOO_LONG`)가 없을 때만 담는다(같은 필드에 두 이유를 담지 않으려는 것)
- `MemoUpdated`에 `partnerUserId`·`notificationId`(없으면 null)를 실어 단위 6이 `MEMO_PINNED` 푸시와 `NOTIFICATION` 신호를 보낼 수 있게 했다 (`MemoCreated`가 `PARTNER_CREATED`에 하는 것과 같다)
- 날짜 범위 조회 검사·잘라내기는 `common/api/DateRangeQuery`로 뽑아 `GET /memos/dated`가 쓴다. `GET /events`·`GET /anniversaries`는 같은 규칙의 자기 복사본을 그대로 둔다(이 단위에서 건드리지 않음)
- 알림 본문 "제목 또는 첫 줄"은 `NotificationTexts.memoLine`(순수 함수)이다. 제목도 본문도 없으면 null이지만 저장된 메모에서는 나오지 않는다
- 메모 이벤트의 `MemoChangeRecorder`(테스트 지원)를 `FakeCouple.kt`에 추가했다

## 리뷰 기록

| 회차 | Critical | Major | 비고 |
| --- | --- | --- | --- |

## 남은 일

- 단위 6으로: `MemoCreated`·`MemoUpdated`·`MemoDeleted`를 듣는 SYNC `MEMO`, `PARTNER_CREATED`(메모)·`MEMO_PINNED` 푸시와 `NOTIFICATION` 신호
