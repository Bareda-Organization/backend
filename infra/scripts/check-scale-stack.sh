#!/usr/bin/env bash
# 2대 구성 정의 확인(I-2 · I-5) — compose 합성 · 백엔드 2대 건강 · nginx 설정 검사 · Prometheus 가 두 대를 모두 수집하는지.
#
#   infra/scripts/check-scale-stack.sh               # 스택을 띄우고 확인한 뒤 내린다(프로젝트 r59-infra · down 만 · -v 없음)
#
# 종료 코드: 0 = 전부 통과 · 1 = 하나라도 실패. 환경변수는 scale-stack.sh 머리말.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/scale-stack.sh"
trap scale_down EXIT
fail=0
ok()  { echo "PASS  $*"; }
bad() { echo "FAIL  $*"; fail=1; }

ENV_FILE="$SCALE_REPO/infra/scale-check/placeholder.env"
cd "$SCALE_REPO" || exit 1
docker compose -f docker-compose.prod.yml --env-file "$ENV_FILE" config >/dev/null 2>&1 \
    && ok "compose config (백엔드 1대: prod.yml)" || bad "compose config (백엔드 1대)"
docker compose -f docker-compose.prod.yml -f docker-compose.scale.yml --env-file "$ENV_FILE" config >/dev/null 2>&1 \
    && ok "compose config (백엔드 2대: prod.yml + scale.yml)" || bad "compose config (백엔드 2대)"

scale_up || { bad "스택 기동(--wait 300s)"; exit 1; }
for svc in backend backend2; do
    status="$(scale_dc ps --format '{{.Status}}' "$svc")"
    [[ "$status" == *"(healthy)"* ]] && ok "$svc healthy — $status" || bad "$svc 가 healthy 가 아니다 — $status"
done
nginx_out="$(scale_dc exec -T proxy nginx -t 2>&1)"
[[ "$nginx_out" == *"test is successful"* ]] && ok "nginx -t (proxy 컨테이너)" || bad "nginx -t — $nginx_out"
grep -q "server backend2:8080" "$SCALE_REPO/infra/proxy/backend-pool.scale.conf" \
    && scale_dc exec -T proxy nginx -T 2>/dev/null | grep -q "server backend2:8080" \
    && ok "nginx 가 읽은 업스트림에 backend2 가 있다" || bad "nginx 업스트림에 backend2 가 없다"

# Prometheus 는 15초마다 긁는다 — 두 대가 up 이 될 때까지 최대 60초 기다린다.
up_instances=""
for _ in $(seq 1 30); do
    up_instances="$(curl -fsS "$SCALE_PROM/api/v1/targets" 2>/dev/null \
        | jq -r '[.data.activeTargets[] | select(.labels.job=="backend" and .health=="up") | .labels.instance] | sort | join(",")')"
    [[ "$up_instances" == "backend2:8081,backend:8081" || "$up_instances" == "backend:8081,backend2:8081" ]] && break
    sleep 2
done
[[ -n "$up_instances" && "$(tr ',' '\n' <<<"$up_instances" | wc -l | tr -d ' ')" -eq 2 ]] \
    && ok "Prometheus job=backend 수집 대상 2개 up — $up_instances" || bad "Prometheus job=backend up 대상 — '$up_instances'"
exit $fail
