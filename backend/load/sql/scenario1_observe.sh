#!/usr/bin/env bash
# 시나리오 1 관측 — scenario1_prep.sql 로 N건을 심고, RunConfirmationScheduler 가 전부 idle 을
# 벗어날 때까지 폴링한다. 재는 것은 §5.3 판정 두 가지 — 도래→확정 완료 지연, 미확정 회차 수.
#
# ⚠ 이 호스트에는 로컬 psql 바이너리가 없다(확인: which psql, Homebrew·Postgres.app 경로 전부 부재) —
# 그래서 모든 psql 호출을 docker exec 로 컨테이너 안 psql 을 쓴다. DB_URL 은 여전히 호스트 접속
# 문자열(포트 15432) 형태로 받되, 여기서는 DB 이름만 뽑아 쓰고 실제 접속은 컨테이너 안(5432)에서 한다.
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

PG_CONTAINER="${PG_CONTAINER:-school-bus-postgres-1}"
DB_NAME="${DB_URL##*/}"

psql_db() {
    # -q 필수 — 없으면 BEGIN;/COMMIT; 명령 완료 태그가 stdout 에 섞여 tail -1 이 데이터 행 대신
    # "COMMIT" 문자열을 집어간다(2026-09-05 N=10 첫 실행에서 실제로 재현·확인).
    docker exec -i "$PG_CONTAINER" psql -U schoolbus -d "$DB_NAME" -q "$@"
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULTS_DIR="$SCRIPT_DIR/../results"
mkdir -p "$RESULTS_DIR"

# $1 = prometheus 메트릭 이름. micrometer 가 common tag(application="school-bus")를 자동으로 붙여
# 실제 줄은 "이름{application=...} 값" 형태다 — 이름 뒤에 스페이스만 오는 무태그 형태를 가정한
# 첫 버전은 이 라벨 때문에 항상 매치 실패해 델타가 전부 0으로 나왔다(2026-09-05 N=10 재현 확인).
# 이름 뒤에 스페이스 또는 '{' 가 오는 두 형태를 모두 매치한다. 값은 항상 마지막 필드다.
#
# ⚠ F5 S2 목표 4 이후 schoolbus_routing_stub_load_throttled_total 은 caller 태그(batch/on_demand)로
# 갈려 같은 이름이 두 줄로 나온다 — 매치된 줄을 전부 더해 caller 무관 총합을 낸다(이 스크립트는
# scenario 1 단독 관측용이라 caller 별 분리가 필요 없다. 분리 값은 README §4 방식의 grep/diff 로
# 별도로 뜬다 — F5 목표 3 결과 표).
fetch_metric() {
    curl -sf "$PROM_URL" | awk -v name="$1" '$0 ~ "^"name"[ {]" {sum+=$NF; found=1} END {if (!found) print "0"; else print sum}'
}

before_lag_count=$(fetch_metric 'schoolbus_run_confirmation_lag_seconds_count')
before_lag_sum=$(fetch_metric 'schoolbus_run_confirmation_lag_seconds_sum')
before_throttled=$(fetch_metric 'schoolbus_routing_stub_load_throttled_total')
before_timeout=$(fetch_metric 'schoolbus_routing_stub_load_timeout_total')
before_failure_injected=$(fetch_metric 'schoolbus_routing_stub_load_failure_injected_total')

prep_out=$(psql_db -v n="$N" -t -A -F',' < "$SCRIPT_DIR/scenario1_prep.sql" | grep -v '^$' | tail -1)
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
    remaining=$(psql_db -t -A -c \
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
delta_throttled=$(echo "${throttled} - ${before_throttled}" | bc)
delta_timeout=$(echo "${timeout_ct} - ${before_timeout}" | bc)
delta_failure_injected=$(echo "${failure_injected} - ${before_failure_injected}" | bc)
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
  "stub_failure_injected_after": ${failure_injected},
  "stub_throttled_delta": ${delta_throttled},
  "stub_timeout_delta": ${delta_timeout},
  "stub_failure_injected_delta": ${delta_failure_injected}
}
JSON

echo "결과 기록: ${out_file}"
cat "$out_file"
