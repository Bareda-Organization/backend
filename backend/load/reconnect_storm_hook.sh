#!/usr/bin/env bash
# L-2 — 전원 동시 재접속(scenario7). prod_sized_round.sh 가 BACKGROUND_DELAY(40초)에 부른다 — 측정 구간 시작(r3_mixed.sh 의 CPU0)보다 몇 초 뒤라, 이 세션 2,000개가 r3 의 시청 세션과 같은 구간(60초)에 올라온다.
# 세션이 다 서고 위치 부하(측정 시작 + 75초) · 확정 배치(+105초, 드레인 약 70초)가 자리를 잡은 뒤 — 훅 시작 CLOSE_AFTER_SEC(기본 180초) 뒤에 전원을 동시에 닫는다. 닫는 시각은 results/<라벨>_storm_meta.json 에 남긴다.
# 닫은 뒤 첫 방송을 기다리므로 위치 부하(R3_POS_DUR)는 닫는 시각 + STORM_HOLD_AFTER_SEC 보다 길어야 한다.
set -uo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; L="${ROUND_LABEL:?}"; R="$DIR/results"
CLOSE_AT_MS=$(( $(date +%s) * 1000 + ${CLOSE_AFTER_SEC:-180} * 1000 ))
echo "{\"close_at_ms\": $CLOSE_AT_MS}" > "$R/${L}_storm_meta.json"
cd "$DIR/k6" && k6 run --address localhost:6570 --summary-trend-stats="avg,min,med,max,p(90),p(95),p(99)" \
    -e STORM_TOKENS="$R/${L}_storm_tokens.json" -e CLOSE_AT_MS="$CLOSE_AT_MS" -e STORM_RAMP_SEC="${STORM_RAMP_SEC:-55}" -e API_PORT=6570 \
    --summary-export="$R/${L}_storm.json" scenario7_reconnect_storm.js > "$R/${L}_storm.log" 2>&1
echo "storm k6 exit=$?"
grep -a 'PRECHECK' "$R/${L}_storm.log" || echo "PRECHECK 줄 없음"
