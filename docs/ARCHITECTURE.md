# 서버 구조

코드로 알 수 있는 것(지금 있는 클래스 목록)은 적지 않는다. 여기에는 **규칙과 이유**만 둔다. 기능 내용은 Docs(PRD·ERD·API)를 본다.

## 1. 패키지

도메인별로 나눈다. 레이어별(controller/service/repository 최상위)로 나누지 않는다.

```
com.duriseo.server
├── common/          모든 도메인이 쓰는 것 (응답 형식, 오류, 웹 설정, 시간 `UserZones`, 권한 정책, UUIDv7·정렬)
├── config/          외부 서비스 설정값 (DuriseoProperties)
├── auth/            로그인 3종, 토큰, 세션 (FR-AUTH)
├── user/            내 정보, 프로필, 탈퇴
├── space/           공간, 구성원, 사람 색, 처음 만난 날
├── couple/          초대코드, 연결, 기록 가져오기, 해제
├── event/           일정, 일정 알림
├── anniversary/     직접 기념일, 자동 기념일 계산
├── memo/            메모, 고정, 즐겨찾기
├── notification/    알림 행 만들기·삭제, 목록·읽음 API (설정 API는 `user/`의 `/me/notification-settings`)
├── push/            FCM 발송기, 동기화 신호 (SYNC), 도메인 이벤트 리스너 (단위 6)
├── storage/         프로필 사진 저장소 (로컬 / S3) — 단위 7에서 만든다
└── job/             정리 작업, 알림 스케줄러 — 단위 6에서 만든다
```

도메인 패키지 안은 이렇게 나눈다. 작은 도메인은 필요한 것만 만든다.

```
<domain>/
├── api/          @RestController, 요청·응답 DTO
├── application/  서비스 (트랜잭션 경계)
└── domain/       엔티티, 리포지토리, 도메인 규칙
```

## 2. 의존 규칙

- `api` → `application` → `domain`. 거꾸로 의존하지 않는다.
- 다른 도메인은 그 도메인의 `application` 서비스로만 부른다. 다른 도메인의 리포지토리를 직접 쓰지 않는다.
- `event`·`anniversary`·`memo`는 `couple`을 알지 않는다. `couple`이 이들의 `*ContentService`(`EventContentService`, `AnniversaryContentService`, `MemoContentService`)를 직접 불러 기록 확인·가져오기·일정 알림 삭제를 한다. `user`는 `couple`·`notification`·`auth`와 이 `*ContentService`를 부른다 (내 정보의 `importCounts`, 탈퇴).
- **예외: `push/`와 `job/`은 다른 도메인의 테이블(devices, auth_sessions, notification_settings, space_members, notifications, users, spaces, invite_codes 등)을 네이티브 SQL로 읽고 지운다.** 알림·정리 작업은 여러 도메인을 가로지르고, 도메인마다 서비스 메서드를 늘리면 오히려 조건(유효 세션, 30일 경계 등)이 흩어지기 때문이다. `SpaceAccessPolicy`와 같은 방식이다. 이 SQL은 `PushStore`·`CleanupStore`와 각 작업 클래스 안에만 둔다. 사용자·공간을 바꾸는 흐름(게스트 정리의 탈퇴 처리, 알림 행 만들기)은 해당 도메인 서비스(`WithdrawalService`, `NotificationService`)를 부른다.
- 엔티티를 API 응답으로 그대로 내보내지 않는다. 항상 응답 DTO로 바꾼다.
- `common`은 어떤 도메인에도 의존하지 않는다.

## 3. 권한 검사 (가장 중요)

- API.md 2장의 R- 규칙은 `common/access/SpaceAccessPolicy` 한 곳에서 구현한다. `common`이 도메인을 모르도록 엔티티·리포지토리 대신 `EntityManager` 네이티브 쿼리로 읽고, 일정·기념일 규칙은 엔티티가 아니라 값(`authorId` 등)을 받는다. 서비스는 이 정책을 부르고, 각 규칙마다 허용·거부 테스트를 둔다.
- 요청자의 활성 공간이 아닌 리소스는 존재해도 404 `NOT_FOUND` (ERD DB-05).
- 앱의 버튼 숨김에 기대지 않는다. 모든 변경 API는 서버에서 작성자·구성원을 확인한다 (NFR-SEC-02).

## 4. 트랜잭션과 동시성

- 여러 테이블을 바꾸는 흐름(API.md 4장)은 서비스 메서드 하나의 트랜잭션에서 처리한다.
- 두 사람이 동시에 움직일 수 있는 흐름(커플 연결, 초대코드)은 행 잠금(`SELECT … FOR UPDATE`)으로 순서를 보장한다.
- **잠금 순서는 모든 흐름에서 같다: users(UUID 오름차순) → invite_codes → spaces.** 공간 행을 잡은 채 사용자 행을 잠그는 흐름을 두지 않는다. 사용자 행을 `PESSIMISTIC_WRITE`(PostgreSQL에서는 `FOR NO KEY UPDATE`)로 잡는 해제·탈퇴, 사용자 행을 `FOR SHARE`로 먼저 잡는 일정 쓰기가 모두 같은 순서를 지켜야 서로 반대로 기다리지 않는다. 외래 키 검사는 참조되는 행에 `FOR KEY SHARE`를 잡는데, 이는 `FOR NO KEY UPDATE`와 충돌하지 않지만 `FOR UPDATE`와는 충돌하므로 순서를 어긴 흐름이 `FOR UPDATE`를 쓰게 되는 때를 대비한다. 연결 해제와 탈퇴는 본인과 상대 사용자 행을 UUID 순서로 먼저 잠그고 공간 행을 잠근다 (`UserService.lockForUpdateWithPartner`). 같은 이유로 `invite_code_attempts` 같은 외래 키 행을 `REQUIRES_NEW`로 넣지 않는다 (바깥 트랜잭션이 잡은 사용자 행을 기다리는 자기 교착).
- **일정·기념일·메모 쓰기는 공간 행을 잠그지 않는다.** 수정·삭제와 일정 알림 설정은 그 행만 `FOR UPDATE`로 잠근다(`EventLocker`, `AnniversaryLocker`, `MemoLocker`). 만들 때는 행을 넣기 전에 사용자 행을 UUID 순서로 `FOR SHARE`로 먼저 잡는다(`UserService.lockShare`). 일정은 요청자와 커플 상대, 기념일·메모는 요청자만 잡는다. 메모는 상대에게 알림 행을 만들 수 있지만(`POST /memos`, 고정) 해제·탈퇴가 두 사람의 사용자 행을 모두 잠그므로 요청자 행 하나로 한 줄이 선다. 메모 고정도 알림 행을 만들 수 있어 요청자 행을 `FOR SHARE`로 먼저 잡은 뒤 메모 행을 `FOR UPDATE`로 잠근다(users → 콘텐츠 행).
  - 이 사전 잠금이 하는 일은 "해제·탈퇴와 한 줄로 세우기"다. `FOR SHARE`는 해제·탈퇴의 `FOR NO KEY UPDATE`와 충돌해 그 트랜잭션이 끝나기를 기다리고, 사용자 행을 공간 행보다 먼저 잡는 순서도 지킨다. `FOR KEY SHARE`는 `FOR NO KEY UPDATE`와 충돌하지 않아 해제를 기다리지 않으므로 쓰지 않는다(CI 동시 테스트에서 발견).
  - 일정은 잡은 뒤 활성 공간·상대를 다시 읽어 바뀌었으면(그 사이 연결 해제가 커밋됨) `PartnerLockRetry`로 롤백하고 다시 한다. `PartnerLockRetry`는 해제·탈퇴·Kakao 웹훅·일정 만들기가 쓰고, 최대 3번 다시 시도한다. 다 쓴 뒤에도 상대가 바뀌면 순서 밖에서 잠가 드물게 교착 판정 500이 날 수 있다.
  - 일정 알림 설정(`PUT`·`DELETE /events/{id}/reminder`)은 R-01 전에 요청자 행을 `FOR SHARE`로 잡아 해제 정리 뒤에 알림 행이 남지 않게 한다. `PATCH /me`(시간대 재계산)는 첫 줄에서 사용자 행을 `FOR UPDATE`로 잡아 같은 사용자의 일정 알림 설정과 한 줄로 선다.
  - 사용자 행의 순서는 PostgreSQL `uuid` 비교(부호 없는 바이트 순)와 같게 `sortedAsPostgres`(`common/persistence/UuidOrder.kt`)로 맞춘다.
- **도메인 이벤트:** 쓰는 트랜잭션 안에서 발행하고(`CoupleLinked`, `CoupleUnlinked`, `SpaceUpdated`, `UserWithdrawn`, `EventCreated`·`Updated`·`Deleted`, `AnniversaryCreated`·`Updated`·`Deleted`, `MemoCreated`·`Updated`·`Deleted`), 듣는 쪽은 `@TransactionalEventListener(AFTER_COMMIT)`로 받는다. 듣는 쪽은 Kakao 연결 끊기(`KakaoUnlinkListener`)와 푸시·SYNC(`push/PushEventListener`)다. 푸시 쪽 리스너는 DB를 새 트랜잭션(`REQUIRES_NEW`, `PushStore`)으로 읽고, 어떤 예외도 밖으로 던지지 않는다(응답에 영향 없음). 탈퇴로 인한 해제는 `CoupleUnlinked`만 듣고 `UserWithdrawn`은 푸시 쪽에서 듣지 않는다. `UserTimeZoneChanged`는 같은 트랜잭션 안에서 종일 일정 알림을 새 시간대로 다시 계산한다(`EventReminderService`).
- 동기화 신호·푸시는 **커밋이 끝난 뒤** 보낸다 (`@TransactionalEventListener(phase = AFTER_COMMIT)`). 트랜잭션 안에서 외부 호출을 하지 않는다. 카카오 연결 끊기도 커밋 뒤에 한다.

## 5. 오류 모델

- 서비스는 `ApiException(ErrorCode, message, details)`를 던진다. `ErrorCode`는 API.md 5장과 1:1이다. 새 코드는 API.md에 먼저 생긴 뒤에만 추가한다.
- `GlobalExceptionHandler`가 모든 예외를 오류 객체 `{code, message, details, traceId}`로 바꾼다. 처리하지 못한 예외는 500 `INTERNAL_ERROR`이고 내부 메시지를 응답에 넣지 않는다.
- 없는 경로와 지원하지 않는 HTTP 메서드는 모두 404 `NOT_FOUND`로 답한다 (API.md에 405용 코드가 없고, 경로 존재를 드러내지 않기 위해서).
- `message`는 로그용 영어 한 줄이다. 사용자 문구는 앱이 `code`로 정한다.

## 6. 시간

- JVM 기본 시간대와 JDBC·Jackson 시간대는 UTC다. 일시는 `Instant`(DB `timestamptz`), 날짜만 있는 값(종일 일정, 기념일, 메모 날짜, 처음 만난 날)은 `LocalDate`(DB `date`)로 다룬다 (NFR-TIME-01).
- "오늘", "오전 9시"처럼 사용자 기준이 필요한 계산은 `users.time_zone`으로 한다. 서버 시간대를 쓰지 않는다.
- 현재 시각은 `java.time.Clock` 빈을 주입받아 쓴다 (테스트에서 고정하려고). `Instant.now()`를 직접 부르지 않는다.

## 7. 환경

| 프로필 | 어디서 | DB |
| --- | --- | --- |
| (없음) | 테스트, 맥 | Testcontainers 또는 환경변수 |
| `dev` | AWS 서버의 개발 컨테이너 | 같은 서버 PostgreSQL의 개발 DB |
| `prod` | AWS 서버의 운영 컨테이너 | 같은 서버 PostgreSQL의 운영 DB |

설정값은 모두 환경변수로 받는다 (`.env.example`, `docs/SETUP.md`). 배포 구성은 `deploy/`(Docker Compose, Caddy, 배포·백업 스크립트, CloudFormation)와 `.github/workflows/deploy.yml`에 있고, 운영 방법은 `docs/DEPLOY.md`를 본다.

- HTTPS는 앞단 Caddy가 끝낸다. 앱은 컨테이너 안에서 HTTP 8080만 연다.
- 서버 1대에 자바 앱 2개와 DB가 있어 메모리가 작다 (앱 컨테이너 512~640MB). 큰 데이터를 한 번에 메모리에 올리지 않는다 (목록은 커서 페이지, 이미지는 스트림).
- 앱이 AWS를 써야 하면(단위 7 S3) 키를 받지 않고 서버 IAM 역할(인스턴스 프로파일)의 권한을 쓴다. 권한은 `deploy/aws/duriseo-stack.yml`의 `ServerRole`에 추가한다.
