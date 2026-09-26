#!/usr/bin/env bash
# LOAD3 측정 전용 실행기 — r3_mixed.sh 와 같은 부하 형태(세션 N · 위치 20*MULT req/s · 확정 배치
# 100*MULT 건 동시 도래)를 재현하되, 앱·k6·표본 수집 전부가 호스트 포트를 거치지 않고
# Postgres·Redis 와 같은 Docker 네트워크(compose-inside.yml) 안에서만 통신한다(BRIEF-LOAD3 §1).
# 기존 r3_mixed.sh 는 고치지 않는다 — 이 파일이 그 사본 겸 컨테이너 버전이다(Ruling 354).
#
# 실행: ./run_load3.sh <태그, 예 load3_a> <세션수> [배율]
set -euo pipefail
TAG="${1:?태그 필요}"
SESSIONS="${2:?세션 수 필요}"
MULT="${3:-1}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PG="docker exec -i school-bus-postgres-1 psql -U schoolbus -d schoolbus_load -q"
PGQ="docker exec school-bus-postgres-1 psql -U schoolbus -d schoolbus_load -t -A -c"
APP=load3-app
NET=school-bus_default

POS_VUS=$((100 * MULT))
BATCH_N=$((100 * MULT))
RAMP=60; HOLD=180; POS_DUR=120; TOTAL=$((RAMP + HOLD + 30))

echo "== LOAD3 ${TAG} 세션=${SESSIONS} 위치VU=${POS_VUS}(=$((POS_VUS/5))req/s) 배치=${BATCH_N} =="

# 컨테이너 안에서 curl — 호스트 포트를 전혀 쓰지 않는다(§1 의 핵심).
metric() { docker exec "$APP" curl -sf --max-time 3 localhost:8080/actuator/prometheus | \
    awk -v n="$1" '$0 ~ "^"n"[ {]" {s+=$NF; k=1} END {if(!k) print 0; else printf "%.6g", s}'; }
cpu_ns() { metric process_cpu_time_ns_total; }

# ① 위치용 회차 심기(기존 SQL 그대로, docker exec 로 컨테이너 안에서 실행)
$PG -v n="$POS_VUS" -t -A -F',' < "$DIR/sql/scenario2_prep.sql" | grep -v '^$' > "$DIR/k6/${TAG}_runs.csv"
echo "위치용 회차 $(wc -l < "$DIR/k6/${TAG}_runs.csv")건"

# ② 배치 대상(LOADCAP) 되돌리기
$PG -v ON_ERROR_STOP=1 < "$DIR/sql/r0_reset_runs.sql" > /dev/null
IDLE_BEFORE=$($PGQ "SELECT count(*) FROM run r JOIN bus b ON b.id=r.bus_id WHERE b.bus_no LIKE 'LOADCAP-%' AND r.status='idle'")
echo "배치 대기 회차 ${IDLE_BEFORE}건 중 ${BATCH_N}건을 도래시킨다"

# 표본기 — r3_mixed.sh 의 sampler.sh 와 같은 지표를 컨테이너 안에서 curl 로 수집(호스트 포트 미사용)
OUT="$DIR/results/${TAG}_sample.csv"
echo "t,cpu_process,heap_mb,live_threads,tomcat_busy,tomcat_conns,hikari_active,hikari_pending,hikari_acquire_max_s,hikari_timeout_total,out_queued,out_active,dropped_total" > "$OUT"
{
    for t in $(seq 0 $((TOTAL - 1))); do
        P="$(docker exec "$APP" curl -sf --max-time 2 localhost:8080/actuator/prometheus || true)"
        v() { echo "$P" | awk -v n="$1" -v f="${2:-}" '$0 ~ "^"n"[ {]" { if (f=="" || index($0,f)>0) { s+=$NF; k=1 } } END { if (!k) print ""; else printf "%.6g", s }'; }
        printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
            "$t" "$(v process_cpu_usage)" \
            "$(echo "$(v jvm_memory_used_bytes 'area=\"heap\"')" | awk '{printf "%.0f", $1/1048576}')" \
            "$(v jvm_threads_live_threads)" "$(v tomcat_threads_busy_threads)" \
            "$(v tomcat_connections_current_connections)" "$(v hikaricp_connections_active)" \
            "$(v hikaricp_connections_pending)" "$(v hikaricp_connections_acquire_seconds_max)" \
            "$(v hikaricp_connections_timeout_total)" \
            "$(v executor_queued_tasks 'clientOutboundChannelExecutor')" \
            "$(v executor_active_threads 'clientOutboundChannelExecutor')" \
            "$(v schoolbus_ws_outbound_dropped_total 'event=\"position\"')" >> "$OUT"
        sleep 1
    done
} &
SAMP=$!

CPU0="$(cpu_ns)"; T0="$(date +%s)"
LAG_C0="$(metric schoolbus_run_confirmation_lag_seconds_count)"; LAG_S0="$(metric schoolbus_run_confirmation_lag_seconds_sum)"

# ③ 세션(관제 채널) — k6 컨테이너, app-load3 와 같은 네트워크, 컨테이너 이름으로 접속
docker run --rm --name "load3_${TAG}_sessions" --network "$NET" \
    -v "$DIR/k6:/scripts:ro" -v "$DIR/results:/results" -w /scripts \
    -e BASE_URL="http://${APP}:8080" -e WS_URL="ws://${APP}:8080/ws/location" \
    -e SCENARIO3_TARGET_VUS="$SESSIONS" -e SCENARIO3_RAMP_SEC="$RAMP" -e SCENARIO3_HOLD_SEC="$HOLD" \
    grafana/k6 run --summary-export="/results/${TAG}_sessions.json" scenario3_admin_fanout.js \
    > "$DIR/results/${TAG}_sessions.log" 2>&1 &
K6_S=$!

sleep $((RAMP + 15))
echo "t+$((RAMP + 15))s 위치 부하 시작 (연결 $(metric tomcat_connections_current_connections)건)"

# ④ 위치 부하
docker run --rm --name "load3_${TAG}_position" --network "$NET" \
    -v "$DIR/k6:/scripts:ro" -v "$DIR/results:/results" -w /scripts \
    -e BASE_URL="http://${APP}:8080" -e WS_URL="ws://${APP}:8080/ws/location" \
    -e SCENARIO2_CSV="./${TAG}_runs.csv" -e SCENARIO2_DURATION_SEC="$POS_DUR" \
    -e SCENARIO2_INTERVAL_SEC=5 -e SCENARIO2_OBSERVERS=2 -e SCENARIO2_JITTER=true \
    grafana/k6 run --summary-export="/results/${TAG}_position.json" scenario2_position.js \
    > "$DIR/results/${TAG}_position.log" 2>&1 &
K6_P=$!

# ⑤ 30초 뒤 확정 배치 동시 도래
sleep 30
BATCH_T0=$(date +%s)
$PG -c "UPDATE run SET depart_time = now() + interval '30 minutes' - interval '1 second', confirm_at = now() - interval '1 second'
        WHERE id IN (SELECT r.id FROM run r JOIN bus b ON b.id = r.bus_id
                     WHERE b.bus_no LIKE 'LOADCAP-%' AND r.status = 'idle' ORDER BY r.id LIMIT ${BATCH_N})" > /dev/null
echo "t+$((RAMP + 45))s 확정 배치 도래시킴"

# ⑥ 배치 드레인 — actuator 누적치로 판정(SQL 폴링 아님, LOAD_TESTING.md §6.4 함정 회피)
BATCH_DRAIN="미완"; REMAIN="?"
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
LAG_C1="$(metric schoolbus_run_confirmation_lag_seconds_count)"; LAG_S1="$(metric schoolbus_run_confirmation_lag_seconds_sum)"
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
python3 - "$LAG_C0" "$LAG_C1" "$LAG_S0" "$LAG_S1" "$BATCH_DRAIN" <<'PY'
import sys
c0, c1, s0, s1 = map(float, sys.argv[1:5])
drain = sys.argv[5]
n = c1 - c0
print(f"  배치: 확정 {n:.0f}건 · 도래→확정 평균 {((s1-s0)/n if n else float('nan')):.2f}초 · 드레인 {drain}초")
PY
python3 "$DIR/summarize_sample.py" "$OUT"
python3 - "$CPU0" "$CPU1" "$T0" "$T1" <<'PY'
import sys
c = (float(sys.argv[2]) - float(sys.argv[1])) / 1e9
w = max(int(sys.argv[4]) - int(sys.argv[3]), 1)
print(f"  JVM CPU {c:.1f}초 / 벽시계 {w}초 = 평균 {c/w:.2f} 코어")
PY

# 멈춤 서명 — 대기(hikari_pending) >=50 이면서 JVM CPU <0.05 인 표본 초 수(FIX-LOAD2 §3-2 정의와 동일 컬럼)
python3 - "$OUT" <<'PY'
import csv, sys
rows = list(csv.DictReader(open(sys.argv[1])))
stuck = 0
for r in rows:
    try:
        pend = float(r['hikari_pending'] or 0); cpu = float(r['cpu_process'] or 0)
    except ValueError:
        continue
    if pend >= 50 and cpu < 0.05:
        stuck += 1
print(f"  멈춤 서명(대기>=50 · CPU<0.05) 초 수: {stuck}")
PY

rm -f "$DIR/k6/${TAG}_runs.csv"
echo "== ${TAG} 종료 =="
