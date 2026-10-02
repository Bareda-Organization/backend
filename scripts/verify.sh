#!/bin/bash
# 병합·push 전 한 명령 검증 — CI(.github/workflows/ci.yml)와 같은 백엔드 시험을 로컬에서 돈다.
# 관계자 웹·앱 검사는 각자의 저장소(web · mobile)의 scripts/verify.sh 에 있다 — 2026-10-02 저장소 분리.
#
# backend/scripts/test.sh 가 전용 DB 를 만들고 끝나면 지운다(인프라 postgres 가 떠 있어야 한다:
# `docker compose up -d postgres redis`). 실 네이버 API 시험(@Tag("live"))은 여기 포함하지 않는다 —
# 필요하면 backend 에서 `./gradlew liveTest -PtestDbUrl=...` 를 따로 돈다.
#
# 시간대를 UTC 로 고정하고 돈다(R46-CIFIX) — GitHub Actions 러너가 UTC·Linux 라서, 한국 시간대인 이 기계에서
# 그냥 돌리면 "로컬은 통과 · CI 만 실패" 가 난다. 로그 수준을 낮추는 `-PciQuiet` 도 CI 와 같이 준다 —
# 로그를 보며 실패를 들여다볼 때는 backend/scripts/test.sh 를 직접 돈다.
# 한계 — macOS 는 시각이 마이크로초(Linux 는 나노초)라 나노초 비교 결함은 이 방법으로 안 드러난다: DB 에 저장했다 읽는 값을
# 시험에서 `OffsetDateTime.now()` 로 만들면 `.truncatedTo(ChronoUnit.MICROS)` 를 붙인다.
set -euo pipefail
cd "$(dirname "$0")/.."

TZ=UTC backend/scripts/test.sh -PciQuiet
echo "verify 통과: backend"
