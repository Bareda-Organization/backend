#!/usr/bin/env bash
# R1 한 회차 — 실시간 세션 수 (부하 한계 측정 계획 §3 R1).
# VU 하나 = WebSocket 세션 하나. 목표는 학부모·학생 2,000 세션(§1) 이고, 여기서 구독하는 채널은
# /topic/admin/live 다 — 관제 채널은 학원 전체 이벤트를 다 받으므로 학부모 채널보다 팬아웃이 넓다.
# 즉 이 회차는 "세션 수 한계" 의 상한을 보수적으로(불리하게) 재는 것이다.
#
# 실행: ./r1_round.sh <VU> [ramp] [hold]
set -euo pipefail
VUS="${1:?VU 필요}"
RAMP="${2:-60}"
HOLD="${3:-60}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROM="http://localhost:18080/actuator/prometheus"
TOTAL=$((RAMP + HOLD + 25))

echo "== R1 VU=${VUS} ramp=${RAMP}s hold=${HOLD}s =="
"$DIR/sampler.sh" "r1_vu${VUS}" "$TOTAL" > /dev/null &
SAMP=$!
cpu_ns() { curl -sf "$PROM" | awk '$0 ~ "^process_cpu_time_ns_total[ {]" {print $NF}'; }
CPU0="$(cpu_ns)"; T0="$(date +%s)"

cd "$DIR/k6"
set +e
k6 run -e SCENARIO3_TARGET_VUS="$VUS" -e SCENARIO3_RAMP_SEC="$RAMP" -e SCENARIO3_HOLD_SEC="$HOLD" \
    --summary-export="$DIR/results/r1_vu${VUS}.json" scenario3_admin_fanout.js \
    > "$DIR/results/r1_vu${VUS}.log" 2>&1
K6=$?
set -e
CPU1="$(cpu_ns)"; T1="$(date +%s)"
wait $SAMP 2>/dev/null || true
"$DIR/snapshot.sh" "r1_vu${VUS}_after" > /dev/null

echo "k6 종료 코드=${K6}"
python3 - "$DIR/results/r1_vu${VUS}.json" "$VUS" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))['metrics']; vus = int(sys.argv[2])
cf = d.get('ws_connect_failures', {}).get('count', 0)
sf = d.get('ws_subscribe_failures', {}).get('count', 0)
recv = d.get('ws_messages_received', {}).get('count', 0)
sess = d.get('ws_sessions', {}).get('count', 0)
ck = d.get('checks', {})
lat = d.get('ws_message_latency_ms', {})
print(f"  세션 시도 {sess} · 연결 실패 {cf} ({100*cf/max(sess,1):.2f}%) · 구독 실패 {sf} · 체크 통과율 {ck.get('value')}")
print(f"  방송 수신 {recv}건 · 지연 p95={lat.get('p(95)')}ms avg={lat.get('avg')}ms max={lat.get('max')}ms")
print(f"  ws_connecting p95={d.get('ws_connecting',{}).get('p(95)')}ms · 세션시간 avg={d.get('ws_session_duration',{}).get('avg')}ms")
PY
python3 "$DIR/summarize_sample.py" "$DIR/results/sample_r1_vu${VUS}.csv"
python3 - "$CPU0" "$CPU1" "$T0" "$T1" <<'PY'
import sys
c = (float(sys.argv[2]) - float(sys.argv[1])) / 1e9
w = max(int(sys.argv[4]) - int(sys.argv[3]), 1)
print(f"  JVM CPU {c:.1f}초 / 벽시계 {w}초 = 평균 {c/w:.2f} 코어")
PY
