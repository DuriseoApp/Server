# 단위 2: 커플 (couple)

> 브랜치: `feat/2-couple` · PR: (링크) · 상태: 범위 확인 대기 (코드 없음)
> 기준: PRD v0.7 (FR-COUPLE-01~06, FR-CAL-04, FR-AUTH-06 AC2, NFR-SEC-02·03, D-05~08, D-36, D-37, D-42, D-45, D-47~49, D-53, D-61, D-72, D-74, D-76, D-78), API.md 1장·1.1·2장(R-01·R-08·R-09·R-15)·3.2·4.4·4.5·4.6·5장·6장·7장, ERD 3.1(notifications)·3.2·4.1·4.2·4.3·4.5·5장(DB-03·04·05·07·08·13)
> Docs 기준 커밋: DuriseoApp/Docs main `b992123` (2026-10-26)

## 범위

### 1. 기능 (FR)과 수용 기준

AC 하나에 테스트 하나 이상 (CONVENTIONS). "앱 동작"은 서버 API가 없어 서버 테스트가 없다 (단위 1 확인 필요 14 결정). JVM 메서드 이름에 `/`를 못 쓰므로 테스트 이름의 경로는 `POST invite-codes`처럼 적는다 (단위 1 결정). `<확인 필요 N>`은 아래 확인 필요가 정해지면 채운다.

**FR-COUPLE-01 초대코드 만들기** (D-05, D-42, D-47)

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-COUPLE-01 AC1 | 개인 공간 회원이 6자리 코드(31자 집합, 대문자)와 남은 시간(10분)을 받는다. 공유는 앱 | `FR-COUPLE-01 AC1 개인 공간 회원이 POST invite-codes를 부르면 6자리 코드와 10분 뒤 expiresAt, 60초 뒤 reissueAvailableAt을 돌려준다` (상태는 확인 필요 2) |
| | | `FR-COUPLE-01 AC1 초대코드는 O·I·L·0·1을 뺀 대문자·숫자 31자 집합에서만 만든다` (생성기 단위 테스트, 여러 번 생성) |
| | | `FR-COUPLE-01 AC1 GET invite-codes current는 내 유효한 코드와 남은 시간·reissueAvailableAt을 돌려준다` |
| | | `FR-COUPLE-01 AC1 유효한 코드가 없으면 GET invite-codes current는 <확인 필요 3>` (만든 적 없음, 만료됨, 둘 다) |
| | | `FR-COUPLE-01 AC1 새 코드가 이미 있는 코드(사용·만료·무효 포함)와 겹치면 다른 코드로 다시 만든다` (생성기를 고정해 충돌 유도, `uq_invite_codes_code`) |
| FR-COUPLE-01 AC2 | 10분이 지난 코드는 "만료된 코드" | `FR-COUPLE-01 AC2 만든 지 10분이 지난 코드를 입력하면 409 INVITE_EXPIRED` (Clock +10분, 경계는 아래 "결정 후보") |
| | | `FR-COUPLE-01 AC2 만료 1초 전의 코드로는 연결된다` |
| FR-COUPLE-01 AC3 | 재요청하면 이전 코드 즉시 만료, 1분 간격, 그 전에는 남은 시간 | `FR-COUPLE-01 AC3 재요청하면 이전 코드에 revoked_at이 기록되고 새 코드가 나온다` |
| | | `FR-COUPLE-01 AC3 재요청으로 바뀐 이전 코드를 입력하면 409 INVITE_EXPIRED` (D-47) |
| | | `FR-COUPLE-01 AC3 마지막 발급 후 60초 안에 재요청하면 429 INVITE_REISSUE_TOO_SOON이고 details.retryAfterSeconds는 남은 초다` (Clock +18초 → 42) |
| | | `FR-COUPLE-01 AC3 재요청이 거부되면 코드가 바뀌지 않는다` |
| | | `FR-COUPLE-01 AC3 만료됐지만 revoked_at이 없는 이전 코드도 재요청 때 revoked_at을 기록한다` (`uq_invite_codes_active_creator`를 지킴, API 4.4 1단계) |
| | | `FR-COUPLE-01 AC3 같은 사용자의 초대코드 생성이 동시에 와도 유효한 코드는 하나이고 나머지는 429다` (동시성, 사용자 행 잠금) |
| FR-COUPLE-01 AC4 | 소문자 입력도 같은 코드 | `FR-COUPLE-01 AC4 소문자와 공백이 섞인 코드도 공백을 지우고 대문자로 바꿔 같은 코드로 연결한다` |
| FR-COUPLE-01 AC5 | 만료되면 앱이 새 코드를 자동 발급 (앱 동작, 서버 자동 발급 없음) | `FR-COUPLE-01 AC5 코드가 만료된 뒤의 POST invite-codes는 429 없이 새 코드를 준다` (만료 = 발급 후 10분 > 60초) |

**FR-COUPLE-02 초대코드 입력하기** (D-48, NFR-SEC-03)

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-COUPLE-02 AC1 | 커플 공간 생성, 코드 사용 처리, 두 사람 모두 캘린더 + 시트 (이동은 앱) | `FR-COUPLE-02 AC1 유효한 코드를 입력하면 커플 공간과 구성원 둘이 생기고 코드가 사용 처리된다` (spaces COUPLE·ACTIVE, `together_color` pick-purple, A pick-orange, B pick-blue, `used_at`·`used_by_user_id`, `restored` false) |
| | | `FR-COUPLE-02 AC1 연결 뒤 두 사람의 GET me는 같은 COUPLE 공간과 서로를 상대로 돌려준다` |
| | | `FR-COUPLE-02 AC1 연결하면 두 사람 각자에게 COUPLE_LINKED 알림 행이 space_id null로 생긴다` (제목 "[상대 이름]님과 연결됐어요", 본문 "이제 캘린더와 메모를 함께 써요", `target_type`은 확인 필요 8) |
| | | `FR-COUPLE-02 AC1 연결 이벤트는 커밋된 뒤에만 리스너에 전달된다` (단위 6의 푸시·SYNC 연결점. 트랜잭션 실패 시 전달 없음) |
| FR-COUPLE-02 AC2 | 없는·만료·사용된·자기 코드는 각각 다른 오류. 재요청·자동 재발급으로 바뀐 이전 코드는 만료 | `FR-COUPLE-02 AC2 없는 코드는 404 INVITE_NOT_FOUND` |
| | | `FR-COUPLE-02 AC2 만료된 코드는 409 INVITE_EXPIRED` |
| | | `FR-COUPLE-02 AC2 무효(revoked_at)인 이전 코드는 409 INVITE_EXPIRED` |
| | | `FR-COUPLE-02 AC2 이미 사용된 코드는 409 INVITE_USED` |
| | | `FR-COUPLE-02 AC2 자신이 만든 코드는 409 INVITE_SELF` |
| | | `FR-COUPLE-02 AC2 여러 거부 사유가 겹치면 <확인 필요 4>의 순서로 하나만 돌려준다` |
| | | `FR-COUPLE-02 AC2 6자리가 아니거나 집합 밖 글자가 있는 코드는 <확인 필요 4>` |
| | | `FR-COUPLE-02 AC2 code가 없거나 비면 400 VALIDATION_FAILED (code REQUIRED)` |
| | | `FR-COUPLE-02 AC2 거부된 입력은 공간·소속·코드를 바꾸지 않는다` |
| | | `FR-COUPLE-02 AC2 같은 코드를 두 사람이 동시에 입력하면 한 사람만 연결되고 다른 사람은 409 INVITE_USED` (동시성) |
| FR-COUPLE-02 AC3 | 코드를 만든 사람이 그 사이 다른 사람과 연결됨 → "사용할 수 없는 코드" | `FR-COUPLE-02 AC3 코드를 만든 사람이 이미 다른 사람과 연결됐으면 409 INVITE_UNAVAILABLE` |
| | | `FR-COUPLE-02 AC3 A가 B의 코드를 입력하는 동안 C가 A의 코드를 입력해도 A는 한 커플에만 들어간다` (동시성, 사용자 행 잠금. 진 쪽은 409 `INVITE_UNAVAILABLE` 또는 `ALREADY_COUPLED`) |
| FR-COUPLE-02 AC4 | 1시간 안에 10번 틀리면 1시간 동안 입력 막힘 (NFR-SEC-03, DB-08) | `FR-COUPLE-02 AC4 1시간 안에 10번 틀리면 11번째 입력은 429 INVITE_RATE_LIMITED이고 details.retryAfterSeconds를 준다` (계산은 확인 필요 5) |
| | | `FR-COUPLE-02 AC4 막힌 동안에는 올바른 코드도 429이고 연결되지 않는다` |
| | | `FR-COUPLE-02 AC4 막힘이 풀린 뒤에는 다시 입력할 수 있다` (Clock 이동, 시점은 확인 필요 5) |
| | | `FR-COUPLE-02 AC4 실패 시도는 연결 요청이 오류로 끝나도 invite_code_attempts에 남는다` (`succeeded` false) |
| | | `FR-COUPLE-02 AC4 성공한 시도는 succeeded true로 남고 실패 횟수에 들어가지 않는다` |
| | | `FR-COUPLE-02 AC4 다른 사용자의 실패는 세지 않는다` |
| | | `FR-COUPLE-02 AC4 <확인 필요 5: 실패로 세지 않는 경우(R-09 거부, 형식 오류, 429)>는 시도로 남기지 않는다` |
| FR-COUPLE-02 AC5 | 코드를 만든 쪽은 SYNC 신호와 5초 `GET /me`로 알아챔 (화면 이동은 앱) | `FR-COUPLE-02 AC5 상대가 연결하면 코드를 만든 사람의 GET me 공간 종류가 COUPLE이고 공간 ID가 바뀐다` (SYNC `LINKED` 발송은 확인 필요 1) |

**FR-COUPLE-03 연결 시 기존 기록 가져오기** (D-08, D-76, D-78)

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-COUPLE-03 AC1 | "가져오기" → 작성자 유지한 채 커플 공간으로 | `FR-COUPLE-03 AC1 기록이 있는 사용자는 연결 뒤 개인 공간이 지워지지 않고 소속에 left_at이 생기며 importPending이 true다` (API 4.4 5단계) |
| | | `FR-COUPLE-03 AC1 import true면 개인 공간의 일정·기념일·메모가 author_id를 유지한 채 커플 공간으로 옮겨지고 개인 공간이 지워진다` (204 추정, 확인 필요 2) |
| | | `FR-COUPLE-03 AC1 연결 응답의 importPending은 요청자 기준이다` (A만 기록 있음 → B의 응답은 false, A의 GET me는 true) |
| FR-COUPLE-03 AC2 | "가져오지 않기" → 개인 공간 데이터 삭제 (재확인은 앱) | `FR-COUPLE-03 AC2 import false면 개인 공간과 그 기록·알림이 지워진다` |
| FR-COUPLE-03 AC3 | 각자 자기 데이터만 고름 | `FR-COUPLE-03 AC3 한 사람의 가져오기 결정은 상대의 대기 중인 개인 공간을 바꾸지 않는다` |
| FR-COUPLE-03 AC4 | 정하기 전에는 시트를 닫을 수 없음, 다음 실행 때 다시 (앱 동작, 서버는 `importPending`) | `FR-COUPLE-03 AC4 결정하기 전에는 GET me의 importPending이 계속 true다` |
| | | `FR-COUPLE-03 AC4 기록이 없는 사용자는 연결 때 개인 공간이 바로 지워지고 importPending이 false다` |
| | | `FR-COUPLE-03 AC4 대기 중인 개인 공간이 없으면 POST couple import는 409 NO_IMPORT_PENDING이고 아무것도 바꾸지 않는다` (이미 정함, 개인 공간 사용자) |
| | | `FR-COUPLE-03 AC4 import 값이 없으면 400 VALIDATION_FAILED (import REQUIRED)` |
| FR-COUPLE-03 AC5 | [가져오기] 미리 선택 | 앱 동작 |

**FR-COUPLE-04 처음 만난 날** (D-61, D-74, Q-07)

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-COUPLE-04 AC1 | 둘 중 누구든 입력·수정, 두 사람에게 반영 (D-day·자동 기념일 재계산은 읽을 때 계산, DB-01, 단위 4) | `FR-COUPLE-04 AC1 커플의 누구든 PATCH space로 처음 만난 날을 정하면 두 사람의 GET space와 GET me에 반영된다` |
| | | `FR-COUPLE-04 AC1 상대가 정한 처음 만난 날을 다른 쪽이 고칠 수 있다` (Q-07) |
| FR-COUPLE-04 AC2 | 처음 만난 날이 없으면 안내 (앱 동작) | `FR-COUPLE-04 AC2 처음 만난 날을 정하지 않은 커플의 startDate는 null이다` |
| FR-COUPLE-04 AC3 | 건너뛰기 가능(앱), 오늘보다 뒤와 1950-01-01 이전 거부 | `FR-COUPLE-04 AC3 요청자 시간대 기준 오늘보다 뒤의 날짜는 400 VALIDATION_FAILED (startDate INVALID)` |
| | | `FR-COUPLE-04 AC3 2050-12-31보다 뒤의 날짜도 VALIDATION_FAILED다` (R-15 예외) |
| | | `FR-COUPLE-04 AC3 1950-01-01보다 앞의 날짜는 400 DATE_OUT_OF_RANGE (startDate OUT_OF_RANGE)` |
| | | `FR-COUPLE-04 AC3 1950-01-01과 오늘은 허용한다` |
| | | `FR-COUPLE-04 AC3 오늘은 서버 시간대가 아니라 요청자 users.time_zone 기준이다` (UTC 15:30, Asia/Seoul 다음 날 → 서울 날짜 허용, UTC 시간대 사용자는 거부) |
| | | `FR-COUPLE-04 AC3 startDate가 없거나 null이면 <확인 필요 7>` |

**FR-COUPLE-05 연결 해제** (D-06, D-37, D-45, D-78)

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-COUPLE-05 AC1 | 두 사람 모두 커플 공간에서 나가 각자 빈 개인 공간, 상대에게 해제 알림 | `FR-COUPLE-05 AC1 연결을 해제하면 커플 공간이 UNLINKED가 되고 unlinked_at과 30일 뒤 purge_at이 기록되며 두 소속에 left_at이 생긴다` |
| | | `FR-COUPLE-05 AC1 해제하면 두 사람 모두 새 빈 개인 공간(pick-orange)에서 다시 시작한다` |
| | | `FR-COUPLE-05 AC1 해제하면 상대에게만 COUPLE_UNLINKED 알림 행이 space_id null로 생긴다` (제목 "[해제한 사람 이름]님이 연결을 해제했어요", 본문 "30일 안에 다시 연결하면 기록이 돌아와요") |
| | | `FR-COUPLE-05 AC1 해제하면 그 공간의 event_reminders와 space_id가 그 공간인 알림이 두 사람 모두 지워지고 COUPLE_LINKED 알림은 남는다` (D-45, 픽스처) |
| | | `FR-COUPLE-05 AC1 둘이 동시에 해제해도 한 번만 처리되고 다른 요청은 <확인 필요 9>` (동시성, 공간 행 잠금) |
| | | `FR-COUPLE-05 AC1 해제 이벤트는 커밋된 뒤에만 리스너에 전달된다` (단위 6의 푸시·SYNC `UNLINKED` 연결점) |
| FR-COUPLE-05 AC2 | 30일 뒤 영구 삭제 | 단위 2는 `purge_at` 기록만 (위 AC1 테스트). 삭제 작업은 단위 6 (ERD 4.4) |
| FR-COUPLE-05 AC3 | 앱 초기화 후 SCR-COUPLE-01, 서버 프로필·알림 설정 유지 | `FR-COUPLE-05 AC3 해제 뒤 두 사람의 GET me는 PERSONAL과 새 공간 ID를 돌려준다` |
| | | `FR-COUPLE-05 AC3 해제해도 닉네임·프로필 사진 키·알림 설정은 그대로다` |
| FR-COUPLE-05 AC4 | 가져오기를 정하기 전 해제 → 원래 개인 공간으로 | `FR-COUPLE-05 AC4 가져오기를 정하기 전에 해제하면 대기 중인 개인 공간이 다시 활성 공간이 되고 기록과 색이 그대로이며 importPending이 false다` |
| | | `FR-COUPLE-05 AC4 대기 공간이 없는 구성원에게만 새 개인 공간을 만든다` (한쪽만 대기) |
| | | `FR-COUPLE-05 AC4 해제 뒤 POST couple import는 409 NO_IMPORT_PENDING` |
| | | `FR-COUPLE-05 AC4 가져오기와 해제가 동시에 와도 기록이 한쪽에만 남는다` (동시성, 공간 행 잠금. 가져오기가 이기면 기록은 커플 공간, 해제가 이기면 개인 공간 + 409 `NO_IMPORT_PENDING`) |

**FR-COUPLE-06 유예 기간 내 복구** (D-06)

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-COUPLE-06 AC1 | 30일 안에 같은 두 사람이 다시 연결하면 이전 공간 복구 | `FR-COUPLE-06 AC1 해제 후 30일 안에 같은 두 사람이 다시 연결하면 이전 공간이 ACTIVE로 복구되고 restored가 true다` (`left_at`·`unlinked_at`·`purge_at` null, `start_date`·`together_color`·구성원 색·콘텐츠 그대로, 새 COUPLE 공간 없음) |
| | | `FR-COUPLE-06 AC1 코드를 만든 사람과 입력한 사람이 이전과 바뀌어도 복구된다` |
| | | `FR-COUPLE-06 AC1 purge_at이 지난 공간은 복구하지 않고 새 커플 공간을 만든다` (정리 작업 전 상태 픽스처) |
| | | `FR-COUPLE-06 AC1 다른 사람과 연결하면 이전 공간을 복구하지 않는다` |
| | | `FR-COUPLE-06 AC1 복구돼도 두 사람에게 COUPLE_LINKED 알림 행이 생긴다` |
| FR-COUPLE-06 AC2 | 해제 후 각자 개인 공간에 만든 데이터는 FR-COUPLE-03과 같이 고름 | `FR-COUPLE-06 AC2 복구할 때도 혼자 쓴 기록이 있으면 importPending이 true이고 import true로 복구된 공간에 합쳐진다` |

**FR-CAL-04 사람 색 (우리 색상)** (D-15, D-41)

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-CAL-04 AC1 | 색은 두 사용자와 함께, 세 가지 | `FR-CAL-04 AC1 커플 공간의 GET space는 두 구성원의 색과 함께 색을 돌려준다` |
| FR-CAL-04 AC2 | 커플의 누구든 12색 중에서 바꾸면 두 사람 화면에 반영 | `FR-CAL-04 AC2 커플의 누구든 PATCH space colors로 두 사람 색과 함께 색을 바꾸면 두 사람의 GET space와 GET me에 반영된다` |
| | | `FR-CAL-04 AC2 12색 팔레트 밖의 값은 <확인 필요 6>이고 아무것도 바뀌지 않는다` |
| | | `FR-CAL-04 AC2 일부 필드만 보내면 <확인 필요 6>` |
| FR-CAL-04 AC3 | 기본값: 만든 사람 주황, 입력한 사람 파랑, 함께 보라 | `FR-CAL-04 AC3 새 커플 공간의 기본 색은 코드를 만든 사람 pick-orange, 입력한 사람 pick-blue, 함께 pick-purple이다` |
| FR-CAL-04 AC4 | 같은 색도 저장(경고는 앱) | `FR-CAL-04 AC4 두 대상이 같은 색이어도 저장된다` |
| FR-CAL-04 AC5 | 개인 공간은 나 하나, 기본 주황 | `FR-CAL-04 AC5 개인 공간의 GET space는 내 색 하나(pick-orange)이고 togetherColor는 null이다` |
| | | `FR-CAL-04 AC5 개인 공간에서도 내 색은 바꿀 수 있다` (R-08 "개인 공간에서는 자기 색만", SCREENS SCR-SET-03 개인 공간) |
| FR-CAL-04 AC6 | 색만으로 의미 전달 안 함 | 앱 동작 |

**단위 1에서 넘어온 것**

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-AUTH-06 AC2 | 커플 공간 사용자 탈퇴: 연결 해제 → 탈퇴 전용 해제 알림 → 커플 공간 90일 보관 (ERD 4.3 1~2단계, API 4.6, D-36, D-53) | `FR-AUTH-06 AC2 활성 커플 공간 사용자가 탈퇴하면 연결이 해제되고 상대는 새 개인 공간으로 간다` |
| | | `FR-AUTH-06 AC2 탈퇴로 해제된 커플 공간의 purge_at은 탈퇴 시각 + 90일이다` (해제 +30일 뒤 단위 1의 연장이 +90일로) |
| | | `FR-AUTH-06 AC2 탈퇴하면 상대에게 탈퇴 전용 COUPLE_UNLINKED 알림이 익명화 전 닉네임으로 생긴다` (제목 "[닉네임]님이 탈퇴해 연결이 해제됐어요", 본문 "함께 쓴 기록은 더 이상 볼 수 없어요", 복구 안내 없음) |
| | | `FR-AUTH-06 AC2 탈퇴 이벤트의 partnerUserId는 상대 ID다` |
| | | `FR-AUTH-06 AC2 상대가 가져오기를 정하기 전에 탈퇴하면 남은 사람의 대기 공간이 되살아난다` (D-78) |
| | | `FR-AUTH-06 AC2 탈퇴한 사용자에게는 개인 공간이 남지 않는다` |
| | | `FR-AUTH-06 AC2 탈퇴 도중 실패하면 커플 공간·상대·알림이 그대로다` (단위 1의 실패 트리거 방식) |
| | | 위 테스트들이 단위 1의 `FR-AUTH-06 AC2 활성 커플 공간 사용자의 탈퇴는 연결 해제(단위 2)가 생기기 전까지 500이고 아무것도 바꾸지 않는다`(`WithdrawalTest`)를 **대체한다** |
| FR-AUTH-07 AC2 | 닉네임을 바꾸면 상대 화면에도 (단위 1이 남긴 `GET /space` 쪽) | `FR-AUTH-07 AC2 닉네임을 바꾸면 상대의 GET space 구성원 닉네임에 반영된다` |
| FR-SET-01 AC1 | 커플(연결 상태, 상대 닉네임, 처음 만난 날) | `FR-SET-01 GET space는 구성원의 ID·닉네임·색·프로필 사진 여부와 처음 만난 날·함께 색을 돌려준다` (모양은 확인 필요 2. D-57 메모 작성자 닉네임도 이 값) |

### 2. 권한 규칙 (API.md 2장) — `SpaceAccessPolicy`

R-09는 단위 1이 정책 컴포넌트 테스트까지 했다. 단위 2는 경로 테스트를 더한다. `DELETE /couple`("커플 구성원")과 `POST /couple/import`("가져오기 대기 중인 본인")는 R- 번호가 없다 (확인 필요 9).

| ID | 내용 | 테스트 |
| --- | --- | --- |
| R-01 | 공간 리소스는 요청자의 활성 공간만 (`GET /space`, `PATCH /space`, `PATCH /space/colors`는 경로에 공간 ID가 없고 활성 공간을 대상으로 한다) | 허용: `R-01 GET space는 요청자의 활성 공간을 돌려준다` |
| | | 거부: `R-01 해제된 이전 커플 공간은 GET space에 나오지 않는다` |
| | | 거부: `R-01 PATCH space colors에 다른 공간 사용자의 userId를 보내면 <확인 필요 6>이고 그 사용자의 색은 바뀌지 않는다` |
| R-08 | 처음 만난 날·사람 색 변경은 커플 공간 구성원, 개인 공간에서는 자기 색만. 아니면 409 `NOT_COUPLE` | 허용: `R-08 커플 구성원은 처음 만난 날을 바꿀 수 있다` |
| | | 허용: `R-08 커플 구성원은 상대 색과 함께 색도 바꿀 수 있다` |
| | | 허용: `R-08 개인 공간에서는 자기 색을 바꿀 수 있다` |
| | | 거부: `R-08 개인 공간에서 PATCH space는 409 NOT_COUPLE이고 아무것도 바뀌지 않는다` (게스트 포함) |
| | | 거부: `R-08 개인 공간에서 together 색을 보내면 <확인 필요 6>이고 아무것도 바뀌지 않는다` |
| R-09 | 초대코드 생성·조회·입력: MEMBER이고 개인 공간. 게스트 403 `GUEST_NOT_ALLOWED`, 커플 409 `ALREADY_COUPLED` | 허용: `R-09 개인 공간 회원은 POST invite-codes, GET invite-codes current, POST couple join을 부를 수 있다` |
| | | 거부: `R-09 게스트가 POST invite-codes를 부르면 403 GUEST_NOT_ALLOWED이고 코드가 생기지 않는다` |
| | | 거부: `R-09 게스트가 GET invite-codes current를 부르면 403 GUEST_NOT_ALLOWED` |
| | | 거부: `R-09 게스트가 POST couple join을 부르면 403 GUEST_NOT_ALLOWED이고 시도 기록도 없다` |
| | | 거부: `R-09 커플 공간 회원이 POST invite-codes를 부르면 409 ALREADY_COUPLED` |
| | | 거부: `R-09 커플 공간 회원이 GET invite-codes current를 부르면 409 ALREADY_COUPLED` |
| | | 거부: `R-09 커플 공간 회원이 POST couple join을 부르면 409 ALREADY_COUPLED이고 코드는 사용되지 않는다` |
| | | 허용: `R-09 연동한 게스트는 같은 access 토큰으로 바로 초대코드를 만들 수 있다` (계정 종류를 DB에서 읽음) |
| | | 거부: `R-09 연결 직후 같은 access 토큰으로 POST invite-codes를 부르면 409 ALREADY_COUPLED` |
| R-15 | `spaces.start_date` 1950-01-01 미만 → `DATE_OUT_OF_RANGE`, 오늘보다 뒤 → `VALIDATION_FAILED` | FR-COUPLE-04 AC3 테스트 5개가 허용·거부를 덮는다 |
| (번호 없음) | `DELETE /couple`: 커플 구성원 | 허용: FR-COUPLE-05 AC1. 거부: `DELETE couple을 개인 공간에서 부르면 <확인 필요 9>` |
| (번호 없음) | `POST /couple/import`: 가져오기 대기 중인 본인 | 허용: FR-COUPLE-03 AC1. 거부: FR-COUPLE-03 AC4의 409 `NO_IMPORT_PENDING` 테스트 |

범위 밖 규칙: R-02~R-07·R-10·R-13(단위 3·5), R-11 경로(단위 7. 단위 1이 "해제된 이전 상대 404" 정책 테스트를 해 둠), R-12·R-16(단위 6), R-14·R-17(단위 1 완료).

### 3. API 경로

모든 경로는 `/api/v1` 아래. API.md 7행은 3.1 밖의 상세 스키마를 서버 OpenAPI가 정한다고 하므로 Docs에 없는 모양은 *(미정)* 으로 두고 확인 필요 2에 모았다.

| 메서드·경로 | 요청 | 성공 응답 | 오류 | 흐름·규칙 |
| --- | --- | --- | --- | --- |
| `GET /space` | 없음 | 200 현재 공간 상세: 구성원(닉네임·색), 함께 색, 처음 만난 날 *(필드 미정)* | 401 계열 | R-01, FR-SET-01, FR-CAL-04, D-57 |
| `PATCH /space` | `{startDate}` | *(미정, 200 공간 상세 추정)* | 400 `VALIDATION_FAILED`(`startDate` `INVALID`: 요청자 시간대 기준 오늘보다 뒤, 2050년 이후 포함 / `REQUIRED`: 확인 필요 7), 400 `DATE_OUT_OF_RANGE`(`startDate` `OUT_OF_RANGE`: 1950-01-01 미만), 409 `NOT_COUPLE` | R-08, R-15, FR-COUPLE-04, API 4.4 9단계 |
| `PATCH /space/colors` | `{members: [{userId, color}], together}` | *(미정)* | 409 `NOT_COUPLE`, 400 `VALIDATION_FAILED`(확인 필요 6) | R-08, FR-CAL-04 |
| `POST /invite-codes` | 없음 | 201? *(확인 필요 2)* `{code, expiresAt, reissueAvailableAt}` | 403 `GUEST_NOT_ALLOWED`, 409 `ALREADY_COUPLED`, 429 `INVITE_REISSUE_TOO_SOON`(`details.retryAfterSeconds`) | R-09, API 4.4 1단계 |
| `GET /invite-codes/current` | 없음 | 200 유효한 코드와 `reissueAvailableAt` *(응답 모양은 POST와 같다고 추정)*. 없으면 *(확인 필요 3)* | 403, 409 (R-09) | R-09, FR-COUPLE-01 |
| `POST /couple/join` | `{code}` (공백 제거·대문자) | *(상태 미정)* `restored`, `importPending` *(나머지 필드 미정)* | 400 `VALIDATION_FAILED`, 403 `GUEST_NOT_ALLOWED`, 404 `INVITE_NOT_FOUND`, 409 `INVITE_EXPIRED`·`INVITE_USED`·`INVITE_SELF`·`INVITE_UNAVAILABLE`·`ALREADY_COUPLED`, 429 `INVITE_RATE_LIMITED`(`details.retryAfterSeconds`) | R-09, API 4.4 2~7단계, NFR-SEC-03 |
| `POST /couple/import` | `{import: boolean}` | *(미정, 204 추정)* | 400 `VALIDATION_FAILED`, 409 `NO_IMPORT_PENDING` | API 4.4 9·10단계 |
| `DELETE /couple` | 없음 | *(미정, 204 추정)* | *(개인 공간에서 부를 때 미정, 확인 필요 9)* | API 4.5 |
| `DELETE /me` (단위 1) | 바뀌지 않음 | 204 | 바뀌지 않음 (커플 사용자의 500이 없어짐) | API 4.6, ERD 4.3 1단계 |
| `GET /me` (단위 1) | 바뀌지 않음 | 200 `Me` | — | 연결·해제·가져오기 뒤 값만 바뀐다 (이미 COUPLE·`importPending`을 지원) |

### 4. ERD 테이블과 제약

모두 V1(`V1__init_schema.sql`)에 있다. **새 마이그레이션은 없을 예정**이다.

| 테이블 | 단위 2에서 하는 일 | 사용하는 제약·인덱스 |
| --- | --- | --- |
| spaces | COUPLE 생성(`together_color` pick-purple), 해제(UNLINKED, `unlinked_at`, `purge_at` +30일), 복구(ACTIVE, 둘 다 null), `start_date`·`together_color` 변경, 연결 때 기록 없는 개인 공간 삭제, 가져오기 결정 뒤 개인 공간 삭제, 탈퇴 해제 | `ck_spaces_couple_only_columns`, `ck_spaces_unlinked_columns`, `ix_spaces_purge_at` |
| space_members | 커플 구성원 2행(A pick-orange, B pick-blue), 개인 공간 소속 `left_at`(가져오기 대기), 해제 때 `left_at`, 대기 공간 되살리기(`left_at` null), 해제 후 새 개인 공간 소속(pick-orange), 복구 때 `left_at` null, 색 변경 | **`uq_space_members_active_user`**(부분 UNIQUE user_id WHERE left_at IS NULL, "한 사람 한 커플"의 최종 보장. 위반은 CONVENTIONS대로 "이미 연결됨"으로 바꾼다), `uq_space_members_space_user` |
| invite_codes | 생성, 재요청 때 이전 미사용 코드 전부 `revoked_at`, 입력 때 `used_at`·`used_by_user_id`, 탈퇴 때 유효한 코드 삭제(단위 1의 SQL을 옮김) | `uq_invite_codes_code`(전체 행 UNIQUE, 사용·만료 코드도 30일 남음 → 생성 충돌 재시도), **`uq_invite_codes_active_creator`**(부분 UNIQUE creator WHERE used_at IS NULL AND revoked_at IS NULL) |
| invite_code_attempts | 입력마다 성공·실패 기록, 최근 1시간 실패 수 조회 | `ix_invite_code_attempts_user_attempted` (7일 정리는 단위 6) |
| notifications | `COUPLE_LINKED` 두 행, `COUPLE_UNLINKED` 한 행(해제·탈퇴 문구), 해제 때 그 공간 `space_id` 행 삭제(D-45) | `ck_notifications_type`, `ck_notifications_target_type`, FK `space_id` CASCADE (ERD 4.5) |
| events, anniversaries, memos | 연결 때 개인 공간에 기록이 있는지 확인, `import true`면 `space_id`를 커플 공간으로 | FK `space_id` CASCADE (개인 공간 삭제 시) |
| event_reminders | 해제 때 그 공간 일정의 행 삭제 (API 4.5 3단계) | FK `event_id` CASCADE |
| users | 잠금(`FOR UPDATE`), 알림 문구의 닉네임 | — |

| ERD 수명주기 | 단위 2 |
| --- | --- |
| 4.1 커플 연결(복구 아님), 커플 연결 때 개인 공간 처리 | 전부 |
| 4.1 연결 해제 때 개인 공간 생성·되살리기 | 전부 |
| 4.1 알림이 생길 때 (연결·해제 알림 행) | 행 생성만. 푸시·SYNC `NOTIFICATION`은 단위 6 |
| 4.2 연결 해제와 보관 (D-45 알림 삭제, D-78 되살리기, 복구) | 전부 |
| 4.3 탈퇴 1단계 (커플이면 4.2로 해제) | 전부 (단위 1 연결점) |
| 4.4 `purge_at` 지난 공간 삭제, 초대코드·시도 정리 | 단위 6 |
| 4.5 공간 삭제 시 알림 CASCADE (연결 때 빈 개인 공간, 가져오기 결정 뒤) | DB가 처리, 테스트로 확인 |

| DB- 결정 | 단위 2에서의 의미 |
| --- | --- |
| DB-03 | 서버 1대라 오입력 제한과 잠금을 DB로만 한다 (분산 잠금 없음) |
| DB-04 | 탈퇴로 해제된 커플 공간은 90일 보관 (단위 1의 연장 코드가 해제 뒤에 돈다) |
| DB-05 | `PATCH /space/colors`의 다른 공간 사용자 등 공간 밖 정보를 드러내지 않는다 |
| DB-07 | 연결·해제 알림 행은 푸시와 별개로 먼저 만든다 |
| DB-08 | 31자 6자리 + 10분 + 1시간 10회 |
| DB-13 | `start_date` 범위는 서비스에서 검사, DB CHECK 없음 |

### 5. 동시성과 잠금 (가장 큰 위험)

ARCHITECTURE 4장: 두 사람이 동시에 움직이는 흐름은 `SELECT … FOR UPDATE`. 교착을 막으려고 **모든 흐름이 같은 순서로 잠근다: users(UUID 오름차순) → invite_codes → spaces**. 공간을 잠근 뒤 사용자를 잠그는 흐름은 두지 않는다.

| 흐름 | 잠금 | 막는 경쟁 |
| --- | --- | --- |
| `POST /invite-codes` | 요청자 users 행 → (잠금 뒤 R-09·60초 다시 확인) → 이전 코드 `revoked_at` → 새 코드 | 같은 사용자의 겹친 생성(두 번째는 429), 생성과 상대의 입력이 겹침 |
| `POST /couple/join` | 코드를 잠금 없이 찾아 만든 사람 A를 안다 → users A·B를 UUID 순서로 잠금 → 코드 행 `FOR UPDATE`로 다시 읽어 검사 → (복구면) 이전 UNLINKED 공간 행 잠금 | 같은 코드 동시 입력(두 번째 `INVITE_USED`), A가 다른 코드로 연결 중(`INVITE_UNAVAILABLE`), 재요청과 입력이 겹침. 코드 먼저 잠그고 사용자를 잠그면 재요청(사용자 → 코드)과 교착한다 |
| `DELETE /couple` | 활성 커플 공간 행 `FOR UPDATE` → 잠금 뒤 다시 확인 | 둘의 동시 해제, 해제와 가져오기·탈퇴 해제가 겹침 |
| `POST /couple/import` | 요청자의 활성 커플 공간 행 `FOR UPDATE` → 대기 공간 확인 | 가져오기와 해제(D-78)가 겹침 |
| `PATCH /space`, `/space/colors` | 활성 공간 행 `FOR UPDATE` | 해제와 겹친 변경. 둘의 동시 변경은 마지막 저장이 이김 (NFR-SYNC-03) |
| `DELETE /me` (커플) | 단위 1대로 본인 users 행 → 커플 공간 행 | 탈퇴와 상대 해제가 겹침. 상대 users 행은 잠그지 않는다 |

그 밖의 구현 위험:

1. **JPA 쓰기 순서와 부분 UNIQUE.** Hibernate는 flush 때 INSERT를 UPDATE보다 먼저 보낸다. 연결에서 "개인 공간 소속 `left_at` 기록 → 커플 소속 INSERT", 해제에서 "커플 소속 `left_at` → 대기 공간 `left_at` null"을 그대로 엔티티로 하면 `uq_space_members_active_user` 위반(500)이 난다. 부분 UNIQUE 인덱스는 지연(DEFERRABLE)할 수 없다. 단계 사이에 명시적 `flush()`를 하거나 JPQL 일괄 UPDATE(`flushAutomatically`)를 쓰고, 순서 테스트를 둔다.
2. **코드 충돌 재시도.** `uq_invite_codes_code` 위반은 PostgreSQL 트랜잭션을 실패 상태로 만들어 같은 트랜잭션에서 다시 INSERT할 수 없다. 넣기 전에 존재를 확인하거나 `INSERT … ON CONFLICT (code) DO NOTHING`으로 재시도한다 (31^6 ≈ 8.9억이라 충돌은 드물다).
3. **실패 시도 기록과 롤백.** 거부는 `ApiException`이라 트랜잭션이 롤백되므로 실패 시도 INSERT도 사라진다. 시도 기록은 별도 트랜잭션(`REQUIRES_NEW`)에 넣는다. 이때 바깥 트랜잭션이 잡은 users 행 잠금과 겹치지 않는지(시도 테이블은 users를 잠그지 않음) 확인한다.
4. **오입력 횟수 경쟁.** 같은 사용자의 입력이 동시에 오면 9회 상태에서 둘 다 통과할 수 있다. 요청자 users 행을 잠근 뒤 횟수를 세면 순서가 정해진다.

## 단위 1 연결점 (완성할 것)

| 연결점 | 지금 | 단위 2에서 |
| --- | --- | --- |
| `WithdrawalService.unlinkCoupleBeforeWithdrawal` | 커플이면 `IllegalStateException` → 500, 전체 롤백 | couple 서비스의 "탈퇴 해제"를 부른다: 4.2 해제 + 상대에게 탈퇴 전용 `COUPLE_UNLINKED`(익명화 전 닉네임) + 상대 개인 공간 되살리기/만들기. 상대 ID를 돌려줘 `UserWithdrawn.partnerUserId`에 담는다. 이 뒤에 단위 1의 `purge_at` +90일 연장(2단계)과 개인 공간 삭제(3단계)가 그대로 돈다 |
| `WithdrawalTest`의 500 테스트 | `FR-AUTH-06 AC2 활성 커플 공간 사용자의 탈퇴는 연결 해제(단위 2)가 생기기 전까지 500이고 아무것도 바꾸지 않는다` | 지우고 위 범위 표의 FR-AUTH-06 AC2 테스트 7개로 바꾼다 |
| `WithdrawalDataEraser`의 네이티브 SQL | `invite_codes`, `notifications`, `memo_favorites`, `event_reminders`를 직접 지움 | `invite_codes` 삭제를 couple의 초대코드 서비스로 옮긴다. `notifications`는 단위 2가 알림 행 생성을 위해 `notification` 도메인(엔티티·생성 서비스)을 만들면 함께 옮긴다(결정 후보). `memo_favorites`(단위 5), `event_reminders`(단위 3)는 남긴다 |
| `UserWithdrawn.partnerUserId` | 항상 null | 상대 ID. 단위 6이 커밋 뒤 푸시·SYNC `UNLINKED`에 쓴다 |
| `SpaceAccessPolicy.requireInviteEligible` (R-09) | 정책 테스트만 | 경로 3개에서 부른다. R-08용 메서드를 더한다 |
| `SpaceService.activeSpaceView`, `MeService` | COUPLE·상대·`importPending` 지원 (픽스처로 테스트됨) | 그대로 쓴다. `GET /space`는 같은 조회를 넓혀 쓴다 |

## 단위 6과의 경계 (지금 할 것과 미룰 것)

API 7장: "모든 알림 작업은 받는 사람마다 `notifications` 행을 **먼저** 만들고 푸시는 조건이 맞을 때만". 단위 1 결정: "연결 해제·`COUPLE_UNLINKED` 알림 행은 단위 2, 푸시·SYNC는 단위 6".

| 일 | 단위 | 근거 |
| --- | --- | --- |
| `COUPLE_LINKED` 행 두 개 (연결·복구) | **2** | API 4.4 6단계, 7장 |
| `COUPLE_UNLINKED` 행 (해제 문구 / 탈퇴 문구, 상대에게만, `space_id` null) | **2** | API 4.5 5단계, 4.6, 7장, D-53 |
| 해제 때 그 공간 `notifications`·`event_reminders` 삭제 | **2** | API 4.5 3단계, D-45 |
| 위 행이 생겼다는 도메인 이벤트 발행 (트랜잭션 안에서 발행, 리스너는 `AFTER_COMMIT`) | **2** | ARCHITECTURE 4장. 단위 1의 `UserWithdrawn`과 같은 방식 |
| 연결·해제 푸시 (항상, 유효 세션 있을 때만 R-16) | 6 | 7장, PLAN 단위 6 |
| SYNC `SPACE`/`LINKED` (두 사람 모두) | **확인 필요 1** | PLAN 단위 2 행은 "SYNC `LINKED`"를, 단위 6 행은 "푸시(FCM)·동기화 신호"를 적었다 |
| SYNC `SPACE`/`UNLINKED` (상대), `SPACE`/`UPDATED` (처음 만난 날·색 변경 → 상대), `NOTIFICATION` (행 생긴 본인) | 6 | 6장, PLAN 단위 6. 단위 2는 이벤트만 |
| `purge_at` 지난 공간 삭제, 30일 지난 초대코드·7일 지난 시도 삭제 | 6 | ERD 4.4 |
| D-day·자동 기념일 | 4 | 읽을 때 계산 (DB-01). 단위 2는 `start_date`만 저장 |
| 일정·기념일·메모·일정 알림·즐겨찾기 엔티티 | 3·4·5 | 단위 2는 개수 확인·`space_id` 이동·`event_reminders` 삭제를 네이티브 SQL로 하고, 그 단위가 도메인 서비스로 옮긴다 (단위 1과 같은 방식) |

## 결정

구현하면서 정한 것 중 문서에 없는 것. 문서에 있어야 할 결정이면 여기 적지 말고 작업을 멈추고 보고한다.

결정 후보 (범위 승인 때 함께 확인):

- 잠금 순서는 users(UUID 오름차순) → invite_codes → spaces. 연결은 코드를 잠금 없이 읽어 만든 사람을 안 뒤 사용자 둘을 잠그고 코드를 `FOR UPDATE`로 다시 읽는다 (위 5장).
- 만료 경계: `expires_at <= now`면 만료. 재요청 허용: 가장 최근 `created_at` + 60초 `<= now`. `retryAfterSeconds`는 남은 시간을 초 단위로 올림하고 최소 1.
- 연결 응답·`restored` 판단: 복구 대상은 `type = COUPLE`, `status = UNLINKED`, `purge_at > now`, 소속 행이 정확히 A와 B 두 개인 공간. 여러 개면(정상적으로는 없음) `unlinked_at`이 가장 늦은 것.
- 복구한 공간의 구성원 색과 함께 색은 해제 전 값을 그대로 쓴다 (소속 행을 되살리므로. API 4.4 4단계는 `left_at`만 비운다고 함).
- 탈퇴로 인한 해제에서 탈퇴하는 사람에게는 새 개인 공간을 만들지 않는다 (바로 다음 3단계가 지우므로 결과는 ERD 4.3과 같다). 상대는 4.2대로 되살리거나 만든다.
- 탈퇴 해제의 닉네임은 `WithdrawalService`가 익명화 전에 읽은 값을 couple 서비스에 넘긴다 (couple → user 의존을 만들지 않으려고).
- 알림 문구는 PRD 8장 그대로 서버 상수로 둔다. `[상대 이름]`은 받는 사람 기준의 상대 닉네임(연결 시점 값).
- 실패 시도 기록은 `REQUIRES_NEW` 트랜잭션. 성공 시도는 연결 트랜잭션 안에서 함께 기록.
- 초대코드는 `SecureRandom`으로 31자 집합에서 고른다. 충돌하면 최대 몇 번 다시 만든다 (`ON CONFLICT DO NOTHING` 또는 존재 확인).
- 연결 이벤트·해제 이벤트·공간 변경 이벤트(예: `CoupleLinked`, `CoupleUnlinked(byWithdrawal)`, `SpaceUpdated`)를 트랜잭션 안에서 발행하고, 단위 2에는 리스너가 없다 (단위 6). 테스트는 `@RecordApplicationEvents` 또는 테스트 리스너로 커밋 뒤 전달을 확인한다.
- 단위 2가 `notification` 패키지에 `Notification` 엔티티와 행 생성 서비스(연결·해제 알림만)를 만들고, `WithdrawalDataEraser`의 `notifications` 삭제를 그 서비스로 옮긴다. 목록·읽음·설정 API는 단위 6.
- 콘텐츠 테이블(events, anniversaries, memos, event_reminders)은 아직 엔티티가 없어 couple의 한 컴포넌트에서 네이티브 SQL로 다룬다 (ARCHITECTURE 2장의 "다른 도메인은 서비스로만"의 임시 예외. 단위 3·4·5가 옮긴다).

## 리뷰 기록

| 회차 | Critical | Major | 비고 |
| --- | --- | --- | --- |

## 남은 일

- 사용자: 범위 표와 아래 확인 필요를 보고 "start" (확인 필요 중 Docs 수정이 필요한 것은 Docs 레포 `/prd-sync`)
- 단위 3·4·5: 단위 2의 콘텐츠 네이티브 SQL(개수 확인, `space_id` 이동, `event_reminders` 삭제)을 각 도메인 서비스로 옮기기
- 단위 6: `CoupleLinked`·`CoupleUnlinked`·`SpaceUpdated`·`UserWithdrawn`을 듣는 푸시·SYNC, `purge_at`·초대코드·시도 정리 작업
- Android: 초대코드·연결·가져오기·해제·공간 응답 모양(확인 필요 2), 오류 우선순위(확인 필요 4), `retryAfterSeconds`(확인 필요 5)

## 확인 필요

문서끼리 다르거나 비어 있는 곳. 추측하지 않고 선택지를 적는다. Docs를 고쳐야 하는 항목은 Docs 레포 `/prd-sync`로.

1. **SYNC `LINKED`를 어느 단위가 보내나.** `docs/PLAN.md` 단위 2 행은 "연결(행 잠금, SYNC `LINKED`)", 단위 6 행은 "푸시(FCM)·동기화 신호"다. `API.md` 3.2 `/couple/join` 행(175행)·4.4 7단계(271행)·6장(355행)은 커밋 뒤 두 사람 기기에 `{"type":"SYNC","resource":"SPACE","action":"LINKED"}`를 보낸다고 한다. FCM 발송 코드(Firebase Admin SDK, `FIREBASE_PROJECT_ID`·`FIREBASE_CREDENTIALS_BASE64`는 `application.yml`에 이미 있음)는 아직 없다. PRD FR-COUPLE-02 AC5와 D-48은 신호가 늦을 때를 대비해 5초마다 `GET /me`를 부르므로 신호 없이도 연결은 감지된다.
   - A) 단위 2는 커밋 뒤 전달되는 `CoupleLinked` 이벤트까지만 만들고, FCM 발송은 단위 6이 한 번에 만든다. PLAN 단위 2 문구에서 "SYNC `LINKED`"를 "SYNC `LINKED` 연결점"으로 고친다 (추천: 배포 1의 두 기기 확인은 5초 확인으로 된다)
   - B) 단위 2에서 FCM 발송기(SYNC 전용, `devices` 조회 포함)를 만들고 `LINKED`만 보낸다. 단위 6이 푸시를 더한다 (Firebase 의존성과 Parameter Store 비밀값 등록이 단위 2로 당겨짐)
2. **3.2 경로의 응답 모양과 상태가 없다.** `API.md` 7행은 3.1 밖의 상세 스키마를 OpenAPI가 정한다고 하지만, 단위 1 때는 Android가 의존하는 모양을 3.1에 적었다. 비어 있는 것: `POST /invite-codes` 상태(1.1의 "생성은 201"?), `GET /invite-codes/current` 본문(173~174행), `POST /couple/join` 상태와 `restored`·`importPending` 밖의 필드(175행), `POST /couple/import`·`DELETE /couple`의 성공 상태(176~177행), `GET /space` 본문(170행: "구성원, 사람 색, 처음 만난 날"뿐), `PATCH /space`·`/space/colors` 응답(171~172행).
   - A) `/prd-sync`로 API.md 3.2에 3.1처럼 적는다 (추천). 제안: `InviteCode` = `{code, expiresAt, reissueAvailableAt}`, POST 201·GET 200 / join 200 `{spaceId, restored, importPending}` / import 204 / `DELETE /couple` 204 / `Space` = `{id, type, togetherColor, startDate, members: [{userId, nickname, color, hasProfileImage}]}`, `GET /space`·`PATCH /space`·`PATCH /space/colors` 모두 200 `Space`
   - B) 서버가 정해 OpenAPI와 이 기록의 "결정"에만 적는다
3. **유효한 코드가 없을 때 `GET /invite-codes/current`.** `API.md` 174행은 "내 유효한 초대코드와 남은 시간"만 말한다. 만든 적 없음, 만료됨(10분 지남, `revoked_at` 없음), 무효됨일 때의 응답이 없다. 앱은 다시 들어왔을 때 만료됐으면 새 코드를 받아야 한다 (FR-COUPLE-01 AC5).
   - A) 404 `NOT_FOUND` → 앱이 `POST /invite-codes`를 부른다 (추천. 만료 코드는 발급 후 10분이 지나 60초 제한에 걸리지 않음)
   - B) 200 `{code: null, expiresAt: null, reissueAvailableAt}`
   - C) 사용·무효가 아닌 가장 최근 코드를 만료됐어도 돌려주고 앱이 `expiresAt`으로 판단
4. **연결 거부 사유가 겹칠 때의 우선순위와 형식 오류.** `API.md` 4.4 3단계(265행)는 "존재, 미만료, 미사용, 미무효, 만든 사람이 B가 아님, A와 B 모두 MEMBER이고 개인 공간"을 나열만 하고 순서를 정하지 않았다. 예: 자기가 만든 만료 코드(SELF? EXPIRED?), 사용 뒤 10분이 지난 코드(USED? EXPIRED?). 또 공백 제거·대문자 변환 뒤 6자리가 아니거나 집합 밖 글자(0, O, 1 등)가 있는 코드의 오류도 없다. R-09와 오입력 제한(2단계 "먼저 최근 1시간 실패")의 앞뒤도 명확히 하고 싶다.
   - A) R-09(요청자) → `code` 없음·빈 값 400 `VALIDATION_FAILED`(`REQUIRED`) → 오입력 제한 429 → 형식이 틀린 코드는 404 `INVITE_NOT_FOUND`(실패로 셈) → 없음 404 → 자기 코드 `INVITE_SELF` → 사용됨 `INVITE_USED` → 만료·무효 `INVITE_EXPIRED` → 만든 사람이 커플·탈퇴 `INVITE_UNAVAILABLE` (추천: 사용자에게 더 쓸모 있는 이유를 먼저)
   - B) 문서 나열 순서(존재 → 만료 → 사용 → 무효 → 자기 → 상대 상태), 형식 오류는 400 `VALIDATION_FAILED`(`code`, `INVALID`)이고 실패로 세지 않음
5. **오입력 제한의 기준.** PRD FR-COUPLE-02 AC4(254행)는 "1시간 안에 잘못된 코드를 10번 입력하면 1시간 동안 막힌다"(10번째 실패부터 1시간), `API.md` 4.4 2단계(264행)는 "최근 1시간 실패가 10회 이상인지 본다"(미끄러지는 창: 가장 오래된 실패가 1시간을 벗어나면 풀림)다. 두 방식은 풀리는 시각과 `retryAfterSeconds`가 다르다. 또 무엇을 "실패"로 세는지(`INVITE_UNAVAILABLE`도? 형식 오류도?), 막힌 동안의 429 요청과 R-09 거부를 시도로 남기는지가 없다 (264행 "시도는 성공·실패 모두 남긴다"만 있음).
   - A) API.md대로 미끄러지는 창. 실패 = 코드 검사 실패 5종(`INVITE_NOT_FOUND`·`EXPIRED`·`USED`·`SELF`·`UNAVAILABLE`), 429·R-09 거부·`VALIDATION_FAILED`는 시도로 남기지 않음. `retryAfterSeconds` = (최근 1시간 실패 중 10번째로 최근 것의 시각 + 1시간) − 지금, 올림. PRD 문구를 "최근 1시간 실패가 10회면 그 중 가장 오래된 실패가 1시간을 지날 때까지 막힌다"로 고친다 (추천)
   - B) PRD 문구대로 10번째 실패 시각부터 1시간 막음. `retryAfterSeconds` = 10번째 실패 + 1시간 − 지금. API.md 2단계 문장을 고친다
6. **`PATCH /space/colors`의 세부 규칙.** `API.md` 172행은 `{members: [{userId, color}], together}`와 R-08만 있다. 정해지지 않은 것: (a) 일부만 보내도 되는지(`members` 하나, `together`만), (b) 12색 팔레트 키 목록이 ERD·API에 없고(`ERD.md` 198행은 "`pick-*` 12색 토큰 이름"이라고만 함, 실제 값은 `design/tokens.json`의 `pick-orange`~`pick-ink`) 팔레트 밖 값의 오류, (c) 활성 공간 구성원이 아닌 `userId`·같은 `userId` 두 번의 오류, (d) 개인 공간에서 `together`나 다른 사용자를 보낼 때 409 `NOT_COUPLE`인지 400인지.
   - A) 모든 필드 선택(없으면 그대로, 빈 요청은 400 `VALIDATION_FAILED`). 팔레트 밖 값·공간 밖 `userId`·중복 `userId`는 400 `VALIDATION_FAILED`(`INVALID`, 필드 `members[i].color` 등. 다른 공간 사용자도 같은 응답이라 존재를 드러내지 않음). 개인 공간에서 `together`가 있거나 자기 밖 `userId`가 있으면 409 `NOT_COUPLE`. 12 키를 ERD 3.2에 적는다 (추천)
   - B) 전체 교체: 커플은 두 구성원 + `together` 필수, 개인 공간은 자기 하나만
7. **처음 만난 날을 지울 수 있나.** PRD FR-COUPLE-04 AC1은 "입력하거나 수정", AC3은 시트에서 "건너뛸 수 있다"뿐이고 `API.md` 171행은 `{startDate}`만 있다. `startDate: null`이나 필드 없음의 처리가 없다.
   - A) 지우기 없음: 없거나 null이면 400 `VALIDATION_FAILED`(`startDate`, `REQUIRED`) (추천: PRD에 지우기 기능이 없음)
   - B) null이면 지운다 (FR-COUPLE-04 AC2의 안내로 돌아감)
8. **연결·해제 알림의 이동 대상.** `API.md` 3.5(222행)는 "이동 대상이 없으면 `targetType`, `targetId`는 null", PRD FR-NOTI-03 AC1(399행)은 이동 화면에 "설정"을 넣었는데, `COUPLE_LINKED`·`COUPLE_UNLINKED`의 `target_type`을 정한 곳이 없다 (`SETTINGS`를 쓸 알림은 이 둘뿐이다).
   - A) 둘 다 `target_type = SETTINGS`, `target_id` null (추천)
   - B) 둘 다 null (누르면 읽음만)
   - C) 연결은 `SETTINGS`, 해제·탈퇴 해제는 null (해제 뒤 앱은 초기화되어 SCR-COUPLE-01로 감)
9. **커플이 아닌 사용자가 `DELETE /couple`을 부를 때.** `API.md` 177행의 권한은 "커플 구성원"뿐이고 R- 규칙과 오류 코드가 없다. 둘이 동시에 해제했을 때 두 번째 요청도 같은 경우가 된다.
   - A) 409 `NOT_COUPLE` (5장 정의 "커플 공간이 필요한 요청을 개인 공간에서 보냄"과 맞음) + API.md 2장에 R- 규칙(예: R-18)으로 `DELETE /couple`과 `POST /couple/import`의 권한을 적는다 (추천)
   - B) 409 `NOT_COUPLE`만 쓰고 R- 규칙은 추가하지 않는다
   - C) 204 (이미 해제됐으면 성공으로 봄. 멱등)
10. **연결할 때 두 사람의 다른 유효한 초대코드.** 문서는 연결 때 코드를 무효로 하라는 말이 없다 (`API.md` 4.4 5단계는 입력한 코드만 사용 처리). 예: A가 코드 X를 만든 뒤 B의 코드를 입력해 연결 → X는 10분 동안 유효(`used_at`·`revoked_at` null)로 남는다. 그 사이 C가 X를 넣으면 `INVITE_UNAVAILABLE`이라 괜찮지만, A가 10분 안에 해제하면 X가 다시 쓸 수 있는 코드가 된다.
    - A) 연결 트랜잭션에서 A와 B의 다른 유효한 코드에 `revoked_at`을 기록한다. ERD `revoked_at` 설명(210행 "재요청으로 만료된 시각")에 "연결로 무효"를 더한다 (추천)
    - B) 그대로 둔다 (해제 직후 10분 안의 드문 경우)
