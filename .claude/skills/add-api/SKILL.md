---
name: add-api
description: API.md에 있는 경로 하나를 서버에 추가하거나 고친다. "GET /memos 만들어줘", "API 하나 추가" 같은 작은 작업에 쓴다. 작업 단위 전체는 /dev-unit을 쓴다.
---

# API 하나 추가

1. `../Docs/docs/API.md`에서 경로의 행(메서드, 설명, 권한 R-, FR)과 관련 흐름(4장), 오류 코드(5장)를 읽는다. 표에 없는 경로면 멈추고 묻는다.
2. 도메인 패키지에 만든다 (`docs/ARCHITECTURE.md` 1장).
   - `api/`: 컨트롤러 메서드와 요청·응답 DTO. 경로에 `/api/v1`을 쓰지 않는다.
   - `application/`: 서비스 메서드. 트랜잭션 경계. `SpaceAccessPolicy`로 권한 검사.
   - `domain/`: 엔티티·리포지토리 변경이 필요하면 `/add-migration`을 먼저.
3. 응답은 API.md 1.1: 단건은 그대로, 목록은 `ListResponse`·`PageResponse`, 빈 성공은 204, 오류는 `ApiException`.
4. 테스트: 정상 흐름, R- 규칙 허용·거부, API.md에 적힌 오류 코드마다 하나. 이름에 ID를 넣는다.
5. `./gradlew build`로 확인한다.
