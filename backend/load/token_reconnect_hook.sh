#!/usr/bin/env bash
# L-6 — 토큰 만료 전 계획된 재접속(scenario8). prod_sized_round.sh 가 r3_mixed.sh 시작 40초 뒤(BACKGROUND_DELAY=40)에 부른다.
# 세션 2,000개가 STORM_RAMP_SEC(100초) 동안 올라오고, 재접속 일정은 훅 시작 START_AFTER_SEC(150초) 뒤 — 위치 부하 · 확정 배치 드레인이 자리 잡은 뒤 — 부터다.
# 환경변수: RATE_FACTOR(1 또는 5) · RECONNECT_SEC(실제 비율 600 · 5배 240) · 위치 부하(R3_POS_DUR)는 일정이 끝난 뒤에도 방송이 와야 하므로 훅 시작 + START_AFTER_SEC + RECONNECT_SEC 보다 길게 준다.
set -uo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; L="${ROUND_LABEL:?}"; R="$DIR/results"
START_AT_MS=$(( $(date +%s) * 1000 + ${START_AFTER_SEC:-150} * 1000 ))
echo "{\"start_at_ms\": $START_AT_MS, \"rate_factor\": ${RATE_FACTOR:-1}, \"reconnect_sec\": ${RECONNECT_SEC:-600}}" > "$R/${L}_reconnect_meta.json"
cd "$DIR/k6" && k6 run --address localhost:6571 --summary-trend-stats="avg,min,med,max,p(90),p(95),p(99)" \
    -e STORM_TOKENS="$R/${L}_storm_tokens.json" -e START_AT_MS="$START_AT_MS" -e RECONNECT_SEC="${RECONNECT_SEC:-600}" -e RATE_FACTOR="${RATE_FACTOR:-1}" -e STORM_RAMP_SEC="${STORM_RAMP_SEC:-100}" -e API_PORT=6571 \
    --summary-export="$R/${L}_reconnect.json" scenario8_token_reconnect.js > "$R/${L}_reconnect.log" 2>&1
echo "reconnect k6 exit=$?"
grep -a 'PRECHECK' "$R/${L}_reconnect.log" || echo "PRECHECK 줄 없음"
