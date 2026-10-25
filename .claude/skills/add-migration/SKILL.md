---
name: add-migration
description: ERD 변경을 Flyway 마이그레이션으로 옮긴다. "마이그레이션 추가", "ERD 바뀐 거 반영" 같은 요청이나 /dev-unit 중 스키마가 필요할 때 쓴다.
---

# 마이그레이션 추가

1. `../Docs/docs/ERD.md`에서 바뀐 테이블·컬럼·제약·인덱스를 찾는다. Docs의 `git log -- docs/ERD.md`로 언제 무엇이 바뀌었는지 본다.
2. `src/main/resources/db/migration/`의 마지막 버전 번호를 확인하고 다음 번호로 `V{n}__{영문_snake_case}.sql`을 만든다. **머지된 파일은 절대 고치지 않는다.**
3. ERD 표의 "제약" 열을 그대로 DDL로 옮긴다.
   - 기본 키는 `uuid` (값은 애플리케이션에서 UUIDv7로 만든다)
   - 열거형은 `varchar` + `CHECK` (PostgreSQL enum 타입을 쓰지 않는다)
   - `created_at`, `updated_at`은 `timestamptz not null default now()`
   - 부분 UNIQUE·부분 INDEX는 ERD 문구 그대로
   - "서비스"로 표시된 규칙은 DDL에 넣지 않는다 (코드에서 검사)
4. 엔티티를 고치고 `ddl-auto: validate`로 스키마와 맞는지 테스트한다 (Testcontainers로 컨텍스트가 뜨면 통과).
5. 데이터가 있는 테이블을 바꾸는 변경(컬럼 삭제, 타입 축소, NOT NULL 추가)은 PR 본문 맨 위에 "⚠️ 사람 확인 필요"로 적는다.
