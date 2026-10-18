#!/usr/bin/env bash
# 호스트 잡음 검사(prod_sized_round.sh 가 종료 코드 3 으로 거절)를 통과할 때까지 1분마다 같은 회차를 다시 시도한다 — 한도는 낮추지 않는다.
# 사용: (환경변수 설정 후) ./retry_round.sh <prod_sized_round.sh 인자 …>   — 라벨은 첫 인자
cd "$(dirname "${BASH_SOURCE[0]}")"
for i in $(seq 1 120); do
    ./prod_sized_round.sh "$@" > "results/${1}_wrapper.log" 2>&1
    rc=$?
    if [ $rc -ne 3 ]; then echo "attempt $i finished rc=$rc at $(date +%H:%M:%S)"; exit $rc; fi
    echo "attempt $i noise-blocked at $(date +%H:%M:%S): $(head -1 "results/${1}_host_noise.log")"
    sleep 60
done
exit 3
