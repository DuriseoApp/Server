#!/usr/bin/env bash
# Claude 세션을 열 때(SessionStart 훅) 실행된다. ../Docs를 GitHub의 최신 main으로 받는다.
# Docs에서 작업 중인 변경은 절대 건드리지 않는다. 어떤 경우에도 세션을 막지 않도록 항상 exit 0.

cd "$(dirname "$0")/.." 2>/dev/null || exit 0
DOCS="../Docs"
say() { echo "[Docs 동기화] $1"; }

if [ ! -d "$DOCS/.git" ]; then
  say "../Docs 폴더가 없어요. Server와 같은 폴더에 DuriseoApp/Docs를 받아 주세요 (docs/SETUP.md)."
  exit 0
fi

if ! git -C "$DOCS" fetch --quiet origin main 2>/dev/null; then
  say "GitHub에 연결하지 못했어요. 지금 있는 Docs로 작업해요."
  exit 0
fi

branch="$(git -C "$DOCS" branch --show-current)"
dirty="$(git -C "$DOCS" status --porcelain)"

if [ "$branch" = "main" ] && [ -z "$dirty" ]; then
  if git -C "$DOCS" merge --ff-only --quiet origin/main 2>/dev/null; then
    say "Docs를 최신 main으로 받았어요 ($(git -C "$DOCS" log -1 --format=%h))."
  else
    say "Docs main을 빨리감기하지 못했어요. 직접 확인해 주세요."
  fi
else
  say "Docs가 '$branch' 브랜치이거나 저장 안 한 변경이 있어 그대로 뒀어요. 기준 문서는 origin/main이에요."
fi
exit 0
