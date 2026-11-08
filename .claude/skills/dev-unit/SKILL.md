---
name: dev-unit
description: 둘이서 서버의 작업 단위 하나(docs/PLAN.md)를 구현하고 리뷰를 거쳐 PR을 연다. "/dev-unit 1", "단위 2 시작해줘" 같은 요청에 쓴다.
---

# 작업 단위 진행

리드(사용자가 연 메인 세션)가 실행한다. 사람은 **범위 확인 한 번과 PR 머지 한 번**만 한다.

## 1. 준비

1. `git switch main && git pull`로 최신을 받는다. Docs는 세션 시작 훅이 최신으로 받았는지 확인한다 (`../Docs`가 main이고 최신인지).
2. `docs/PLAN.md`에서 단위 `<N>`의 범위와 기준을 읽는다. 앞 단위가 머지되지 않았으면 멈추고 묻는다.
3. 브랜치를 만든다: `git switch -c feat/<N>-<영문 이름>`.
4. 앞 단위의 진행 기록이 `docs/progress/`에 남아 있으면(그 단위 PR이 머지됐다는 뜻) `git mv`로 `docs/progress/archive/`에 옮기고 `docs/PLAN.md`의 그 단위 상태를 "완료"로 고친다. 옮기기 전에 그 기록의 "남은 일"에서 아직 안 끝난 것을 `docs/PLAN.md` 해당 단위의 "앞 단위에서 넘어온 일"로 옮기고, Docs에 올릴 후속은 PLAN.md 끝 "Docs 후속"에 모은다.

## 2. 범위 확정 (사람 확인)

1. `docs/progress/_TEMPLATE.md`를 복사해 `docs/progress/unit-<N>.md`를 만든다. 그 단위의 "앞 단위에서 넘어온 일"(`docs/PLAN.md`)도 범위 표에 넣는다.
2. Docs에서 이 단위에 해당하는 FR·AC, API 경로, R- 규칙, ERD 테이블을 모두 찾아 범위 표에 적는다. AC마다 테스트 이름을 미리 정한다.
3. 문서끼리 다르거나 비어 있는 곳이 있으면 여기서 멈추고 사용자에게 묻는다 (Docs 수정은 Docs 레포의 `/prd-sync`로).
4. 사용자에게 범위 표를 보여 주고 "맞으면 **start**"라고 묻는다. 승인 전에는 코드를 쓰지 않는다.

## 3. 구현

1. `server-implementer` 서브에이전트에게 진행 기록 경로를 주고 구현을 맡긴다. 범위가 크면 도메인별로 나눠 순서대로 맡긴다 (같은 파일을 동시에 고치지 않게).
2. 끝나면 `./gradlew build`(Docker 없으면 컴파일만)를 직접 돌려 확인한다.

## 4. 리뷰 반복

1. `server-reviewer`에게 단위 번호를 주고 검사를 맡긴다. 리뷰는 sonnet 1회 + Major 수정분만 리드가 확인한다. 동시성·보안이 큰 단위(예: 단위 6 알림 스케줄러)는 리드가 opus로 지정해 부를 수 있다.
2. Critical·Major는 `server-implementer`에게 고치게 한다. Major 수정분은 리드가 확인하고, Critical이 있었으면 고친 뒤 다시 리뷰한다. 결과를 진행 기록의 "리뷰 기록"에 남긴다.
3. Critical이 3번 리뷰를 거쳐도 남으면 멈추고, 남은 문제를 PR 본문 맨 위 "⚠️ 사람 확인 필요"에 적는다.

## 5. PR

1. `docs/PLAN.md`의 상태를 "PR 완료"로, 진행 기록 상태를 바꾼다.
2. 커밋: `feat(<도메인>): <단위 이름> (<대표 FR>)`. push 후 `gh pr create --base main`.
3. PR 본문: 요약, 다룬 FR·AC·R ID와 테스트 이름 표, 리뷰 결과, 문서와 다르게 한 점(없어야 정상), 새 환경변수(있으면 `docs/SETUP.md` 반영 여부), 남은 일.
4. `gh pr checks --watch`로 CI를 기다린다. 실패하면 고쳐서 다시 push 한다.
5. **머지하지 않는다.** 사용자에게 PR 링크와 결과를 짧게 알린다.
