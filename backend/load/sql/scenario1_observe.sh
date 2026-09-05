#!/usr/bin/env bash
# 시나리오 1 관측 — scenario1_prep.sql 로 N건을 심고, RunConfirmationScheduler 가 전부 idle 을
# 벗어날 때까지 폴링한다. 재는 것은 §5.3 판정 두 가지 — 도래→확정 완료 지연, 미확정 회차 수.
#
# 실행:
#   ./scenario1_observe.sh <N> <psql접속문자열> <prometheus URL> [폴링간격초] [타임아웃초]
# 예:
#   ./scenario1_observe.sh 200 "postgresql://schoolbus:schoolbus@localhost:15432/schoolbus_load" \
#     http://localhost:18080/actuator/prometheus 2 1800
set -euo pipefail

N="${1:?N 필요}"
DB_URL="${2:?psql 접속 문자열 필요}"
PROM_URL="${3:?prometheus URL 필요}"
POLL_INTERVAL="${4:-2}"
TIMEOUT_SEC="${5:-1800}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULTS_DIR="$SCRIPT_DIR/../results"
mkdir -p "$RESULTS_DIR"

# $1 = prometheus 메트릭 이름(태그 없는 형태만 다룬다 — 이 저장소의 새 지표 3종은 전부 무태그).
fetch_metric() {
    curl -sf "$PROM_URL" | awk -v name="$1" '$0 ~ "^"name" " {print $NF; found=1} END {if (!found) print "0"}'
}

before_lag_count=$(fetch_metric 'schoolbus_run_confirmation_lag_seconds_count')
before_lag_sum=$(fetch_metric 'schoolbus_run_confirmation_lag_seconds_sum')

prep_out=$(psql "$DB_URL" -v n="$N" -f "$SCRIPT_DIR/scenario1_prep.sql" -t -A -F',' | grep -v '^$' | tail -1)
tag=$(echo "$prep_out" | cut -d',' -f1)
runs_created=$(echo "$prep_out" | cut -d',' -f5)
t_inserted=$(date +%s.%N)

echo "심음: tag=${tag} runs_created=${runs_created} (요청 N=${N})"
if [ "$runs_created" != "$N" ]; then
    echo "⚠ 요청한 N(${N})과 실제 생성 수(${runs_created})가 다르다 — scenario1_prep.sql 을 확인하라" >&2
fi

remaining=-1
start=$(date +%s)
while true; do
    now=$(date +%s)
    elapsed=$((now - start))
    remaining=$(psql "$DB_URL" -t -A -c \
        "SELECT count(*) FROM run r JOIN bus b ON r.bus_id = b.id WHERE b.bus_no LIKE 'LOAD-${tag}-%' AND r.status = 'idle'")
    echo "t+${elapsed}s remaining=${remaining}"
    if [ "$remaining" = "0" ]; then
        break
    fi
    if [ "$elapsed" -ge "$TIMEOUT_SEC" ]; then
        echo "⚠ 타임아웃(${TIMEOUT_SEC}s) — remaining=${remaining} 남은 채 종료. N=${N} 까지 미관측으로 기록한다." >&2
        break
    fi
    sleep "$POLL_INTERVAL"
done
t_drained=$(date +%s.%N)

after_lag_count=$(fetch_metric 'schoolbus_run_confirmation_lag_seconds_count')
after_lag_sum=$(fetch_metric 'schoolbus_run_confirmation_lag_seconds_sum')
unconfirmed_gauge=$(fetch_metric 'schoolbus_run_unconfirmed')
throttled=$(fetch_metric 'schoolbus_routing_stub_load_throttled_total')
timeout_ct=$(fetch_metric 'schoolbus_routing_stub_load_timeout_total')
failure_injected=$(fetch_metric 'schoolbus_routing_stub_load_failure_injected_total')

delta_count=$(echo "${after_lag_count} - ${before_lag_count}" | bc)
delta_sum=$(echo "${after_lag_sum} - ${before_lag_sum}" | bc)
avg_lag_sec="null"
if [ "$(echo "${delta_count} > 0" | bc)" = "1" ]; then
    avg_lag_sec=$(echo "scale=3; ${delta_sum} / ${delta_count}" | bc)
fi

drain_sec=$(echo "${t_drained} - ${t_inserted}" | bc)
timed_out="false"
[ "$remaining" != "0" ] && timed_out="true"

out_file="${RESULTS_DIR}/scenario1_N${N}_${tag}.json"
cat > "$out_file" <<JSON
{
  "scenario": 1,
  "n_requested": ${N},
  "runs_created": ${runs_created},
  "tag": "${tag}",
  "timed_out": ${timed_out},
  "remaining_at_end": ${remaining},
  "drain_seconds": ${drain_sec},
  "confirmation_lag_delta_count": ${delta_count},
  "confirmation_lag_delta_sum_seconds": ${delta_sum},
  "confirmation_lag_avg_seconds": ${avg_lag_sec},
  "unconfirmed_gauge_after": ${unconfirmed_gauge},
  "stub_throttled_after": ${throttled},
  "stub_timeout_after": ${timeout_ct},
  "stub_failure_injected_after": ${failure_injected}
}
JSON

echo "결과 기록: ${out_file}"
cat "$out_file"
