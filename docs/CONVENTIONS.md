# 코드 규칙

## 이름

- API 경로·필드·오류 코드·enum 값은 API.md에 적힌 그대로 쓴다. JSON 필드는 camelCase, DB 컬럼은 snake_case (`is_together` ↔ `isTogether`).
- 엔티티 이름은 ERD 테이블의 단수형 (`events` → `Event`).
- 요청 DTO는 `<동작>Request`, 응답 DTO는 `<리소스>Response`.
- 컨트롤러 경로에 `/api/v1`을 쓰지 않는다 (`WebConfig`가 붙인다).

## DTO와 검증

- 입력 길이·필수값은 DTO에 Bean Validation으로 건다 (`@field:NotBlank`, `@field:Size(max = 10)`). 한도는 PRD NFR-LIMIT-01을 따른다.
- 날짜 범위(1950-01-01 ~ 2050-12-31)처럼 API.md가 별도 오류 코드를 정한 검사는 서비스에서 하고 해당 코드로 던진다 (`DATE_OUT_OF_RANGE`).
- 목록 응답은 `ListResponse`·`PageResponse`·`CountedPageResponse`만 쓴다. 배열을 그대로 돌려주지 않는다.
- 돌려줄 내용이 없는 성공은 `ResponseEntity.noContent()` (204).

## 테스트

- 테스트 이름에 FR·R ID를 넣는다: `` `FR-CAL-03 AC2 상대의 개인 일정은 수정할 수 없다` ``, `` `R-02 작성자가 아니면 403 NOT_AUTHOR` ``.
- PRD의 AC 하나에 테스트 하나 이상. API.md의 R- 규칙 하나에 허용·거부 테스트 둘 이상.
- DB가 필요한 테스트는 `TestcontainersConfig`를 `@Import` 한다. H2 같은 다른 DB로 대신하지 않는다 (PostgreSQL 전용 기능을 쓴다).
- 시간이 걸리는 테스트는 `Clock`을 고정해서 쓴다.

## 마이그레이션

- 파일: `src/main/resources/db/migration/V{n}__{설명}.sql` (ERD 1장). 설명은 영어 snake_case.
- 한 PR에 한 버전. 머지된 파일은 고치지 않는다.
- ERD의 "제약" 열은 그대로 DDL로, "서비스" 규칙은 코드로.

## 커밋과 PR

- 커밋 메시지: `feat(auth): 게스트 시작 (FR-AUTH-01)`처럼 `type(도메인): 내용 (ID)`. type은 feat, fix, chore, test, docs, refactor.
- PR 하나는 작업 단위 하나(`docs/PLAN.md`). PR 본문에 다룬 FR·R ID, 테스트 목록, 문서와 다르게 한 점(없어야 정상), 남은 일을 적는다.
- 리뷰 에이전트(`server-reviewer`)가 Critical 0이라고 할 때까지 고친 뒤 사람에게 넘긴다.
