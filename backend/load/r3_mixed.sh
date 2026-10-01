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
#
# R46-LOAD 가 더한 환경변수 — 기본값은 전부 2026-09-09 와 같은 동작이다(값을 안 주면 09-09 회차 그대로).
#   R3_INTERVAL   위치 송신 주기(초). 기본 5(09-09). 앱 실제 값은 2(`position_constants.dart`)
#   R3_POLLING    1 이면 학부모 홈 폴링 + 관계자 웹 폴링을 더한다(scenario5_polling.js). 기본 0
#   R3_MODE       admin(기본) = 09-09 처럼 세션 전원이 /topic/admin/live 를 구독
#                 realistic    = 세션 수만큼 학부모가 자기 학생 채널을 구독 + 관계자 R3_WS_STAFF 명(학원 채널)
#                                + 메인 관리자 R3_WS_ADMIN 명(관제 채널) — 조사 D "다음 측정 3"의 실제 구독 분포
#   ROUND_LABEL   결과 파일 이름표(기본 r3_s<세션>_x<배율>)
set -euo pipefail
SESSIONS="${1:?세션 수 필요}"
MULT="${2:-1}"
INTERVAL="${R3_INTERVAL:-5}"
POLL="${R3_POLLING:-0}"
MODE="${R3_MODE:-admin}"
WS_STAFF="${R3_WS_STAFF:-10}"
WS_ADMIN="${R3_WS_ADMIN:-2}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PG="docker exec -i school-bus-postgres-1 psql -U schoolbus -d schoolbus_load -q"
PGQ="docker exec school-bus-postgres-1 psql -U schoolbus -d schoolbus_load -t -A -c"
PROM="http://localhost:18080/actuator/prometheus"
TAG="${ROUND_LABEL:-r3_s${SESSIONS}_x${MULT}}"

POS_VUS=$((100 * MULT))
RAMP=60; HOLD=180; POS_DUR=120; TOTAL=$((RAMP + HOLD + 30))
SCENARIO5_DUR=$((RAMP + HOLD + 10))

echo "== R3 세션=${SESSIONS}(${MODE}) · 위치 VU=${POS_VUS}(${INTERVAL}초 주기 = $((POS_VUS / INTERVAL)) req/s) · 배치=$((100 * MULT))회차 · 폴링=${POLL} =="

# ① 위치용 회차를 심는다(moving 상태, 기사 계정 포함). 앞 회차가 심은 위치용 회차는 먼저 종료한다 — 근접 판정 스케줄러가
#    움직이는 회차 수에 비례해 일하므로, 안 끝내면 회차를 거듭할수록 배경 부하가 늘어 회차끼리 비교가 안 된다.
$PG -c "UPDATE run SET status = 'finished', finished_at = now() WHERE status = 'moving' AND bus_id IN (SELECT id FROM bus WHERE bus_no LIKE 'LP-%')" > /dev/null
$PG -v n="$POS_VUS" -t -A -F',' < "$DIR/sql/scenario2_prep.sql" | grep -v '^$' > "$DIR/results/${TAG}_runs.csv"
echo "위치용 회차 $(wc -l < "$DIR/results/${TAG}_runs.csv")건"

# ② 배치 대상은 R0 이 심어 둔 회차다 — confirm_at 이 미래라 아직 idle 이다. 회차마다 먼저 되돌려
#    같은 조건에서 다시 잰다(안 되돌리면 두 번째 라운드가 "동시 도래" 가 아니라 "재확정" 이 된다).
$PG -v ON_ERROR_STOP=1 < "$DIR/sql/r0_reset_runs.sql" > /dev/null
BATCH_N=$((100 * MULT))
IDLE_BEFORE=$($PGQ "SELECT count(*) FROM run r JOIN bus b ON b.id=r.bus_id WHERE b.bus_no LIKE 'LOADCAP-%' AND r.status='idle'")
echo "배치 대기 회차 ${IDLE_BEFORE}건 중 ${BATCH_N}건을 도래시킨다"

cpu_ns() { curl -sf "$PROM" | awk '$0 ~ "^process_cpu_time_ns_total[ {]" {print $NF}'; }
metric() { curl -sf "$PROM" | awk -v n="$1" '$0 ~ "^"n"[ {]" {s+=$NF; k=1} END {if(!k) print 0; else printf "%.6g", s}'; }
# 이름 + 라벨 일부 문자열로 거른 합 — 방송 전달 수는 STOMP 송신 실행기 것만 세야 한다.
prom_sum() { curl -sf "$PROM" | awk -v n="$1" -v f="${2:-}" '$0 ~ "^"n"[ {]" { if (f == "" || index($0, f) > 0) { s += $NF; k = 1 } } END { if (!k) print 0; else printf "%.10g", s }'; }
server_counters() { echo "$(prom_sum executor_completed_tasks_total clientOutboundChannelExecutor) $(prom_sum hikaricp_connections_timeout_total) $(prom_sum http_server_requests_seconds_count 'status="5') $(prom_sum schoolbus_ws_outbound_dropped_total)"; }

# 폴링·시청 세션 토큰 — 로그인 CPU 가 측정 구간에 섞이지 않게 회차 직전에 미리 발급한다(유효시간 15분).
TOKENS="$DIR/results/${TAG}_tokens.json"
if [ "$POLL" = "1" ] || [ "$MODE" = "realistic" ]; then
    python3 "$DIR/r46_mint_tokens.py" "$TOKENS" 600
fi

# k6 → Prometheus 원격 쓰기, 기본 꺼짐(K6_PROM_RW=1 로 켠다) — r1_round.sh 와 같은 스위치·같은 이유
# (문자열 + word-splitting, 빈 배열 + set -u 조합이 bash 3.2 에서 죽는 문제 회피). 수신은 범용 관측
# 스택(:9390, O1)이 켠다.
# 세션·위치 두 k6 실행이 겹치므로 testid 태그를 따로 붙여 대시보드에서 구분한다.
K6_OUT_ARGS_SESSIONS=""; K6_OUT_ARGS_POSITION=""; K6_OUT_ARGS_POLL=""
if [ "${K6_PROM_RW:-0}" = "1" ]; then
    export K6_PROMETHEUS_RW_SERVER_URL="${K6_PROMETHEUS_RW_SERVER_URL:-http://localhost:9390/api/v1/write}"
    export K6_PROMETHEUS_RW_TREND_STATS="${K6_PROMETHEUS_RW_TREND_STATS:-p(95),p(99),max}"
    K6_OUT_ARGS_SESSIONS="-o experimental-prometheus-rw --tag testid=${TAG}_sessions"
    K6_OUT_ARGS_POSITION="-o experimental-prometheus-rw --tag testid=${TAG}_position"
    K6_OUT_ARGS_POLL="-o experimental-prometheus-rw --tag testid=${TAG}_poll"
fi

"$DIR/sampler.sh" "$TAG" "$TOTAL" > /dev/null &
SAMP=$!
CPU0="$(cpu_ns)"; T0="$(date +%s)"
SRV0="$(server_counters)"
OUT0="$(metric executor_completed_tasks_total)"
LAG_C0="$(metric schoolbus_run_confirmation_lag_seconds_count)"; LAG_S0="$(metric schoolbus_run_confirmation_lag_seconds_sum)"
THR0="$(metric schoolbus_routing_stub_load_throttled_total)"; TMO0="$(metric schoolbus_routing_stub_load_timeout_total)"

cd "$DIR/k6"
# ③ 세션 먼저 올린다 — 방송을 받을 대상이 붙어 있어야 위치·배치의 팬아웃 비용이 실제로 발생한다.
#    admin: 09-09 와 같은 관제 채널 세션. realistic: scenario5 가 학부모·관계자·관리자 세션과 (켰다면) 폴링을 함께 낸다.
K6_S=""; K6_Q=""
if [ "$MODE" = "realistic" ]; then
    POLL_ENV="-e POLL_PARENT_APPS=0 -e POLL_STAFF_DASH_TABS=0 -e POLL_STAFF_TODAY_TABS=0"
    [ "$POLL" = "1" ] && POLL_ENV=""
    k6 run $K6_OUT_ARGS_POLL -e POLL_TOKENS="$TOKENS" -e SCENARIO5_DURATION_SEC="$SCENARIO5_DUR" -e SCENARIO5_RAMP_SEC="$RAMP" \
        -e WS_VIEWERS="$SESSIONS" -e WS_STAFF="$WS_STAFF" -e WS_ADMIN="$WS_ADMIN" $POLL_ENV \
        --summary-export="$DIR/results/${TAG}_poll.json" scenario5_polling.js \
        > "$DIR/results/${TAG}_poll.log" 2>&1 &
    K6_Q=$!
else
    k6 run $K6_OUT_ARGS_SESSIONS -e SCENARIO3_TARGET_VUS="$SESSIONS" -e SCENARIO3_RAMP_SEC="$RAMP" -e SCENARIO3_HOLD_SEC="$HOLD" \
        --summary-export="$DIR/results/${TAG}_sessions.json" scenario3_admin_fanout.js \
        > "$DIR/results/${TAG}_sessions.log" 2>&1 &
    K6_S=$!
    if [ "$POLL" = "1" ]; then
        k6 run $K6_OUT_ARGS_POLL -e POLL_TOKENS="$TOKENS" -e SCENARIO5_DURATION_SEC="$SCENARIO5_DUR" \
            -e WS_VIEWERS=0 -e WS_STAFF=0 -e WS_ADMIN=0 \
            --summary-export="$DIR/results/${TAG}_poll.json" scenario5_polling.js \
            > "$DIR/results/${TAG}_poll.log" 2>&1 &
        K6_Q=$!
    fi
fi

sleep $((RAMP + 15))
echo "t+$((RAMP + 15))s 위치 부하 시작 (연결 $(metric tomcat_connections_current_connections)건)"

# ④ 위치 부하.
k6 run $K6_OUT_ARGS_POSITION -e SCENARIO2_CSV="$DIR/results/${TAG}_runs.csv" -e SCENARIO2_DURATION_SEC="$POS_DUR" \
    -e SCENARIO2_INTERVAL_SEC="$INTERVAL" -e SCENARIO2_OBSERVERS=2 -e SCENARIO2_JITTER=true \
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
[ -n "$K6_S" ] && { wait $K6_S || true; }
[ -n "$K6_Q" ] && { wait $K6_Q || true; }
CPU1="$(cpu_ns)"; T1="$(date +%s)"
SRV1="$(server_counters)"
OUT1="$(metric executor_completed_tasks_total)"
LAG_C1="$(metric schoolbus_run_confirmation_lag_seconds_count)"; LAG_S1="$(metric schoolbus_run_confirmation_lag_seconds_sum)"
THR1="$(metric schoolbus_routing_stub_load_throttled_total)"; TMO1="$(metric schoolbus_routing_stub_load_timeout_total)"
UNCONF="$(metric schoolbus_run_unconfirmed)"
wait $SAMP 2>/dev/null || true

echo "--- 결과 ---"
python3 - "$DIR/results/${TAG}_position.json" "$DIR/results/${TAG}_sessions.json" "$DIR/results/${TAG}_poll.json" <<'PY'
import json, os, sys
def load(p):
    return json.load(open(p))['metrics'] if os.path.exists(p) else None
pos, ses, poll = (load(p) for p in sys.argv[1:4])
def f(v, key, fmt='{:.0f}'):
    x = (v or {}).get(key)
    return 'n/a' if x is None else fmt.format(x)
sent = pos.get('positions_sent_total', {}).get('count', 0)
d = pos.get('position_post_duration_ms', {})
print(f"  위치: 송신 {sent}건 · 실패 {pos.get('position_post_failures',{}).get('count',0)}건 · p95={f(d,'p(95)')}ms avg={f(d,'avg')}ms max={f(d,'max')}ms")
fan = pos.get('ws_fanout_latency_ms', {})
print(f"  학원 채널 팬아웃 지연(관측자 2, 송신→내 회차 방송 수신): p95={f(fan,'p(95)')}ms max={f(fan,'max')}ms")
if ses:
    lat = ses.get('ws_message_latency_ms', {})
    print(f"  세션(관제 채널): 시도 {ses.get('ws_sessions',{}).get('count',0)} · 연결 실패 {ses.get('ws_connect_failures',{}).get('count',0)} · "
          f"수신 {ses.get('ws_messages_received',{}).get('count',0)}건")
    print(f"  관제 채널 방송 지연: p95={f(lat,'p(95)')}ms avg={f(lat,'avg')}ms max={f(lat,'max')}ms")
if poll:
    for name, label in (('viewer', '학부모 시청 세션(학생 채널)'), ('control', '관계자·관리자 세션(학원·관제 채널)')):
        got = poll.get(f'{name}_ws_messages_received', {}).get('count')
        if got is None: continue
        lat = poll.get(f'{name}_ws_latency_ms', {})
        print(f"  {label}: 수신 {got}건 · 방송 지연 p95={f(lat,'p(95)')}ms avg={f(lat,'avg')}ms max={f(lat,'max')}ms")
    print(f"  세션 연결 실패 {poll.get('ws_connect_failures',{}).get('count',0)}")
    for group in ('parent', 'staff'):
        req = poll.get(f'poll_{group}_requests', {}).get('count')
        if req is None: continue
        print(f"  폴링({group}): 요청 {req}건 · 실패 {poll.get(f'poll_{group}_failures',{}).get('count',0)}건")
    for k in sorted(poll):
        if k.startswith('poll_') and k.endswith('_ms'):
            v = poll[k]
            print(f"    {k[5:-3]:<22} avg={f(v,'avg')}ms p95={f(v,'p(95)')}ms max={f(v,'max')}ms")
PY
python3 - "$LAG_C0" "$LAG_C1" "$LAG_S0" "$LAG_S1" "$THR0" "$THR1" "$TMO0" "$TMO1" "$OUT0" "$OUT1" "$UNCONF" "$BATCH_DRAIN" <<'PY'
import sys
c0, c1, s0, s1, t0, t1, m0, m1, o0, o1, unconf, drain = map(float, sys.argv[1:13])
n = c1 - c0
print(f"  배치: 확정 {n:.0f}건 · 도래→확정 평균 {((s1-s0)/n if n else float('nan')):.2f}초 · 드레인 {drain:.0f}초 · 미확정 게이지 {unconf:.0f}")
print(f"  지도 스텁: 격벽 거부 +{t1-t0:.0f} · 타임아웃 +{m1-m0:.0f}")
print(f"  STOMP 실행기 처리 태스크 +{o1-o0:.0f} (전 실행기 합 — 09-09 와 같은 정의)")
PY
python3 - "$SRV0" "$SRV1" "$T0" "$T1" <<'PY'
import sys
b, a = [list(map(float, x.split())) for x in sys.argv[1:3]]
wall = max(int(sys.argv[4]) - int(sys.argv[3]), 1)
d = [y - x for x, y in zip(b, a)]
print(f"  서버 방송 전달 {d[0]:.0f}건 ({d[0]/wall:.1f}건/s, 송신 실행기만) · Hikari 연결 대기 시간초과 +{d[1]:.0f} · 5xx +{d[2]:.0f} · 버려진 위치 방송 +{d[3]:.0f}")
PY
python3 "$DIR/summarize_sample.py" "$DIR/results/sample_${TAG}.csv"
python3 - "$CPU0" "$CPU1" "$T0" "$T1" <<'PY'
import sys
c = (float(sys.argv[2]) - float(sys.argv[1])) / 1e9
w = max(int(sys.argv[4]) - int(sys.argv[3]), 1)
print(f"  JVM CPU {c:.1f}초 / 벽시계 {w}초 = 평균 {c/w:.2f} 코어")
PY
