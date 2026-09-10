#!/usr/bin/env bash
# 인스턴스 크기를 흉내 내어 앱을 띄운다 — "목표 규모에 어떤 서버가 필요한가" 를 재기 위한 것.
#
# ⚠ `-XX:ActiveProcessorCount` 는 **JVM 이 자기가 몇 코어짜리 기계에 있다고 믿는지**를 바꾼다 —
# GC 스레드 수 · ForkJoinPool · Spring 기본 실행기 크기가 그 값으로 정해진다. 하지만 macOS 에는
# cgroup 이 없어 **실제 CPU 시간을 강제로 자르지는 못한다.** 그래서 이 방식은 "작은 기계의 런타임
# 구성" 은 재현하되 "작은 기계의 CPU 상한" 은 재현하지 않는다. 상한 쪽 판정은 측정된 CPU 초
# (process_cpu_time_ns_total 누적 차)를 인스턴스 vCPU 수와 직접 비교해서 한다.
#
# 실행: ./start_sized.sh <코어수> <힙> <아웃바운드스레드> [로그파일]
set -euo pipefail
CORES="${1:?코어 수}"
HEAP="${2:?힙 크기 예: 1g}"
OUTBOUND="${3:?아웃바운드 스레드 수}"
LOG="${4:-/tmp/sized_${CORES}c_${HEAP}.log}"
JAR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/build/libs/backend-0.0.1-SNAPSHOT.jar"

nohup caffeinate -i java \
    -XX:ActiveProcessorCount="$CORES" -Xmx"$HEAP" -Xms"$HEAP" \
    -jar "$JAR" \
    --spring.profiles.active=load \
    --app.routing.map.stub.load.min-delay-ms=300 \
    --app.routing.map.stub.load.max-delay-ms=1200 \
    --app.routing.map.stub.load.max-concurrent=4 \
    --app.ws.outbound.core-pool-size="$OUTBOUND" \
    > "$LOG" 2>&1 &

for i in $(seq 1 40); do
    if curl -sf -m 3 http://localhost:18080/actuator/health > /dev/null 2>&1; then
        echo "기동 완료 — 코어 ${CORES} · 힙 ${HEAP} · 아웃바운드 ${OUTBOUND} (로그 ${LOG})"
        curl -s http://localhost:18080/actuator/prometheus | grep -E '^jvm_memory_max_bytes.*heap|^executor_pool_max_threads.*clientOutbound|^tomcat_threads_config_max'
        exit 0
    fi
    sleep 3
done
echo "기동 실패 — ${LOG} 확인" >&2
tail -20 "$LOG" >&2
exit 1
