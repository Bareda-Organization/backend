#!/usr/bin/env bash
# R2 한 회차 — 위치 수신 처리량 (부하 한계 측정 계획 §3 R2).
# 회차마다 기사·버스·회차를 새로 심고(scenario2_prep.sql), k6 로 N VU 가 5초 주기로 위치를 올린다.
# 목표 환산: 버스 100대 ÷ 5초 = 20 req/s (계획 §1). N=100 이 그 지점이다.
#
# 실행: ./r2_round.sh <N> [지속초]
set -euo pipefail
N="${1:?N 필요}"
DURATION="${2:-60}"
INTERVAL="${SCENARIO2_INTERVAL_SEC:-5}"
OBSERVERS="${SCENARIO2_OBSERVERS:-2}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PG_CONTAINER="${PG_CONTAINER:-school-bus-postgres-1}"

echo "== R2 N=${N} interval=${INTERVAL}s duration=${DURATION}s observers=${OBSERVERS} =="
docker exec -i "$PG_CONTAINER" psql -U schoolbus -d schoolbus_load -q -v n="$N" -t -A -F',' \
    < "$DIR/sql/scenario2_prep.sql" | grep -v '^$' > "$DIR/k6/scenario2_runs_n${N}.csv"
echo "심은 회차 수: $(wc -l < "$DIR/k6/scenario2_runs_n${N}.csv")"

"$DIR/snapshot.sh" "r2_n${N}_before" > /dev/null
# 누적 CPU 시간 — 1초 표본은 5초 주기의 버스트를 놓쳐 최대값이 회차마다 들쭉날쭉하다(같은 N=1200
# 에서 0.19 ~ 0.38). 누적값의 차는 표본 시점과 무관해 "평균 몇 코어를 썼나"를 흔들림 없이 준다.
cpu_ns() { curl -sf "$PROM_URL_DEFAULT" | awk '$0 ~ "^process_cpu_time_ns_total[ {]" {print $NF}'; }
PROM_URL_DEFAULT="http://localhost:18080/actuator/prometheus"
CPU_NS_BEFORE="$(cpu_ns)"
T_BEFORE="$(date +%s)"

# 회차 내내 1초 간격으로 표본을 뜬다 — 포화 판정은 끝난 뒤 값이 아니라 도는 동안의 최대값으로 한다.
"$DIR/sampler.sh" "r2_n${N}" $((DURATION + 10)) > /dev/null &
SNAP_PID=$!

cd "$DIR/k6"
set +e
k6 run -e SCENARIO2_CSV="./scenario2_runs_n${N}.csv" \
    -e SCENARIO2_DURATION_SEC="$DURATION" \
    -e SCENARIO2_INTERVAL_SEC="$INTERVAL" \
    -e SCENARIO2_OBSERVERS="$OBSERVERS" \
    --summary-export="$DIR/results/r2_n${N}.json" \
    scenario2_position.js > "$DIR/results/r2_n${N}.log" 2>&1
K6_EXIT=$?
set -e
wait $SNAP_PID 2>/dev/null || true
"$DIR/snapshot.sh" "r2_n${N}_after" > /dev/null
CPU_NS_AFTER="$(cpu_ns)"
T_AFTER="$(date +%s)"

echo "k6 종료 코드=${K6_EXIT} (임계 위반이면 99 — 실패가 아니라 관측값이다)"
python3 - "$DIR/results/r2_n${N}.json" "$N" "$INTERVAL" "$DURATION" <<'PY'
import json, sys
p, n, interval, dur = sys.argv[1], int(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4])
d = json.load(open(p))
m = d['metrics']
def g(name, stat='p(95)'):
    v = m.get(name)
    return None if v is None else v.get(stat)
sent = m.get('positions_sent_total', {}).get('count', 0)
echo = m.get('position_echo_received_total', {}).get('count', 0)
fail = m.get('position_post_failures', {}).get('count', 0)
reqs = m.get('http_reqs', {}).get('count', 0)
print(f"  송신 {sent}건 (목표 {n*dur/interval:.0f}건) · 실패 {fail}건 ({100*fail/max(sent+fail,1):.2f}%) · echo {echo}건")
print(f"  처리량 {sent/dur:.1f} req/s")
print(f"  position_post p95={g('position_post_duration_ms')}ms avg={g('position_post_duration_ms','avg')}ms max={g('position_post_duration_ms','max')}ms")
print(f"  ws_fanout p95={g('ws_fanout_latency_ms')}ms avg={g('ws_fanout_latency_ms','avg')}ms max={g('ws_fanout_latency_ms','max')}ms")
print(f"  http_req_failed rate={m.get('http_req_failed',{}).get('value')}")
PY
python3 "$DIR/summarize_sample.py" "$DIR/results/sample_r2_n${N}.csv"
python3 - "$CPU_NS_BEFORE" "$CPU_NS_AFTER" "$T_BEFORE" "$T_AFTER" "$DIR/results/r2_n${N}.json" <<'PY'
import json, sys
before, after, t0, t1, summary = float(sys.argv[1]), float(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), sys.argv[5]
cpu_sec = (after - before) / 1e9
wall = max(t1 - t0, 1)
sent = json.load(open(summary))['metrics'].get('positions_sent_total', {}).get('count', 0)
per_req_ms = 1000 * cpu_sec / sent if sent else float('nan')
print(f"  JVM CPU {cpu_sec:.1f}초 / 벽시계 {wall}초 = 평균 {cpu_sec/wall:.2f} 코어 · 요청당 {per_req_ms:.1f}ms CPU")
PY
