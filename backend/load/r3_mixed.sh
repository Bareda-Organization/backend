#!/usr/bin/env bash
# R3 — 등원 피크 혼합 (부하 한계 측정 계획 §3 R3).
# 네 가지를 겹쳐 돌린다: 실시간 세션 N개(관제 채널) · 위치 20 req/s(버스 100대) ·
# 확정 배치 100 회차 동시 도래 · 그 배치가 만드는 방송.
#
# 겹치는 것이 요점이다 — 위치 1건은 학원 채널과 관제 채널 양쪽으로 나가므로(PositionBroadcastListener)
# 세션 N개가 붙어 있으면 초당 방송이 20 × N 건이 된다. 갈래별로 따로 재면 이 곱이 안 생긴다.
#
# 실행: ./r3_mixed.sh <세션수> [배율]
#   배율 2 를 주면 위치·배치를 2배로 올린다(계획 §3 R4 여유 확인용).
set -euo pipefail
SESSIONS="${1:?세션 수 필요}"
MULT="${2:-1}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PG="docker exec -i school-bus-postgres-1 psql -U schoolbus -d schoolbus_load -q"
PGQ="docker exec school-bus-postgres-1 psql -U schoolbus -d schoolbus_load -t -A -c"
PROM="http://localhost:18080/actuator/prometheus"
TAG="r3_s${SESSIONS}_x${MULT}"

POS_VUS=$((100 * MULT))
RAMP=60; HOLD=180; POS_DUR=120; TOTAL=$((RAMP + HOLD + 30))

echo "== R3 세션=${SESSIONS} · 위치 VU=${POS_VUS}(5초 주기 = $((POS_VUS / 5)) req/s) · 배치=$((100 * MULT))회차 =="

# ① 위치용 회차를 심는다(moving 상태, 기사 계정 포함).
$PG -v n="$POS_VUS" -t -A -F',' < "$DIR/sql/scenario2_prep.sql" | grep -v '^$' > "$DIR/k6/${TAG}_runs.csv"
echo "위치용 회차 $(wc -l < "$DIR/k6/${TAG}_runs.csv")건"

# ② 배치 대상은 R0 이 심어 둔 회차다 — confirm_at 이 미래라 아직 idle 이다. 회차마다 먼저 되돌려
#    같은 조건에서 다시 잰다(안 되돌리면 두 번째 라운드가 "동시 도래" 가 아니라 "재확정" 이 된다).
$PG -v ON_ERROR_STOP=1 < "$DIR/sql/r0_reset_runs.sql" > /dev/null
BATCH_N=$((100 * MULT))
IDLE_BEFORE=$($PGQ "SELECT count(*) FROM run r JOIN bus b ON b.id=r.bus_id WHERE b.bus_no LIKE 'LOADCAP-%' AND r.status='idle'")
echo "배치 대기 회차 ${IDLE_BEFORE}건 중 ${BATCH_N}건을 도래시킨다"

cpu_ns() { curl -sf "$PROM" | awk '$0 ~ "^process_cpu_time_ns_total[ {]" {print $NF}'; }
metric() { curl -sf "$PROM" | awk -v n="$1" '$0 ~ "^"n"[ {]" {s+=$NF; k=1} END {if(!k) print 0; else printf "%.6g", s}'; }

"$DIR/sampler.sh" "$TAG" "$TOTAL" > /dev/null &
SAMP=$!
CPU0="$(cpu_ns)"; T0="$(date +%s)"
OUT0="$(metric executor_completed_tasks_total)"
LAG_C0="$(metric schoolbus_run_confirmation_lag_seconds_count)"; LAG_S0="$(metric schoolbus_run_confirmation_lag_seconds_sum)"
THR0="$(metric schoolbus_routing_stub_load_throttled_total)"; TMO0="$(metric schoolbus_routing_stub_load_timeout_total)"

cd "$DIR/k6"
# ③ 세션 먼저 올린다 — 방송을 받을 대상이 붙어 있어야 위치·배치의 팬아웃 비용이 실제로 발생한다.
k6 run -e SCENARIO3_TARGET_VUS="$SESSIONS" -e SCENARIO3_RAMP_SEC="$RAMP" -e SCENARIO3_HOLD_SEC="$HOLD" \
    --summary-export="$DIR/results/${TAG}_sessions.json" scenario3_admin_fanout.js \
    > "$DIR/results/${TAG}_sessions.log" 2>&1 &
K6_S=$!

sleep $((RAMP + 15))
echo "t+$((RAMP + 15))s 위치 부하 시작 (연결 $(metric tomcat_connections_current_connections)건)"

# ④ 위치 부하.
k6 run -e SCENARIO2_CSV="./${TAG}_runs.csv" -e SCENARIO2_DURATION_SEC="$POS_DUR" \
    -e SCENARIO2_INTERVAL_SEC=5 -e SCENARIO2_OBSERVERS=2 -e SCENARIO2_JITTER=true \
    --summary-export="$DIR/results/${TAG}_position.json" scenario2_position.js \
    > "$DIR/results/${TAG}_position.log" 2>&1 &
K6_P=$!

# ⑤ 30초 뒤 확정 배치 100건을 동시에 도래시킨다 — 세션·위치가 이미 돌고 있는 위에 겹쳐야
#    "등원 피크" 가 된다. ck_run_confirm_at 이 confirm_at = depart_time - 30분 을 강제하므로 둘을 함께 옮긴다.
sleep 30
BATCH_T0=$(date +%s)
$PG -c "UPDATE run SET depart_time = now() + interval '30 minutes' - interval '1 second', confirm_at = now() - interval '1 second'
        WHERE id IN (SELECT r.id FROM run r JOIN bus b ON b.id = r.bus_id
                     WHERE b.bus_no LIKE 'LOADCAP-%' AND r.status = 'idle' ORDER BY r.id LIMIT ${BATCH_N})" > /dev/null
echo "t+$((RAMP + 45))s 확정 배치 도래시킴"

# ⑥ 배치가 다 빠질 때까지 센다 — 확정 지연 히스토그램의 count 증가분으로 판정한다.
#    SQL 로 세면 부하가 가장 높은 구간에 docker exec 를 2초마다 부르게 되는데, 그 호출이 실제로
#    Docker Desktop 의 VM 을 두 번 멈춰 회차를 통째로 버렸다(2026-09-09). actuator 는 앱 자신이라
#    그 위험이 없다.
BATCH_DRAIN="미완"
REMAIN="?"
for i in $(seq 1 200); do
    DONE=$(python3 -c "print(int(float('$(metric schoolbus_run_confirmation_lag_seconds_count)') - float('$LAG_C0')))")
    REMAIN=$((BATCH_N - DONE))
    if [ "$REMAIN" -le 0 ]; then BATCH_DRAIN=$(( $(date +%s) - BATCH_T0 )); break; fi
    sleep 2
done
echo "배치 드레인: ${BATCH_DRAIN}초 (미확정 ${REMAIN}건)"

wait $K6_P || true
wait $K6_S || true
CPU1="$(cpu_ns)"; T1="$(date +%s)"
OUT1="$(metric executor_completed_tasks_total)"
LAG_C1="$(metric schoolbus_run_confirmation_lag_seconds_count)"; LAG_S1="$(metric schoolbus_run_confirmation_lag_seconds_sum)"
THR1="$(metric schoolbus_routing_stub_load_throttled_total)"; TMO1="$(metric schoolbus_routing_stub_load_timeout_total)"
UNCONF="$(metric schoolbus_run_unconfirmed)"
wait $SAMP 2>/dev/null || true

echo "--- 결과 ---"
python3 - "$DIR/results/${TAG}_position.json" "$DIR/results/${TAG}_sessions.json" <<'PY'
import json, sys
pos = json.load(open(sys.argv[1]))['metrics']; ses = json.load(open(sys.argv[2]))['metrics']
sent = pos.get('positions_sent_total', {}).get('count', 0)
print(f"  위치: 송신 {sent}건 · 실패 {pos.get('position_post_failures',{}).get('count',0)}건 · "
      f"p95={pos.get('position_post_duration_ms',{}).get('p(95)'):.0f}ms max={pos.get('position_post_duration_ms',{}).get('max'):.0f}ms")
fan = pos.get('ws_fanout_latency_ms', {})
print(f"  학원 채널 팬아웃 지연: p95={fan.get('p(95)')}ms max={fan.get('max')}ms")
lat = ses.get('ws_message_latency_ms', {})
print(f"  세션: 시도 {ses.get('ws_sessions',{}).get('count',0)} · 연결 실패 {ses.get('ws_connect_failures',{}).get('count',0)} · "
      f"수신 {ses.get('ws_messages_received',{}).get('count',0)}건")
print(f"  관제 채널 방송 지연: p95={lat.get('p(95)')}ms avg={lat.get('avg')}ms max={lat.get('max')}ms")
PY
python3 - "$LAG_C0" "$LAG_C1" "$LAG_S0" "$LAG_S1" "$THR0" "$THR1" "$TMO0" "$TMO1" "$OUT0" "$OUT1" "$UNCONF" "$BATCH_DRAIN" <<'PY'
import sys
c0, c1, s0, s1, t0, t1, m0, m1, o0, o1, unconf, drain = map(float, sys.argv[1:13])
n = c1 - c0
print(f"  배치: 확정 {n:.0f}건 · 도래→확정 평균 {((s1-s0)/n if n else float('nan')):.2f}초 · 드레인 {drain:.0f}초 · 미확정 게이지 {unconf:.0f}")
print(f"  지도 스텁: 격벽 거부 +{t1-t0:.0f} · 타임아웃 +{m1-m0:.0f}")
print(f"  STOMP 실행기 처리 태스크 +{o1-o0:.0f}")
PY
python3 "$DIR/summarize_sample.py" "$DIR/results/sample_${TAG}.csv"
python3 - "$CPU0" "$CPU1" "$T0" "$T1" <<'PY'
import sys
c = (float(sys.argv[2]) - float(sys.argv[1])) / 1e9
w = max(int(sys.argv[4]) - int(sys.argv[3]), 1)
print(f"  JVM CPU {c:.1f}초 / 벽시계 {w}초 = 평균 {c/w:.2f} 코어")
PY
