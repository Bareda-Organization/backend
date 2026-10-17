#!/usr/bin/env bash
# 백엔드를 한 대씩 다시 만든다 — 한 대가 healthy 가 된 것을 보고 다음 대로 넘어가므로 항상 한 대는 요청을 받는다. nginx(proxy)는 건드리지 않는다.
#
#   COMPOSE="docker compose -f … --env-file …" infra/scripts/rolling-restart.sh backend backend2
#
# deploy.sh 가 BACKEND_INSTANCES=2 일 때 부르고, check-rolling-restart.sh 가 로컬 스택에서 같은 스크립트를 부른다.
#
# 규칙
#  1. 대상 서비스마다 `up -d --no-deps --force-recreate` — 새 이미지(IMAGE_TAG)를 받은 컨테이너로 바꾼다. 종료 신호를 받은 옛 컨테이너는 stop_grace_period(35초) 안에 끝난다.
#  2. 다시 만들기 전에 다른 백엔드가 있으면 그 대가 healthy 인지 본다 — 아니면 멈춘다(마지막 건강한 대를 내리지 않는다). 다른 백엔드가 하나도 안 떠 있으면(첫 배포) 막지 않는다.
#  3. 다시 만든 대가 healthy 가 되면 ROLLING_SETTLE_SEC(기본 8초) 쉰다 — nginx 가 컨테이너 이름을 다시 풀어(resolver valid=5s) 새 IP 를 잡을 시간이다. 그 전에 다음 대를 내리면 두 대가 다 풀 밖이다.
# 출력 중 `ROLL <서비스> <단계> <epoch_ms>` 줄은 확인 스크립트가 시각을 읽는 표지다.
# 종료 코드: 0 = 전부 완료 · 1 = 건강 확인 실패 또는 다른 대가 건강하지 않아 멈춤.
set -euo pipefail

: "${COMPOSE:?COMPOSE 미설정 — 예: COMPOSE=\"docker compose -f docker-compose.prod.yml -f docker-compose.scale.yml --env-file /opt/school-bus/.env\"}"
[[ $# -ge 1 ]] || { echo "사용법: rolling-restart.sh <서비스>..." >&2; exit 1; }
SERVICES=("$@")
HEALTH_TIMEOUT_SEC="${ROLLING_HEALTH_TIMEOUT_SEC:-240}"
SETTLE_SEC="${ROLLING_SETTLE_SEC:-8}"

now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }
mark() { echo "ROLL $1 $2 $(now_ms)"; }
# 서비스의 건강 상태 — 컨테이너가 없으면 빈 문자열, 있으면 healthy | unhealthy | starting
health_of() {
    local cid
    cid="$($COMPOSE ps -q "$1" 2>/dev/null | head -n1)"
    [[ -n "$cid" ]] || return 0
    docker inspect -f '{{if .State.Running}}{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}{{else}}stopped{{end}}' "$cid"
}
wait_healthy() { # <서비스> — healthy 가 될 때까지 최대 HEALTH_TIMEOUT_SEC
    local deadline=$(( SECONDS + HEALTH_TIMEOUT_SEC ))
    while (( SECONDS < deadline )); do
        [[ "$(health_of "$1")" == healthy ]] && return 0
        sleep 2
    done
    return 1
}

for svc in "${SERVICES[@]}"; do
    for other in "${SERVICES[@]}"; do
        [[ "$other" == "$svc" ]] && continue
        state="$(health_of "$other")"
        # 아직 안 뜬 대(빈 값 · stopped)는 지킬 것이 없다. 떠 있는데 healthy 가 아니면 잠시 기다려 본다.
        if [[ -n "$state" && "$state" != stopped && "$state" != healthy ]]; then
            wait_healthy "$other" || { echo "중단: $other 가 healthy 가 아니다($(health_of "$other")) — $svc 를 내리면 요청을 받을 대가 없다" >&2; exit 1; }
        fi
    done
    mark "$svc" restart
    $COMPOSE up -d --no-deps --force-recreate "$svc"
    wait_healthy "$svc" || { echo "중단: $svc 가 ${HEALTH_TIMEOUT_SEC}초 안에 healthy 가 되지 않았다" >&2; $COMPOSE logs --tail 60 "$svc" >&2 || true; exit 1; }
    mark "$svc" healthy
    sleep "$SETTLE_SEC"
    mark "$svc" settled
done
echo "롤링 재시작 완료: ${SERVICES[*]}"
