# 둘이서 Server

둘이서(커플 일정·메모 앱)의 API 서버예요. Kotlin, Spring Boot 4, PostgreSQL 16을 쓰고 AWS 서울 리전에 배포해요.

- 기획 문서(PRD·ERD·API)는 [DuriseoApp/Docs](https://github.com/DuriseoApp/Docs)에 있고, 서버는 그 문서를 기준으로 만들어요.
- 개발 규칙은 `CLAUDE.md`, 구조는 `docs/ARCHITECTURE.md`, 코드 규칙은 `docs/CONVENTIONS.md`, 작업 순서와 지금 위치는 `docs/PLAN.md`, 맥 설정과 AWS 준비는 `docs/SETUP.md`, 배포·운영은 `docs/DEPLOY.md`를 봐요. 끝난 단위의 진행 기록은 `docs/progress/archive/`에 있어요.

## 실행

```bash
cp .env.example .env   # 값 채우기 (docs/SETUP.md)
./gradlew build        # 컴파일 + 테스트 (Testcontainers라 Docker 필요)
```

## 작업 방법

Claude Code를 `claude --add-dir ../Docs`로 열고 `/dev-unit <단위 번호>`를 실행해요. 에이전트가 범위 확인 → 구현 → 리뷰 → PR까지 하고, 사람은 범위 승인과 PR 머지만 해요. 구현은 `server-implementer`(sonnet), 리뷰는 `server-reviewer`(sonnet)가 맡아요.
