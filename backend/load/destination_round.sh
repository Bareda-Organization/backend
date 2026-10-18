#!/usr/bin/env bash
# 학생 목적지 시험 한 회차(L-3) — 위치용 회차를 심고 명단(LOADCAP 20 + L3DEST 12)을 붙인 뒤 학부모 토큰을 발급해 scenario6 을 돌린다.
# destination_seed.sql 을 먼저 적재해 둔다. 위치용 회차는 LOADCAP 학원마다 10개(10학원 × 10 = 100개)다.
#
# 실행: DESTINATIONS=3200 MIN_DESTINATIONS=3000 ROUND_LABEL=<라벨> ./destination_round.sh      (prod_sized_round.sh 가 부르면 라벨이 이미 들어 있다)
# 종료 코드: k6 종료 코드(임계를 하나라도 못 지키면 99). 결과: results/<라벨>_dest_students.json · _dest_positions.json (각 .log)
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TAG="${ROUND_LABEL:?ROUND_LABEL 필요}"
DESTINATIONS="${DESTINATIONS:-3200}"
PG="docker exec -i school-bus-postgres-1 psql -U schoolbus -d schoolbus_load -q"
PGQ="docker exec school-bus-postgres-1 psql -U schoolbus -d schoolbus_load -t -A -c"
PROM="${PROM_URL:-http://localhost:18080/actuator/prometheus}"
prom_sum() { curl -sf "$PROM" | awk -v n="$1" -v f="${2:-}" '$0 ~ "^"n"[ {]" { if (f == "" || index($0, f) > 0) { s += $NF; k = 1 } } END { if (!k) print 0; else printf "%.10g", s }'; }
counters() { echo "$(prom_sum hikaricp_connections_timeout_total) $(prom_sum http_server_requests_seconds_count 'status="5') $(prom_sum schoolbus_ws_outbound_dropped_total)"; }

$PG -c "UPDATE run SET status = 'finished', finished_at = now() WHERE status = 'moving' AND bus_id IN (SELECT id FROM bus WHERE bus_no LIKE 'LP-%')" > /dev/null
RUNS_CSV="$DIR/results/${TAG}_runs.csv"; : > "$RUNS_CSV"
read -r -a ACADEMIES <<< "$($PGQ "SELECT string_agg(id::text, ' ' ORDER BY id) FROM academy WHERE code LIKE 'LOADCAP-%'")"
for academy in "${ACADEMIES[@]}"; do
    $PG -v n=10 -v academy_id="$academy" -t -A -F',' < "$DIR/sql/scenario2_prep.sql" | grep -v '^$' >> "$RUNS_CSV"
done
echo "위치용 회차 $(wc -l < "$RUNS_CSV")건"
echo "명단 연결(LOADCAP): $($PG -t -A -F' 회차 ' < "$DIR/sql/r46_link_position_riders.sql" | tail -1)"
echo "명단 연결(L3DEST): $($PG -t -A -F',' < "$DIR/sql/destination_link_riders.sql" | tail -1) (건수,서로 다른 학생,회차)"

TOKENS="$DIR/results/${TAG}_tokens.json"
PARENT_NAME_LIKES='LOADCAP-%,L3DEST-%' python3 "$DIR/r46_mint_tokens.py" "$TOKENS" "$DESTINATIONS"

C0="$(counters)"; T0="$(date +%s)"
CG0="$("$DIR/cgroup_sampler.sh" cpu-all)"
"$DIR/sampler.sh" "$TAG" 240 > /dev/null &
SAMP=$!
# k6 프로세스 CPU(%)를 5초마다 남긴다 — k6 가 병목인 회차를 가려내는 근거(top 의 전체 사용률도 같이)
( while true; do echo "$(date +%s) k6=$(ps -A -o %cpu,command | awk '/[k]6 run/ {s += $1} END {print s+0}') idle=$(top -l 1 -n 0 | awk '/^CPU usage/ {print $7}')"; sleep 5; done ) > "$DIR/results/${TAG}_k6_cpu.log" 2>&1 &
KCPU=$!
set +e
# 학생 세션(3,200)과 위치 송신 · 학원 관측자를 k6 프로세스 둘로 나눠 건다 — 한 프로세스는 방송 수신 처리에 CPU 를 쓰느라 위치 송신 시각이 밀려(버려진 반복) 시험이 무효가 됐다.
K6_COMMON="--summary-trend-stats=avg,min,med,max,p(90),p(95),p(99) -e STUDENT_TOKENS=$TOKENS -e SCENARIO6_CSV=$RUNS_CSV -e DESTINATIONS=$DESTINATIONS -e MIN_DESTINATIONS=${MIN_DESTINATIONS:-3000}"
( cd "$DIR/k6" && k6 run $K6_COMMON -e SCENARIO6_PART=students --summary-export="$DIR/results/${TAG}_dest_students.json" scenario6_student_destinations.js ) > "$DIR/results/${TAG}_dest_students.log" 2>&1 &
K6_S=$!
( cd "$DIR/k6" && k6 run $K6_COMMON -e SCENARIO6_PART=positions --summary-export="$DIR/results/${TAG}_dest_positions.json" scenario6_student_destinations.js ) > "$DIR/results/${TAG}_dest_positions.log" 2>&1 &
K6_P=$!
wait $K6_S; EX_S=$?; wait $K6_P; EX_P=$?
K6_EXIT=$(( EX_S != 0 || EX_P != 0 ? 99 : 0 ))

set -e
kill $KCPU 2>/dev/null; wait $KCPU 2>/dev/null || true
wait $SAMP 2>/dev/null || true
C1="$(counters)"; T1="$(date +%s)"
echo "$T0 $T1 $CG0 $("$DIR/cgroup_sampler.sh" cpu-all)" > "$DIR/results/${TAG}_cg_cpu.txt"
echo "--- 결과 (k6 종료 코드 $K6_EXIT) ---"
python3 - "$DIR/results/${TAG}_dest_students.json" "$DIR/results/${TAG}_dest_positions.json" "$C0" "$C1" "$K6_EXIT" <<'PY'
import json, sys
ms = json.load(open(sys.argv[1]))['metrics']; mp = json.load(open(sys.argv[2]))['metrics']
m = {**ms, **mp}
m["dropped_iterations_students"] = ms.get("dropped_iterations", {}); m["dropped_iterations_positions"] = mp.get("dropped_iterations", {})
b, a, k6 = [list(map(float, x.split())) for x in sys.argv[3:5]] + [sys.argv[5]]
g = lambda n, k='count': m.get(n, {}).get(k)
f = lambda v: 'n/a' if v is None else f'{v:.0f}'
print(f"  목적지 구독 확인(RECEIPT) {f(g('destinations_subscribed'))} = 학생 채널 {f(g('destinations_subscribed_student'))} + 학원 채널 {f(g('destinations_subscribed_academy'))}")
print(f"  위치를 1건 이상 받은 학생 목적지(중복 제외) {f(g('destinations_received'))}")
print(f"  위치 POST {f(g('positions_sent_total'))}건 · 실패 {f(g('position_post_failures'))} · p95 {f(g('position_post_duration_ms','p(95)'))}ms · max {f(g('position_post_duration_ms','max'))}ms")
print(f"  학생 채널 방송 수신 {f(g('viewer_ws_messages_received'))}건 · 지연 p95 {f(g('viewer_ws_latency_ms','p(95)'))} / p99 {f(g('viewer_ws_latency_ms','p(99)'))} / max {f(g('viewer_ws_latency_ms','max'))}ms")
print(f"  학원 채널 방송 수신 {f(g('academy_ws_messages_received'))}건 · 지연 p95 {f(g('academy_ws_latency_ms','p(95)'))} / p99 {f(g('academy_ws_latency_ms','p(99)'))} / max {f(g('academy_ws_latency_ms','max'))}ms")
print(f"  연결 실패 {f(g('ws_connect_failures'))} · 버려진 반복(k6 병목) 학생 프로세스 {f(g('dropped_iterations_students'))} · 위치 프로세스 {f(g('dropped_iterations_positions'))}")
print(f"  서버: Hikari 연결 대기 시간초과 +{a[0]-b[0]:.0f} · 5xx +{a[1]-b[1]:.0f} · 버려진 위치 방송 +{a[2]-b[2]:.0f}")
PY
python3 "$DIR/summarize_sample.py" "$DIR/results/sample_${TAG}.csv"
exit "$K6_EXIT"
