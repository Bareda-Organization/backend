#!/usr/bin/env bash
# 스레드 덤프는 SIGQUIT(docker kill -s QUIT — JVM 이 덤프를 로그에 찍고 계속 돈다)로 받는다 — 관리 포트에 threaddump 엔드포인트가 열려 있지 않다.
# 사용: DIAG=<라벨> ./capture_storm_diag.sh  (라벨 = prod_sized_round.sh 의 라벨, results/<라벨>_storm_meta.json 이 생기면 그 닫는 시각 기준으로 수집)
# 진단 수집 — 닫는 순간 기준으로 스레드 덤프 3회 · 1초 간격 지표(관리 포트) · 시험 컨테이너 로그
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
D="$DIR/results/diag_${DIAG:?DIAG 라벨 필요}"
mkdir -p "$D"
META="$DIR/results/${DIAG}_storm_meta.json"
until [ -f "$META" ]; do sleep 2; done
CLOSE=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['close_at_ms']/1000)" "$META")
wait_until() { python3 -c "import time,sys;t=float(sys.argv[1])-time.time();time.sleep(max(t,0))" "$1"; }
wait_until $(python3 -c "print($CLOSE-6)")
( for i in $(seq 1 75); do echo "$(date +%s) ESTAB18080=$(netstat -an -p tcp | awk '$4 ~ /\.18080$/ && $6=="ESTABLISHED"' | wc -l | tr -d ' ') $(curl -s -m 2 localhost:18081/actuator/prometheus | grep -E '^(tomcat_connections_current_connections|tomcat_connections_keepalive_current_connections|tomcat_threads_busy_threads|tomcat_threads_current_threads|hikaricp_connections_(active|pending|acquire_seconds_max|timeout_total)|process_cpu_usage|system_cpu_usage|jvm_threads_live_threads|schoolbus_ws_closed_total|schoolbus_stomp_sessions|executor_queued_tasks.*clientInbound|executor_active_threads.*clientInbound|executor_queued_tasks.*clientOutbound|http_server_requests_seconds_count.*status=\"5)' | sed 's/application=\"school-bus\",//' | tr '\n' ' ')"; sleep 1; done ) > "$D"/metrics_1s.log 2>&1 &
wait_until $(python3 -c "print($CLOSE+6)");  docker kill -s QUIT r59-load-app > /dev/null; sleep 3; docker logs --since 6s r59-load-app > "$D"/threaddump_1.txt 2>&1
wait_until $(python3 -c "print($CLOSE+14)"); docker kill -s QUIT r59-load-app > /dev/null; sleep 3; docker logs --since 6s r59-load-app > "$D"/threaddump_2.txt 2>&1
wait_until $(python3 -c "print($CLOSE+24)"); docker kill -s QUIT r59-load-app > /dev/null; sleep 3; docker logs --since 6s r59-load-app > "$D"/threaddump_3.txt 2>&1
wait_until $(python3 -c "print($CLOSE+75)")
docker logs r59-load-app --since "$(date -u -r $(python3 -c "print(int($CLOSE-10))") +%Y-%m-%dT%H:%M:%SZ)" 2>&1 | grep -aE " WARN | ERROR " | cut -c1-300 > "$D"/server_warn_error.log
echo done > "$D"/done
