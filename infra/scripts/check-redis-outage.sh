#!/usr/bin/env bash
# Redis 멈춤 연습 — 백엔드 1대를 nginx 뒤에서 작게 계속 두드리는 동안 Redis 를 멈추고(kill) 얼려(pause) 사용자에게 보이는 것을 재고, Ruling 929 D 의 기준으로 판정한다.
#
# 스택 = docker-compose.prod.yml + infra/redis-drill/docker-compose.drill.yml, 프로젝트 이름 r60-redis. postgres · redis · backend(1대) · proxy(nginx) · prometheus 만 띄운다.
# 부하는 backend/load/k6/r60_redis_outage.js(기사 위치 POST · 학부모 위치 GET · Redis 무관 GET · 승인 상세 GET · STOMP 구독자).
# 정리는 `down` 만 한다 — `-v` 는 쓰지 않는다(스택이 볼륨을 만들지 않는다).
#
# 판정(종료 코드 0 이려면 전부 만족) — 기준값은 환경변수로 바꿀 수 있다. 929 D 가 정한 것은 1 · 2 · 3 이고, 4~8 은 확인 회차(큰 회차, 요청 7,971건)의 실측에 여유를 둔 값이다:
#   1. Redis 와 무관한 REST 5xx · 시간 초과 = 0                     DRILL_5XX_LIMIT (기본 0)                  929 D
#   2. 위치 송신(POST) 실패 = 0                                      DRILL_SEND_FAIL_LIMIT (기본 0)            929 D
#   3. 다시 켠 뒤 중계 구독 복구 시간(Redis PUBSUB NUMSUB)           DRILL_RECOVERY_LIMIT_SEC (기본 10)        929 D (실측 kill 2.1초 · pause 0.1초)
#   4. 다시 켠 뒤 REST 위치 조회가 낡은 값(나이 2초 초과)을 마지막으로 돌려준 시각   DRILL_FILL_LIMIT_SEC (기본 30 — 클라이언트 안전망 조회 주기, API_SPEC §7.2 · 실측 0초)
#   5. 멈춘 구간 안에서 늘어난 지도 API(스텁) 온디맨드 호출 수(멈추기 전 호출률로 기대한 수를 뺀 값)   DRILL_MAP_EXTRA_CALLS (기본 2 · 실측 큰 회차 1건 = 미리보기가 사라진 직후 한 번 다시 계산. 반복 조회는 인스턴스 안 기억이 받는다)
#   6. 멈춘 동안 DB 트랜잭션률 ≤ 멈추기 전 × 배수                     DRILL_DB_RATE_FACTOR (기본 1.5 · 실측 1.05배)
#   7. 멈춘 동안 Hikari 대기 최대값                                   DRILL_HIKARI_PENDING_LIMIT (기본 2 · 실측 0)
#   8. 멈춘 동안 Redis 무관 읽기(student_runs) p95                    DRILL_P95_LIMIT_MS (기본 500 · 실측 최대 89.5ms)
#   9. 멈춘 동안 컨테이너 헬스 · /healthz 가 UP(200), 멈춘 채 다시 시작한 백엔드가 start_period 안에 건강해짐   DRILL_RESTART_HEALTHY_LIMIT_SEC (기본 90 = compose healthcheck start_period · Ruling 932)
# 출력에는 그 밖에 멈춤 구간별 응답 시간 · /healthz 응답 · 서킷이 닫힌 시각 · nginx 가 /healthz 에 낸 상태를 적는다.
#
# 사용
#   infra/scripts/check-redis-outage.sh                       작은 회차(기본, 약 3분 + 이미지 빌드)
#   DRILL_SIZE=full infra/scripts/check-redis-outage.sh       큰 회차(멈춤 60초씩)
#   DRILL_DRY=1 …                                             멈추지 않고 같은 구간·같은 계수를 돈다(계수가 맞는지 보는 용)
#   DRILL_RECOVERY_LIMIT_SEC=1 …                              기준이 실패할 수 있음을 보이는 용(종료 코드 ≠ 0)
# 환경변수: DRILL_PROJECT(기본 r60-redis) · DRILL_MEM(백엔드 메모리, 기본 3072m) · DRILL_SKIP_BUILD=1 · DRILL_KEEP_UP=1 · DRILL_OUT(결과 폴더, 기본 임시 폴더)
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROJECT="${DRILL_PROJECT:-r60-redis}"
IMAGE="school-bus-backend:r60-redis"
NGINX_HTTPS="https://localhost:38443"
BE_DIRECT="http://localhost:38081"
SIZE="${DRILL_SIZE:-small}"

case "$SIZE" in
  small) BASELINE=12 HOLD=12 COOL=25 SESSIONS=5 ;;
  full)  BASELINE=60 HOLD=60 COOL=60 SESSIONS=20 ;;
  *) echo "DRILL_SIZE 는 small 또는 full"; exit 2 ;;
esac
WARMUP=6
# 멈춘 채 백엔드 다시 시작 구간 — 건강해지기를 기다리는 최대 시간(compose healthcheck 의 start_period 90초에 여유) + 복구 대기
RESTART_WAIT=120
RESTART_COOL=10
# k6 가 도는 시간 = 준비 + 기준 + (멈춤 + 회복) × 2 + 멈춘 채 다시 시작 + 마무리
K6_SECONDS=$(( WARMUP + BASELINE + 2 * (HOLD + COOL) + RESTART_WAIT + RESTART_COOL + 30 ))

ANALYZE_ONLY="${DRILL_ANALYZE_ONLY:-}"   # 이미 돈 결과 폴더를 다른 기준값으로 다시 판정만 한다(스택을 띄우지 않는다)
OUT="${ANALYZE_ONLY:-${DRILL_OUT:-$(mktemp -d "${TMPDIR:-/tmp}/r60-redis-drill.XXXXXX")}}"
mkdir -p "$OUT"
EVENTS="$OUT/events.tsv"; SAMPLES="$OUT/samples.tsv"; HEALTHZ="$OUT/healthz.tsv"; RECOVERY="$OUT/recovery.tsv"
if [[ -z "$ANALYZE_ONLY" ]]; then : > "$EVENTS"; : > "$SAMPLES"; : > "$HEALTHZ"; : > "$RECOVERY"; fi

COMPOSE="docker compose -p $PROJECT -f $REPO/docker-compose.prod.yml -f $REPO/infra/redis-drill/docker-compose.drill.yml --env-file $REPO/infra/redis-drill/placeholder.env"
dc() { $COMPOSE "$@"; }
now_ms() { perl -MTime::HiRes=time -e 'printf "%d\n", time()*1000'; }
event() { printf '%s\t%s\n' "$1" "$(now_ms)" >> "$EVENTS"; echo "== $(date +%H:%M:%S) $1"; }

SAMPLER_PID=""; K6_PID=""
cleanup() {
  [[ -n "$SAMPLER_PID" ]] && kill "$SAMPLER_PID" 2>/dev/null || true
  [[ -n "$K6_PID" ]] && kill "$K6_PID" 2>/dev/null || true
  # 얼려 둔 Redis 가 남지 않게 풀어 둔다(이미 풀렸으면 무시).
  [[ -n "${REDIS_CID:-}" ]] && docker unpause "$REDIS_CID" >/dev/null 2>&1 || true
  if [[ "${DRILL_KEEP_UP:-}" == 1 ]]; then
    echo "== DRILL_KEEP_UP=1 — 스택을 내리지 않는다 (내리기: docker compose -p $PROJECT … down) =="
  else
    echo "== 스택 내림 (down, -v 없음) =="
    dc down --remove-orphans >/dev/null 2>&1 || true
    [[ -n "${DRILL_DIR:-}" ]] && rm -rf "$DRILL_DIR"
  fi
}

live_drill() {
trap cleanup EXIT

# ── 스택 ──
DRILL_DIR="$(mktemp -d "${TMPDIR:-/tmp}/r60-redis-drill-certs.XXXXXX")"; export DRILL_DIR
mkdir -p "$DRILL_DIR/letsencrypt/live/api.example.com" "$DRILL_DIR/www"; : > "$DRILL_DIR/htpasswd"
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=api.example.com" \
  -keyout "$DRILL_DIR/letsencrypt/live/api.example.com/privkey.pem" \
  -out "$DRILL_DIR/letsencrypt/live/api.example.com/fullchain.pem" 2>/dev/null

if [[ "${DRILL_SKIP_BUILD:-}" == 1 ]] && docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "== 이미지 빌드 생략 ($IMAGE 있음) =="
else
  echo "== 백엔드 이미지 빌드 ($IMAGE) =="
  docker build -q -t "$IMAGE" "$REPO/backend" >/dev/null
fi
echo "== 스택 기동 (project=$PROJECT, 백엔드 메모리 ${DRILL_MEM:-3072m}, 크기 $SIZE) =="
dc up -d --wait --wait-timeout 300 postgres redis backend proxy prometheus
REDIS_CID="$(dc ps -q redis)"; BACKEND_CID="$(dc ps -q backend)"; PG_CID="$(dc ps -q postgres)"; PROXY_CID="$(dc ps -q proxy)"

# 승인 상세가 지도 스텁을 부르는 건이 있어야 호출 수를 본다(시드의 승인 대기 건 1).
APPROVAL_ID=1
pending="$(docker exec "$PG_CID" psql -U schoolbus -d schoolbus -Atc "select count(*) from change_request where id = $APPROVAL_ID and status = 'pending'")"
[[ "$pending" == 1 ]] || { echo "승인 대기 건 $APPROVAL_ID 이 없다(시드가 바뀌었나) — 연습을 중단한다"; exit 2; }

# ── 표본 수집: 1초마다 지표, 3초마다 /healthz ──
metric() { # 이름 정규식의 값 하나(없으면 0)
  awk -v pat="$1" '$0 ~ "^" pat { s += $NF } END { printf "%s", s + 0 }' <<<"$METRICS"
}
sampler() {
  local i=0
  while :; do
    METRICS="$(docker exec "$BACKEND_CID" curl -fsS http://localhost:8081/actuator/prometheus 2>/dev/null || true)"
    local pg
    pg="$(docker exec "$PG_CID" psql -U schoolbus -d schoolbus -Atc "select xact_commit + xact_rollback from pg_stat_database where datname = 'schoolbus'" 2>/dev/null || echo 0)"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(now_ms)" \
      "$(metric 'hikaricp_connections_pending[{]')" "$(metric 'hikaricp_connections_active[{]')" \
      "$(metric 'schoolbus_routing_stub_calls_total[{][^}]*caller="on_demand"')" \
      "$(metric 'resilience4j_circuitbreaker_state[{][^}]*name="redis"[^}]*state="closed"')" \
      "$(metric 'schoolbus_ws_relay_publish_failed_total')" "$(metric 'schoolbus_position_fallback_total')" \
      "$pg" "$(metric 'schoolbus_ws_relay_received_total')" >> "$SAMPLES"
    if (( i % 3 == 0 )); then
      printf '%s\t%s\t%s\t%s\n' "$(now_ms)" \
        "$(curl -ks -o /dev/null -m 4 -w '%{http_code}' "$NGINX_HTTPS/healthz" || true)" \
        "$(curl -s -o /dev/null -m 4 -w '%{http_code}' "$BE_DIRECT/healthz" || true)" \
        "$(docker inspect -f '{{.State.Health.Status}}' "$BACKEND_CID" 2>/dev/null || echo ?)" >> "$HEALTHZ"
    fi
    i=$((i + 1)); sleep 1
  done
}

wait_subscribed() { # 중계 채널 구독이 Redis 에 보일 때까지 — 걸린 초를 돌려준다(최대 90초)
  local from="$1" deadline=$(( $(now_ms) + 90000 )) n
  while (( $(now_ms) < deadline )); do
    n="$(docker exec "$REDIS_CID" redis-cli PUBSUB NUMSUB schoolbus:relay:ws 2>/dev/null | tail -1 | tr -d '\r' || true)"
    if [[ "$n" =~ ^[0-9]+$ ]] && (( n >= 1 )); then
      echo $(( ($(now_ms) - from) ))
      return 0
    fi
    sleep 0.2
  done
  echo -1
}

# ── 부하 시작 ──
echo "== 부하 시작 (k6 ${K6_SECONDS}초, 구독자 $SESSIONS) =="
k6 run --quiet --out "json=$OUT/k6.json" \
  -e NGINX_URL="$NGINX_HTTPS" -e BE_URL="$BE_DIRECT" -e SESSIONS="$SESSIONS" -e DURATION_SEC="$K6_SECONDS" -e APPROVAL_ID="$APPROVAL_ID" \
  "$REPO/backend/load/k6/r60_redis_outage.js" > "$OUT/k6.log" 2>&1 &
K6_PID=$!
sampler & SAMPLER_PID=$!
event k6_start
sleep "$WARMUP"; event baseline_start
sleep "$BASELINE"

# 1) kill
event kill_begin
[[ "${DRILL_DRY:-}" == 1 ]] || docker kill "$REDIS_CID" >/dev/null
sleep "$HOLD"
event kill_end
[[ "${DRILL_DRY:-}" == 1 ]] || docker start "$REDIS_CID" >/dev/null
RECOVERY_KILL_MS="$(wait_subscribed "$(now_ms)")"
event kill_subscribed
echo "   중계 구독 복구(kill): ${RECOVERY_KILL_MS} ms"; printf 'kill\t%s\n' "$RECOVERY_KILL_MS" >> "$RECOVERY"
sleep "$COOL"

# 2) pause
event pause_begin
[[ "${DRILL_DRY:-}" == 1 ]] || docker pause "$REDIS_CID" >/dev/null
sleep "$HOLD"
event pause_end
[[ "${DRILL_DRY:-}" == 1 ]] || docker unpause "$REDIS_CID" >/dev/null
RECOVERY_PAUSE_MS="$(wait_subscribed "$(now_ms)")"
event pause_subscribed
echo "   중계 구독 복구(pause): ${RECOVERY_PAUSE_MS} ms"; printf 'pause\t%s\n' "$RECOVERY_PAUSE_MS" >> "$RECOVERY"
sleep "$COOL"

# 3) Redis 가 멈춘 채 백엔드를 다시 시작 — start_period 안에 건강해져야 한다(Ruling 932). nginx 는 다시 시작한 컨테이너의 새 주소를 reload 로 다시 찾는다(운영 배포는 proxy 도 다시 시작한다).
event restart_begin
[[ "${DRILL_DRY:-}" == 1 ]] || docker kill "$REDIS_CID" >/dev/null
docker restart "$BACKEND_CID" >/dev/null
restart_t0="$(now_ms)"; RESTART_HEALTHY_MS=-1
while (( $(now_ms) < restart_t0 + RESTART_WAIT * 1000 )); do
  if [[ "$(docker inspect -f '{{.State.Health.Status}}' "$BACKEND_CID" 2>/dev/null)" == healthy ]]; then RESTART_HEALTHY_MS=$(( $(now_ms) - restart_t0 )); break; fi
  sleep 1
done
event restart_healthy
echo "   다시 시작한 백엔드가 건강해진 시간(Redis 멈춘 채): ${RESTART_HEALTHY_MS} ms"; printf 'restart_healthy\t%s\n' "$RESTART_HEALTHY_MS" >> "$RECOVERY"
docker exec "$PROXY_CID" nginx -s reload >/dev/null 2>&1 || true
sleep 6
[[ "${DRILL_DRY:-}" == 1 ]] || docker start "$REDIS_CID" >/dev/null
wait_subscribed "$(now_ms)" > /dev/null
event restart_end
sleep "$RESTART_COOL"

event drill_end
wait "$K6_PID" 2>/dev/null || true; K6_PID=""
kill "$SAMPLER_PID" 2>/dev/null || true; wait "$SAMPLER_PID" 2>/dev/null || true; SAMPLER_PID=""

}

[[ -n "$ANALYZE_ONLY" ]] || live_drill

# ── 판정 ──
EXIT=0
python3 -I - "$OUT" <<'PY' || EXIT=$?
import json, os, sys, math

out = sys.argv[1]
env = os.environ.get
LIM = dict(
    five_xx=int(env("DRILL_5XX_LIMIT", "0")), send_fail=int(env("DRILL_SEND_FAIL_LIMIT", "0")),
    recovery_sec=float(env("DRILL_RECOVERY_LIMIT_SEC", "10")), fill_sec=float(env("DRILL_FILL_LIMIT_SEC", "30")),
    map_extra=float(env("DRILL_MAP_EXTRA_CALLS", "2")), db_factor=float(env("DRILL_DB_RATE_FACTOR", "1.5")),
    hikari=float(env("DRILL_HIKARI_PENDING_LIMIT", "2")), restart_healthy_sec=float(env("DRILL_RESTART_HEALTHY_LIMIT_SEC", "90")), p95_ms=float(env("DRILL_P95_LIMIT_MS", "500")),
)

events = {}
for line in open(f"{out}/events.tsv"):
    k, v = line.rstrip("\n").split("\t"); events[k] = int(v)

def ms(iso):  # k6 json 시각 → epoch ms (소수 자리가 9자리까지 오므로 6자리로 줄인다)
    import re
    from datetime import datetime
    return int(datetime.fromisoformat(re.sub(r"(\.\d{6})\d+", r"\1", iso)).timestamp() * 1000)

pts = []  # (t, metric, value, tags)
for line in open(f"{out}/k6.json"):
    try:
        row = json.loads(line)
    except ValueError:
        continue
    if row.get("type") != "Point":
        continue
    d = row["data"]
    pts.append((ms(d["time"]), row["metric"], d["value"], d.get("tags", {})))

samples = []
for line in open(f"{out}/samples.tsv"):
    f = line.rstrip("\n").split("\t")
    if len(f) == 9:
        samples.append([int(f[0])] + [float(x) if x not in ("", None) else 0.0 for x in f[1:]])
health = [l.rstrip("\n").split("\t") for l in open(f"{out}/healthz.tsv") if l.strip()]

t0 = events["baseline_start"]
# 다시 시작 구간이 없는 옛 결과 폴더도 다시 판정할 수 있게 구간 끝을 연습 끝으로 둔다
restart_begin, restart_end = events.get("restart_begin", events["drill_end"]), events.get("restart_end", events["drill_end"])
phases = {  # 구간 이름 → (시작, 끝) epoch ms
    "before": (t0, events["kill_begin"]),
    "kill": (events["kill_begin"], events["kill_subscribed"] + 5000),
    "pause": (events["pause_begin"], events["pause_subscribed"] + 5000),
    "after": (events["pause_subscribed"] + 5000, restart_begin),
}
# 백엔드를 일부러 다시 시작한 구간(요청이 실패하는 것이 정상)은 5xx · 송신 실패 계수에서 뺀다 — 다시 시작이 끝나고 10초 뒤부터 센다.
steady = [(t0, restart_begin), (restart_end + 10000, events["drill_end"])]

APP = {"position_post", "bus_position", "student_runs", "approval_detail"}
def in_(t, w): return w[0] <= t < w[1]
def http_points(name=None, w=None):
    return [p for p in pts if p[1] == "http_req_duration" and (name is None or p[3].get("name") == name)
            and p[3].get("name") in APP and (w is None or in_(p[0], w))]
def is_5xx(p):
    s = p[3].get("status", "0")
    return int(s) >= 500 or int(s) == 0
def p95(values):
    if not values: return float("nan")
    v = sorted(values); return v[min(len(v) - 1, math.ceil(0.95 * len(v)) - 1)]
def rate(col, w):
    ss = [s for s in samples if in_(s[0], w)]
    if len(ss) < 2: return float("nan")
    return (ss[-1][col] - ss[0][col]) / ((ss[-1][0] - ss[0][0]) / 1000.0)
def peak(col, w):
    ss = [s[col] for s in samples if in_(s[0], w)]
    return max(ss) if ss else float("nan")

fails = []
print("\n===== Redis 멈춤 연습 결과 =====")
print("요청 계수(이름별 · 연습 전체):")
for name in sorted(APP):
    pp = [p for w in steady for p in http_points(name, w)]
    codes = {}
    for p in pp: codes[p[3].get("status", "?")] = codes.get(p[3].get("status", "?"), 0) + 1
    print(f"  {name:16s} 요청 {len(pp):5d}  상태 {codes}")

five = [p for w in steady for p in http_points(None, w) if is_5xx(p)]
send_fail = [p for w in steady for p in http_points("position_post", w) if not (200 <= int(p[3].get("status", "0")) < 300)]
print(f"\n1. Redis 무관 REST 5xx · 시간 초과: {len(five)} (기준 ≤ {LIM['five_xx']})")
if len(five) > LIM["five_xx"]: fails.append("5xx")
print(f"2. 위치 송신 실패: {len(send_fail)} (기준 ≤ {LIM['send_fail']})")
if len(send_fail) > LIM["send_fail"]: fails.append("send_fail")

recovery = dict(l.rstrip("\n").split("\t") for l in open(f"{out}/recovery.tsv") if l.strip())
rk, rp = int(recovery["kill"]) / 1000.0, int(recovery["pause"]) / 1000.0
print(f"3. 중계 구독 복구: kill 뒤 {rk:.1f}초 · pause 뒤 {rp:.1f}초 (기준 ≤ {LIM['recovery_sec']}초, -1 은 90초 안에 복구 안 됨)")
for label, v in (("kill", rk), ("pause", rp)):
    if v < 0 or v > LIM["recovery_sec"]: fails.append(f"recovery_{label}")

def fill_time(restore_ms, until_ms):
    # 다시 켠 뒤 낡은 값(나이 > 2초) 또는 빈 응답(lat 없음)을 마지막으로 받은 시각까지 — 최신 값이 한 번 돌아온 시각이 아니라 그런 응답이 더는 안 오는 시각이다.
    stale = [p[0] for p in pts if p[1] == "snapshot_age_sec" and restore_ms <= p[0] < until_ms and p[2] > 2]
    missing = [p[0] for p in pts if p[1] == "snapshot_missing" and restore_ms <= p[0] < until_ms]
    both = stale + missing
    return ((max(both) - restore_ms) / 1000.0 if both else 0.0), len(stale), len(missing)
fk, fk_n, fk_m = fill_time(events["kill_end"], events["pause_begin"])
fp, fp_n, fp_m = fill_time(events["pause_end"], restart_begin)
print(f"4. 다시 켠 뒤 REST 위치 조회가 낡은 값(나이 > 2초)이나 빈 응답을 마지막으로 돌려준 시각: kill {fk:.1f}초(낡은 {fk_n}건 · 빈 {fk_m}건) · pause {fp:.1f}초(낡은 {fp_n}건 · 빈 {fp_m}건) (기준 ≤ {LIM['fill_sec']}초)")
for label, v in (("kill", fk), ("pause", fp)):
    if v > LIM["fill_sec"]: fails.append(f"fill_{label}")
ages = {name: max([p[2] for p in pts if p[1] == "snapshot_age_sec" and in_(p[0], w)] or [float("nan")]) for name, w in phases.items()}
print(f"   위치 조회 값 나이 최대(초): {ages}")
gaps = {name: max([p[2] for p in pts if p[1] == "ws_msg_gap_ms" and in_(p[0], w)] or [float("nan")]) for name, w in phases.items()}
print(f"   구독자 방송 간격 최대(ms): {gaps}")

COL = dict(hikari_pending=1, hikari_active=2, map_calls=3, cb_closed=4, relay_failed=5, pos_fallback=6, pg=7, relay_received=8)
map_before = rate(COL["map_calls"], phases["before"])
def extra_map_calls(w):  # 구간 안 호출 수 − 멈추기 전 호출률로 기대한 수
    ss = [s for s in samples if in_(s[0], w)]
    return (ss[-1][COL["map_calls"]] - ss[0][COL["map_calls"]]) - map_before * (ss[-1][0] - ss[0][0]) / 1000.0 if len(ss) >= 2 else float("nan")
print(f"5. 지도 API(스텁) 온디맨드 호출: 멈추기 전 {map_before:.3f}건/초 · 구간 안 늘어난 호출 수 kill {extra_map_calls(phases['kill']):.1f} · pause {extra_map_calls(phases['pause']):.1f} (기준 ≤ {LIM['map_extra']:.0f} — 미리보기가 사라진 직후 한 번 다시 계산 + 여유 1)")
for label in ("kill", "pause"):
    if extra_map_calls(phases[label]) > LIM["map_extra"]: fails.append(f"map_{label}")
db_before = rate(COL["pg"], phases["before"])
print(f"6. DB 트랜잭션률(건/초): 멈추기 전 {db_before:.1f} · kill 중 {rate(COL['pg'], phases['kill']):.1f} · pause 중 {rate(COL['pg'], phases['pause']):.1f} · 이후 {rate(COL['pg'], phases['after']):.1f} (기준: 멈추기 전 × {LIM['db_factor']} 이하)")
for label in ("kill", "pause"):
    if rate(COL["pg"], phases[label]) > db_before * LIM["db_factor"]: fails.append(f"db_{label}")
hp = {name: peak(COL["hikari_pending"], w) for name, w in phases.items()}
print(f"7. Hikari 대기 최대: {hp} (기준 멈춤 구간 ≤ {LIM['hikari']})  · 사용 중 최대: " + str({name: peak(COL['hikari_active'], w) for name, w in phases.items()}))
for label in ("kill", "pause"):
    if hp[label] > LIM["hikari"]: fails.append(f"hikari_{label}")
print("8. 응답 시간 p95(ms) — 이름 × 구간:")
for name in sorted(APP):
    row = {ph: round(p95([p[2] for p in http_points(name, w)]), 1) for ph, w in phases.items()}
    print(f"  {name:16s} {row}")
for label in ("kill", "pause"):
    v = p95([p[2] for p in http_points("student_runs", phases[label])])
    if v > LIM["p95_ms"]: fails.append(f"p95_{label}")

for label, end_key, until in (("kill", "kill_end", events["pause_begin"]), ("pause", "pause_end", restart_begin)):
    back = [s[0] for s in samples if events[end_key] <= s[0] < until and s[COL["cb_closed"]] >= 1]
    when = f"{(back[0] - events[end_key]) / 1000.0:.1f}초 뒤" if back else "닫히지 않음"
    opened = [s[0] for s in samples if events[label + "_begin"] <= s[0] < events[end_key] and s[COL["cb_closed"]] < 1]
    first_open = f"{(opened[0] - events[label + '_begin']) / 1000.0:.1f}초 뒤" if opened else "열리지 않음"
    print(f"   Redis 서킷: {label} 시작 {first_open} 열림 · 다시 켠 {when} 닫힘")
print("   방송 중계 발행 실패 · 위치 DB 대체 · 중계 수신(구간 안 증가분):")
for label, w in phases.items():
    ss = [s for s in samples if in_(s[0], w)]
    if len(ss) >= 2:
        print(f"   {label:6s} 발행 실패 +{int(ss[-1][COL['relay_failed']] - ss[0][COL['relay_failed']])} · 위치 DB 대체 +{int(ss[-1][COL['pos_fallback']] - ss[0][COL['pos_fallback']])} · 중계 수신 +{int(ss[-1][COL['relay_received']] - ss[0][COL['relay_received']])}")

# 9. 멈춘 동안 컨테이너 헬스 · /healthz 가 UP, 멈춘 채 다시 시작한 백엔드가 start_period 안에 건강해짐(Ruling 932)
bad_health = [h for label in ("kill", "pause") for h in health if in_(int(h[0]), phases[label]) and (h[1], h[2], h[3]) != ("200", "200", "healthy")]
print(f"\n9. Redis 가 멈춘 동안 /healthz(nginx · 직접) · 컨테이너 헬스가 UP 이 아닌 표본: {len(bad_health)} (기준 0)")
if bad_health: fails.append("health_not_up")
if "restart_healthy" in recovery:
    restart_ms = int(recovery["restart_healthy"])
    print(f"   Redis 가 멈춘 채 다시 시작한 백엔드가 건강해진 시간: {restart_ms / 1000.0:.1f}초 (기준 ≤ {LIM['restart_healthy_sec']}초 = compose healthcheck start_period, -1 은 120초 안에 못 됨)")
    if restart_ms < 0 or restart_ms / 1000.0 > LIM["restart_healthy_sec"]: fails.append("restart_healthy")
    after_restart = [h for h in health if events["restart_healthy"] + 8000 <= int(h[0]) < restart_end]
    print(f"   다시 시작 뒤 /healthz 표본 — 백엔드 직접 {[h[2] for h in after_restart]} · nginx(reload 뒤) {[h[1] for h in after_restart]}")
else:
    print("   (이 결과 폴더에는 멈춘 채 다시 시작한 구간이 없다)")

print("\n/healthz — (시각 초 · nginx 상태 · 백엔드 직접 상태 · 컨테이너 헬스) 구간별 요약:")
for ph, w in phases.items():
    rows = [h for h in health if in_(int(h[0]), w)]
    tally = {}
    for h in rows: tally[(h[1], h[2], h[3])] = tally.get((h[1], h[2], h[3]), 0) + 1
    print(f"  {ph:6s} {tally}")
print("  (nginx 는 /healthz 의 접근 기록을 끄고 백엔드 상태를 그대로 넘긴다 — 위 두 번째 열이 nginx 를 거친 응답이다)")

verdict = "통과" if not fails else "실패 — " + ", ".join(fails)
print(f"\n판정: {verdict}")
json.dump({"fails": fails, "limits": LIM}, open(f"{out}/result.json", "w"))
sys.exit(0 if not fails else 1)
PY
echo "결과 폴더: $OUT"
exit "$EXIT"
