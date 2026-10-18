#!/usr/bin/env bash
# 컨테이너 안에서 1초마다 cgroup 값(메모리 현재·최고, CPU 사용·제한 대기 마이크로초)을 CSV 로 쌓는다 — 호스트에서 docker exec 를 매초 부르면
# 고부하에서 Docker Desktop 이 멈춘 적이 있어(LOAD_TESTING §6.4) 컨테이너 안 반복문 하나로 대신한다. 파일은 끝난 뒤 한 번에 꺼낸다.
#
# 실행: ./cgroup_sampler.sh start <라벨> <초>   → 컨테이너 안에서 백그라운드로 시작
#       ./cgroup_sampler.sh collect <라벨>      → results/cg_<라벨>.csv 로 꺼낸다
#       ./cgroup_sampler.sh cpu-all              → 앱 · Postgres · Redis 컨테이너의 누적 CPU 마이크로초를 한 줄로 (r3_mixed.sh 가 측정 구간 시작·끝에 부른다)
#       ./cgroup_sampler.sh cpu <컨테이너>      → 그 컨테이너 cgroup 의 누적 CPU 마이크로초(usage_usec) 한 줄 — 회차 전후 차이로 CPU 초를 낸다
set -euo pipefail
APP="${APP_NAME:-r59-load-app}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
case "${1:?start|collect|cpu}" in
    start)
        LABEL="${2:?라벨}"; SECS="${3:?초}"
        docker exec -d "$APP" sh -c "f=/tmp/cg_${LABEL}.csv; echo t,mem_current,mem_peak,cpu_usec,throttled_usec,epoch > \$f; i=0; \
while [ \$i -lt $SECS ]; do echo \"\$i,\$(cat /sys/fs/cgroup/memory.current),\$(cat /sys/fs/cgroup/memory.peak),\$(awk '/^usage_usec/{u=\$2}/^throttled_usec/{t=\$2}END{print u\",\"t}' /sys/fs/cgroup/cpu.stat),\$(date +%s)\" >> \$f; i=\$((i+1)); sleep 1; done"
        ;;
    collect)
        LABEL="${2:?라벨}"
        docker exec "$APP" cat "/tmp/cg_${LABEL}.csv" > "$DIR/results/cg_${LABEL}.csv"
        echo "$DIR/results/cg_${LABEL}.csv"
        ;;
    cpu-all)
        echo "$("$0" cpu "$APP") $("$0" cpu school-bus-postgres-1) $("$0" cpu school-bus-redis-1)"
        ;;
    cpu)
        docker exec "${2:?컨테이너}" sh -c "awk '/^usage_usec/{print \$2}' /sys/fs/cgroup/cpu.stat"
        ;;
esac
