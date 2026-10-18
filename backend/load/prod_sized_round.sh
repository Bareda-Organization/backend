#!/usr/bin/env bash
# 운영 크기 컨테이너(start_prod_sized.sh)에 r3_mixed.sh 한 회차를 걸고, 회차 전후의 환경 값을 함께 남긴다.
#  - 호스트 잡음(측정과 무관한 프로세스 CPU 상위) 전·후 → results/<라벨>_host_noise.log. 시작 전 값이 NOISE_MAX_PCT(기본 300%)를 넘으면 시작하지 않는다
#  - 컨테이너 안 cgroup 표본(cgroup_sampler.sh) → results/cg_<라벨>.csv · JVM 표본(sampler.sh) → results/sample_<라벨>.csv
#  - 앱 · Postgres · Redis 컨테이너의 CPU 초(cgroup usage_usec 전후 차) · 컨테이너 메모리 최고 · OOMKilled → results/<라벨>_env.json
#  - r3_mixed.sh 출력 → results/<라벨>_run.out, r46_judge.py 판정 → results/<라벨>_judge.txt (종료 코드는 판정 결과를 따른다)
#
# 실행: ./prod_sized_round.sh <라벨> [회차 명령 …]   — 명령을 안 주면 `r3_mixed.sh 570 1`. 라벨은 ROUND_LABEL 로 명령에 넘어간다. R3_* 환경변수도 그대로 넘어간다.
#   REDIS_DB=13 R3_ADMINS=50 R3_INTERVAL=2 R3_FIXED_RATE=1 ./prod_sized_round.sh p1 ./r3_mixed.sh 570 1
#   NO_JUDGE=1 REDIS_DB=13 ./prod_sized_round.sh d1 ./destination_round.sh     (r46_judge.py 로 못 읽는 회차는 판정을 건너뛴다)
# 폴링을 겸하는 다른 k6(L-2·L-6)를 같은 회차에 겹치려면 BACKGROUND_HOOK 에 실행 파일을 준다 — 위치 부하가 돌기 시작할 때(회차 시작 + 75초) 백그라운드로 시작한다.
set -euo pipefail
LABEL="${1:?라벨}"; shift
[ $# -gt 0 ] || set -- "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/r3_mixed.sh" 570 1
APP="${APP_NAME:-r59-load-app}"
REDIS_DB="${REDIS_DB:?Redis DB 번호}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RES="$DIR/results"; mkdir -p "$RES"
NOISE_MAX_PCT="${NOISE_MAX_PCT:-300}"
SAMPLE_SECS="${SAMPLE_SECS:-$((60 + ${R3_HOLD:-180} + 30))}"   # r3_mixed.sh 의 TOTAL(RAMP 60 + HOLD + 30)과 같다

# 측정과 무관한 프로세스 CPU(%) — top 의 두 번째 표본(첫 표본은 누적 평균이라 쓰지 않는다). 측정에 쓰는 프로세스는 뺀다.
noise() {
    top -l 2 -s 2 -n 25 -o cpu -stats command,cpu 2>/dev/null | awk '
        /^COMMAND/ {n++; next}
        n == 2 && NF >= 2 {
            pct = $NF + 0; name = $0; sub(/ +[0-9.]+ *$/, "", name)
            if (name ~ /^(k6|docker|com.docker|com.apple.Virtua|vpnkit|qemu|psql|python|curl|bash|sh|ps|awk|top|caffeinate|2\.1\.|claude|node)/) next
            total += pct; if (++k <= 3) top3 = top3 sprintf("%s %.1f%% · ", name, pct)
        }
        END { printf "%.0f%% (상위 %s)\n", total, top3 }'
}
BEFORE_NOISE="$(noise)"; echo "[$(date +%H:%M:%S)] 시작 전 호스트 잡음 $BEFORE_NOISE" | tee "$RES/${LABEL}_host_noise.log"
[ "${BEFORE_NOISE%%%*}" -le "$NOISE_MAX_PCT" ] || { echo "호스트 잡음 ${BEFORE_NOISE%%%*}% > ${NOISE_MAX_PCT}% — 시작하지 않는다" >&2; exit 3; }

export ROUND_LABEL="$LABEL" CGROUP_CPU=1 PROM_URL="${PROM_URL:-http://localhost:18081/actuator/prometheus}"   # 관리 포트(start_prod_sized.sh)
[ -z "${PRE_HOOK:-}" ] || "$PRE_HOOK"   # 토큰 발급처럼 측정 구간 밖에서 끝낼 일
"$DIR/cgroup_sampler.sh" start "$LABEL" "$SAMPLE_SECS"
T0="$(date +%s)"
if [ -n "${BACKGROUND_HOOK:-}" ]; then ( sleep "${BACKGROUND_DELAY:-75}"; "$BACKGROUND_HOOK" > "$RES/${LABEL}_hook.out" 2>&1 ) & HOOK=$!; fi
set +e
ROUND_LABEL="$LABEL" "$@" 2>&1 | tee "$RES/${LABEL}_run.out"
R3_EXIT=${PIPESTATUS[0]}
[ -z "${HOOK:-}" ] || wait "$HOOK"
set -e
T1="$(date +%s)"
read -r W0 W1 APP_CPU0 PG_CPU0 RD_CPU0 APP_CPU1 PG_CPU1 RD_CPU1 < "$RES/${LABEL}_cg_cpu.txt"   # r3_mixed.sh 가 측정 구간 시작·끝에 남긴 값
[ -z "${HOOK_OUT:-}" ] || true
sleep $(( SAMPLE_SECS - (T1 - T0) > 0 ? SAMPLE_SECS - (T1 - T0) + 2 : 2 ))   # 컨테이너 안 표본이 끝날 때까지
"$DIR/cgroup_sampler.sh" collect "$LABEL" > /dev/null
echo "[$(date +%H:%M:%S)] 끝난 뒤 호스트 잡음 $(noise)" | tee -a "$RES/${LABEL}_host_noise.log"

OOM="$(docker inspect --format '{{.State.OOMKilled}}' "$APP")"; RESTARTS="$(docker inspect --format '{{.RestartCount}}' "$APP")"
python3 - "$RES/${LABEL}_env.json" "$RES/cg_${LABEL}.csv" "$RES/sample_${LABEL}.csv" "$W0" "$W1" "$APP_CPU0" "$APP_CPU1" "$PG_CPU0" "$PG_CPU1" "$RD_CPU0" "$RD_CPU1" "$OOM" "$RESTARTS" "$R3_EXIT" <<'PY'
import csv, json, sys
out, cg, smp, t0, t1 = sys.argv[1:6]
a0, a1, p0, p1, r0, r1 = map(float, sys.argv[6:12])
oom, restarts, r3_exit = sys.argv[12], int(sys.argv[13]), int(sys.argv[14])
wall = max(int(t1) - int(t0), 1)
def col(path, name, scale=1.0):
    vals = []
    for r in csv.DictReader(open(path)):
        try:
            if 'epoch' in r and r['epoch'] and not (int(t0) <= int(r['epoch']) <= int(t1)): continue   # 측정 구간(r3_mixed.sh CPU0~CPU1)만
            vals.append(float(r[name]) * scale)
        except (KeyError, ValueError): pass
    return vals
mb = 1 / 1048576
mem = col(cg, 'mem_current', mb); peak = col(cg, 'mem_peak', mb)
thr = col(cg, 'throttled_usec')
doc = {
    'wall_sec': wall, 'oom_killed': oom, 'restart_count': restarts, 'r3_exit': r3_exit,
    'cpu_sec': {'app': round((a1 - a0) / 1e6, 1), 'postgres': round((p1 - p0) / 1e6, 1), 'redis': round((r1 - r0) / 1e6, 1)},
    'cpu_cores_avg': {'app': round((a1 - a0) / 1e6 / wall, 2), 'postgres': round((p1 - p0) / 1e6 / wall, 2), 'redis': round((r1 - r0) / 1e6 / wall, 2)},
    'container_mem_mb': {'current_max': round(max(mem), 0), 'cgroup_peak_since_start': round(max(peak), 0)},
    'cpu_throttled_sec': round((max(thr) - min(thr)) / 1e6, 1) if thr else None,
    'jvm_max': {k: max(col(smp, k)) for k in ('heap_mb', 'heap_committed_mb', 'nonheap_mb', 'direct_mb', 'live_threads', 'gc_pause_max_s', 'hikari_pending', 'hikari_acquire_max_s') if col(smp, k)},
}
doc['app_pg_redis_cores_avg_sum'] = round(sum(doc['cpu_cores_avg'].values()), 2)
json.dump(doc, open(out, 'w'), ensure_ascii=False, indent=1)
print(json.dumps(doc, ensure_ascii=False))
PY
[ "${NO_JUDGE:-0}" != "1" ] || exit "$R3_EXIT"
set +e
"$DIR/r46_judge.py" "$LABEL" | tee "$RES/${LABEL}_judge.txt"; JUDGE_EXIT=${PIPESTATUS[0]}
echo "judge_exit=$JUDGE_EXIT oom_killed=$OOM" | tee -a "$RES/${LABEL}_judge.txt"
exit "$JUDGE_EXIT"
