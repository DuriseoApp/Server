# 둘이서 Server 레포 규칙

둘이서(커플 일정·메모 앱)의 API 서버예요. Kotlin + Spring Boot 4 + PostgreSQL 16, AWS 서울 리전에 배포해요.

## 언어

- 사용자에게는 항상 한국어로 답한다.
- 문서·주석·커밋 메시지도 한국어로 쓴다. 코드 식별자는 영어.

## 기준 문서

기획 문서는 `../Docs` 레포(DuriseoApp/Docs의 main)에 있다. **이 문서와 코드가 다르면 문서가 맞다.** 세션은 `claude --add-dir ../Docs`로 연다. 세션을 열 때 `scripts/sync-docs.sh`가 Docs를 최신으로 받는다.

- 요구사항: `../Docs/docs/PRD.md` (커밋·PR·테스트 이름에 FR ID를 쓴다)
- 데이터: `../Docs/docs/ERD.md` (테이블, 수명주기, `DB-` 결정)
- API: `../Docs/docs/API.md` (경로, 권한 규칙 `R-`, 흐름, 오류 코드, 응답 형식 1.1, 동기화 신호, 알림 작업)

문서에 없는 기능·경로·오류 코드·컬럼은 만들지 않는다. 문서가 틀려 보이거나 비어 있으면 **작업을 멈추고 보고한다.** 문서를 고치는 건 Docs 레포의 `/prd-sync`가 한다 (이 레포에서 Docs를 고치지 않는다).

## 이 레포의 문서

| 문서 | 내용 |
| --- | --- |
| `docs/ARCHITECTURE.md` | 패키지 구조, 의존 규칙, 권한 검사, 트랜잭션, 오류 모델, 시간 처리 |
| `docs/CONVENTIONS.md` | 이름, DTO, 테스트, 커밋·PR 규칙 |
| `docs/PLAN.md` | 작업 단위와 순서. 지금 어디까지 했는지 |
| `docs/SETUP.md` | 맥 설정, 환경변수, AWS 서버 처음 준비 |
| `docs/DEPLOY.md` | 배포, 서버 로그 보기, 되돌리기, 백업 복구 (`deploy/`, `.github/workflows/deploy.yml`) |
| `docs/progress/` | 작업 단위별 진행 기록 |

## 가장 중요한 규칙

1. **권한 검사는 API.md 2장 R- 규칙을 `SpaceAccessPolicy` 한 곳에서 한다.** 다른 공간의 리소스는 403이 아니라 404. 이 규칙 위반은 리뷰에서 항상 Critical이다 (PRD NFR-SEC-02).
2. **스키마는 Flyway 마이그레이션으로만 바꾼다.** 이미 머지된 마이그레이션 파일은 고치지 않고 새 버전을 추가한다. `ddl-auto`는 `validate` 그대로.
3. **응답 형식은 API.md 1.1을 따른다.** 오류는 `ApiException(ErrorCode)`를 던지고 `GlobalExceptionHandler`가 바꾼다. 컨트롤러에서 오류 응답을 직접 만들지 않는다.
4. **비밀값을 코드·설정 파일·문서·커밋·로그에 쓰지 않는다.** 환경변수로만 받는다 (`.env.example`).

## 빌드와 확인

- 코드를 고친 뒤 `./gradlew build`로 컴파일과 테스트를 확인한다. 테스트는 Testcontainers로 PostgreSQL을 띄우므로 Docker가 필요하다.
- 맥에 Docker가 없으면 `./gradlew compileKotlin compileTestKotlin`까지 확인하고, PR을 연 뒤 `gh pr checks`로 CI 결과를 확인한다. **CI가 초록불이 아니면 작업이 끝난 게 아니다.**

## Git

- `main`에 직접 커밋·push하지 않는다. 작업 브랜치는 `feat/<단위>-<내용>`, `fix/…`, `chore/…`.
- `git push --force`, `git reset --hard`, `rm -rf`를 쓰지 않는다.
- PR은 에이전트가 열고, **머지는 사람이 GitHub에서 한다** (`gh pr merge` 금지).
