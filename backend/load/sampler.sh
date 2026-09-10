#!/usr/bin/env bash
# 1초 간격 자원 표본기 — snapshot.sh 의 점 하나로는 최대치를 놓친다.
# 위치 송신이 5초 주기로 몰려 들어오는 형태라(모든 VU 가 같은 순간에 쏜다) 40초 지점 한 번만 뜨면
# 골짜기를 잴 확률이 높다. 회차 내내 1초마다 떠서 최대·중간값을 낼 수 있게 CSV 로 쌓는다.
#
# 실행: ./sampler.sh <라벨> <지속초>   → results/sample_<라벨>.csv
set -euo pipefail
LABEL="${1:?라벨 필요}"
SECONDS_TOTAL="${2:?지속초 필요}"
PROM_URL="${PROM_URL:-http://localhost:18080/actuator/prometheus}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$DIR/results/sample_${LABEL}.csv"
APP_PID="$(pgrep -f 'BackendApplication' | head -1 || true)"

echo "t,cpu_process,cpu_system,heap_mb,rss_mb,live_threads,tomcat_busy,tomcat_conns,hikari_active,hikari_pending,hikari_acquire_max_s,out_queued,out_active,established" > "$OUT"
for t in $(seq 0 $((SECONDS_TOTAL - 1))); do
    P="$(curl -sf --max-time 2 "$PROM_URL" || true)"
    v() { echo "$P" | awk -v n="$1" -v f="${2:-}" '$0 ~ "^"n"[ {]" { if (f=="" || index($0,f)>0) { s+=$NF; k=1 } } END { if (!k) print ""; else printf "%.6g", s }'; }
    RSS=""; [ -n "$APP_PID" ] && RSS="$(ps -o rss= -p "$APP_PID" 2>/dev/null | awk '{printf "%.0f", $1/1024}')"
    # 연결 수는 tomcat_connections_current_connections 로 대신한다 — lsof 는 연결이 수백 개를 넘으면
    # 한 번에 1초 넘게 걸려 표본기 자체가 느려지고, 그러면 버스트를 놓쳐 최대값이 낮게 잡힌다
    # (2026-09-09 N=800 회차에서 cpu 최대가 0.13 으로 나온 원인).
    EST="$(v tomcat_connections_current_connections)"
    printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
        "$t" "$(v process_cpu_usage)" "$(v system_cpu_usage)" \
        "$(echo "$(v jvm_memory_used_bytes 'area=\"heap\"')" | awk '{printf "%.0f", $1/1048576}')" \
        "$RSS" "$(v jvm_threads_live_threads)" "$(v tomcat_threads_busy_threads)" \
        "$(v tomcat_connections_current_connections)" "$(v hikaricp_connections_active)" \
        "$(v hikaricp_connections_pending)" "$(v hikaricp_connections_acquire_seconds_max)" \
        "$(v executor_queued_tasks 'clientOutboundChannelExecutor')" \
        "$(v executor_active_threads 'clientOutboundChannelExecutor')" "$EST" >> "$OUT"
    sleep 1
done
echo "$OUT"
