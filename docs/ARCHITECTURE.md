# 서버 구조

코드로 알 수 있는 것(지금 있는 클래스 목록)은 적지 않는다. 여기에는 **규칙과 이유**만 둔다. 기능 내용은 Docs(PRD·ERD·API)를 본다.

## 1. 패키지

도메인별로 나눈다. 레이어별(controller/service/repository 최상위)로 나누지 않는다.

```
com.duriseo.server
├── common/          모든 도메인이 쓰는 것 (응답 형식, 오류, 웹 설정, 시간, 권한 정책)
├── config/          외부 서비스 설정값 (DuriseoProperties)
├── auth/            로그인 3종, 토큰, 세션 (FR-AUTH)
├── user/            내 정보, 프로필, 탈퇴
├── space/           공간, 구성원, 사람 색, 처음 만난 날
├── couple/          초대코드, 연결, 기록 가져오기, 해제
├── event/           일정, 일정 알림
├── anniversary/     직접 기념일, 자동 기념일 계산
├── memo/            메모, 고정, 즐겨찾기
├── notification/    알림 목록, 알림 설정, 푸시 발송
├── push/            FCM, 동기화 신호 (SYNC)
├── storage/         프로필 사진 저장소 (로컬 / S3)
└── job/             정리 작업, 알림 스케줄러
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
- 엔티티를 API 응답으로 그대로 내보내지 않는다. 항상 응답 DTO로 바꾼다.
- `common`은 어떤 도메인에도 의존하지 않는다.

## 3. 권한 검사 (가장 중요)

- API.md 2장의 R- 규칙은 `common` 아래 `SpaceAccessPolicy`(계정 단위에서 만든다) 한 곳에서 구현한다. 서비스는 이 정책을 부르고, 각 규칙마다 허용·거부 테스트를 둔다.
- 요청자의 활성 공간이 아닌 리소스는 존재해도 404 `NOT_FOUND` (ERD DB-05).
- 앱의 버튼 숨김에 기대지 않는다. 모든 변경 API는 서버에서 작성자·구성원을 확인한다 (NFR-SEC-02).

## 4. 트랜잭션과 동시성

- 여러 테이블을 바꾸는 흐름(API.md 4장)은 서비스 메서드 하나의 트랜잭션에서 처리한다.
- 두 사람이 동시에 움직일 수 있는 흐름(커플 연결, 초대코드)은 행 잠금(`SELECT … FOR UPDATE`)으로 순서를 보장한다.
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
