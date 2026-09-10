#!/usr/bin/env bash
# 자원 스냅샷 — 부하 한계 측정 계획 §4 가 "포화 순간에 함께 기록할 것" 으로 지정한 지표를 한 번에 뜬다.
# 이 값이 없으면 포화를 봐도 "증설로 풀리는가" 에 답할 수 없다.
#
# 실행: ./snapshot.sh <라벨>            (results/snapshot_<라벨>.json 에 쓴다)
#      ./snapshot.sh <라벨> --stdout   (파일 대신 표준출력)
set -euo pipefail

LABEL="${1:?라벨 필요}"
PROM_URL="${PROM_URL:-http://localhost:18080/actuator/prometheus}"
PG_CONTAINER="${PG_CONTAINER:-school-bus-postgres-1}"
DB_NAME="${DB_NAME:-schoolbus_load}"
APP_PORT="${APP_PORT:-18080}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "$DIR/results"

PROM="$(curl -sf "$PROM_URL" || true)"

# $1=메트릭 이름, $2=선택적 라벨 필터(부분 문자열). 매치된 줄의 마지막 필드를 전부 더한다 —
# micrometer 가 common tag 를 붙여 이름 뒤에 '{' 가 오는 형태와 스페이스가 오는 형태가 섞인다.
m() {
    echo "$PROM" | awk -v name="$1" -v filt="${2:-}" '
        $0 ~ "^"name"[ {]" { if (filt == "" || index($0, filt) > 0) { sum += $NF; found = 1 } }
        END { if (!found) print "null"; else printf "%.6g", sum }'
}

# JVM RSS(KB→MB). t3.medium(4GB) 환산에 쓰는 값이라 힙만이 아니라 프로세스 전체를 본다.
APP_PID="$(pgrep -f 'BackendApplication' | head -1 || true)"
RSS_MB="null"
if [ -n "$APP_PID" ]; then
    RSS_MB="$(ps -o rss= -p "$APP_PID" | awk '{printf "%.1f", $1/1024}')"
fi

# 앱 포트로 맺어진 TCP 연결 수 — WS 세션 수의 직접 관측값(앱에 세션 게이지가 없다).
ESTABLISHED="$(lsof -nP -iTCP:"$APP_PORT" -sTCP:ESTABLISHED 2>/dev/null | grep -c java || true)"

PG_TOTAL="$(docker exec "$PG_CONTAINER" psql -U schoolbus -d "$DB_NAME" -t -A -c \
    "SELECT count(*) FROM pg_stat_activity WHERE datname='$DB_NAME'" 2>/dev/null || echo null)"
PG_ACTIVE="$(docker exec "$PG_CONTAINER" psql -U schoolbus -d "$DB_NAME" -t -A -c \
    "SELECT count(*) FROM pg_stat_activity WHERE datname='$DB_NAME' AND state='active'" 2>/dev/null || echo null)"
PG_WAITING="$(docker exec "$PG_CONTAINER" psql -U schoolbus -d "$DB_NAME" -t -A -c \
    "SELECT count(*) FROM pg_stat_activity WHERE datname='$DB_NAME' AND wait_event_type='Lock'" 2>/dev/null || echo null)"

JSON=$(cat <<JSONEOF
{
  "label": "${LABEL}",
  "at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "host_rss_mb": ${RSS_MB},
  "established_conns_to_app": ${ESTABLISHED},
  "cpu": {
    "process_cpu_usage": $(m process_cpu_usage),
    "system_cpu_usage": $(m system_cpu_usage),
    "system_load_average_1m": $(m system_load_average_1m)
  },
  "jvm": {
    "heap_used_bytes": $(m jvm_memory_used_bytes 'area="heap"'),
    "heap_committed_bytes": $(m jvm_memory_committed_bytes 'area="heap"'),
    "nonheap_used_bytes": $(m jvm_memory_used_bytes 'area="nonheap"'),
    "live_threads": $(m jvm_threads_live_threads),
    "peak_threads": $(m jvm_threads_peak_threads)
  },
  "hikari": {
    "active": $(m hikaricp_connections_active),
    "idle": $(m hikaricp_connections_idle),
    "pending": $(m hikaricp_connections_pending),
    "max": $(m hikaricp_connections_max),
    "timeout_total": $(m hikaricp_connections_timeout_total),
    "acquire_seconds_max": $(m hikaricp_connections_acquire_seconds_max),
    "usage_seconds_max": $(m hikaricp_connections_usage_seconds_max)
  },
  "tomcat": {
    "threads_busy": $(m tomcat_threads_busy_threads),
    "threads_current": $(m tomcat_threads_current_threads),
    "threads_config_max": $(m tomcat_threads_config_max_threads),
    "connections_current": $(m tomcat_connections_current_connections),
    "connections_config_max": $(m tomcat_connections_config_max_connections)
  },
  "stomp_executors": {
    "inbound_active": $(m executor_active_threads 'clientInboundChannelExecutor'),
    "inbound_queued": $(m executor_queued_tasks 'clientInboundChannelExecutor'),
    "inbound_pool": $(m executor_pool_size_threads 'clientInboundChannelExecutor'),
    "outbound_active": $(m executor_active_threads 'clientOutboundChannelExecutor'),
    "outbound_queued": $(m executor_queued_tasks 'clientOutboundChannelExecutor'),
    "outbound_pool": $(m executor_pool_size_threads 'clientOutboundChannelExecutor'),
    "outbound_completed_total": $(m executor_completed_tasks_total 'clientOutboundChannelExecutor')
  },
  "app": {
    "run_unconfirmed": $(m schoolbus_run_unconfirmed),
    "confirmation_lag_count": $(m schoolbus_run_confirmation_lag_seconds_count),
    "confirmation_lag_sum": $(m schoolbus_run_confirmation_lag_seconds_sum),
    "confirmation_lag_max": $(m schoolbus_run_confirmation_lag_seconds_max),
    "stub_throttled_total": $(m schoolbus_routing_stub_load_throttled_total),
    "stub_timeout_total": $(m schoolbus_routing_stub_load_timeout_total),
    "stub_failure_injected_total": $(m schoolbus_routing_stub_load_failure_injected_total),
    "scheduler_failures_total": $(m schoolbus_scheduler_failures_total)
  },
  "postgres": { "backends_total": ${PG_TOTAL:-null}, "backends_active": ${PG_ACTIVE:-null}, "lock_waiting": ${PG_WAITING:-null} }
}
JSONEOF
)

if [ "${2:-}" = "--stdout" ]; then
    echo "$JSON"
else
    echo "$JSON" > "$DIR/results/snapshot_${LABEL}.json"
    echo "$DIR/results/snapshot_${LABEL}.json"
fi
