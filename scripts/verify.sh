#!/bin/bash
# 병합·push 전 한 명령 검증 — CI(.github/workflows/ci.yml)와 같은 검사를 로컬에서 돈다.
#
# 사용
#   scripts/verify.sh                 백엔드 + 웹 + Flutter 전부
#   scripts/verify.sh web flutter     골라서(backend · web · flutter)
#
# 백엔드는 backend/scripts/test.sh 가 전용 DB 를 만들고 끝나면 지운다(인프라 postgres 가 떠 있어야 한다:
# `docker compose up -d postgres redis`). 실 네이버 API 시험(@Tag("live"))은 여기 포함하지 않는다 —
# 필요하면 backend 에서 `./gradlew liveTest -PtestDbUrl=...` 를 따로 돈다.
#
# 웹·Flutter 는 실서버 계약 시험(백엔드가 떠 있어야 하는 시험)을 뺀다 — CI 와 같다.
#   웹      파일명이 realBackend 인 시험(주소 없이 돌면 로딩에서 던진다)
#   Flutter @Tags(['real_backend']) 시험 — 각 패키지 dart_test.yaml 에 태그 선언이 있어야 걸러진다
set -euo pipefail
cd "$(dirname "$0")/.."

WEB_DIR=frontend/apps/academy-web
FLUTTER_DIRS=(frontend/packages/baraeda_core frontend/packages/baraeda_ui frontend/apps/manager-app frontend/apps/parent-app)
WEB_EXCLUDE='**/*[Rr]ealBackend*.test.ts'

verify_backend() {
  backend/scripts/test.sh
}

verify_web() {
  (
    cd "$WEB_DIR"
    [ -d node_modules ] || npm ci --no-audit --no-fund
    npx next typegen   # tsc 가 읽는 라우트 타입(LayoutProps 등)을 만든다
    npx tsc --noEmit
    npm run lint
    npx vitest run --exclude "$WEB_EXCLUDE"
  )
}

verify_flutter() {
  local dir
  for dir in "${FLUTTER_DIRS[@]}"; do
    echo "== $dir"
    (
      cd "$dir"
      flutter pub get
      if grep -q build_runner pubspec.yaml; then
        dart run build_runner build --delete-conflicting-outputs
      fi
      flutter analyze
      flutter test --exclude-tags real_backend
    )
  done
}

targets=("$@")
[ ${#targets[@]} -gt 0 ] || targets=(backend web flutter)

for target in "${targets[@]}"; do
  case "$target" in
    backend | web | flutter) echo "===== $target"; "verify_$target" ;;
    *) echo "알 수 없는 대상: $target (backend · web · flutter)"; exit 2 ;;
  esac
done
echo "verify 통과: ${targets[*]}"
