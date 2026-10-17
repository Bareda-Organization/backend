#!/usr/bin/env bash
# 2대 구성 로컬 확인 스택 공통 함수 — check-cross-instance.sh · check-instance-failover.sh · check-rolling-restart.sh 가 source 한다.
#
# 스택 = docker-compose.prod.yml + docker-compose.scale.yml + docker-compose.scale-local.yml, 프로젝트 이름 r59-infra.
# postgres · redis · backend · backend2 · proxy(nginx) · prometheus 만 띄운다(certbot · node-exporter · grafana · alertmanager 는 확인에 필요 없다).
# 정리는 `down` 만 한다 — `-v` 는 쓰지 않는다(스택이 볼륨을 만들지 않는다).
#
# 환경변수: SCALE_PROJECT(기본 r59-infra) · SCALE_CHECK_MEM(백엔드 한 대 메모리, 기본 1536m) · SCALE_SKIP_BUILD=1(이미지를 다시 빌드하지 않는다) · SCALE_KEEP_UP=1(끝나도 내리지 않는다)

SCALE_REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCALE_PROJECT="${SCALE_PROJECT:-r59-infra}"
SCALE_IMAGE="school-bus-backend:r59-scale"
SCALE_SERVICES=(postgres redis backend backend2 proxy prometheus)
# 직접 접속 포트(백엔드 한 대씩) · nginx · prometheus
SCALE_BE1=http://localhost:28081
SCALE_BE2=http://localhost:28082
SCALE_NGINX_HTTPS=https://localhost:28443
SCALE_PROM=http://localhost:29090

scale_dc() {
    docker compose -p "$SCALE_PROJECT" \
        -f "$SCALE_REPO/docker-compose.prod.yml" -f "$SCALE_REPO/docker-compose.scale.yml" -f "$SCALE_REPO/docker-compose.scale-local.yml" \
        --env-file "$SCALE_REPO/infra/scale-check/placeholder.env" "$@"
}

# 자체 서명 인증서 · 빈 계정 파일 — nginx.prod.conf 가 443 과 .htpasswd 를 요구한다. 확인 때마다 새로 만든다.
scale_make_certs() {
    SCALE_CHECK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/r59-scale-check.XXXXXX")"
    export SCALE_CHECK_DIR
    mkdir -p "$SCALE_CHECK_DIR/letsencrypt/live/api.example.com" "$SCALE_CHECK_DIR/www"
    : > "$SCALE_CHECK_DIR/htpasswd"
    openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=api.example.com" \
        -keyout "$SCALE_CHECK_DIR/letsencrypt/live/api.example.com/privkey.pem" \
        -out "$SCALE_CHECK_DIR/letsencrypt/live/api.example.com/fullchain.pem" 2>/dev/null
}

scale_build() {
    if [[ "${SCALE_SKIP_BUILD:-}" == 1 ]] && docker image inspect "$SCALE_IMAGE" >/dev/null 2>&1; then
        echo "== 이미지 빌드 생략 ($SCALE_IMAGE 있음) =="
        return 0
    fi
    echo "== 백엔드 이미지 빌드 ($SCALE_IMAGE) =="
    docker build -q -t "$SCALE_IMAGE" "$SCALE_REPO/backend" >/dev/null
}

# 스택을 띄우고 건강해질 때까지 기다린다. 실패하면 비영 종료.
scale_up() {
    scale_make_certs
    scale_build
    echo "== 스택 기동 (project=$SCALE_PROJECT, 백엔드 메모리 ${SCALE_CHECK_MEM:-1536m}) =="
    scale_dc up -d --wait --wait-timeout 300 "${SCALE_SERVICES[@]}"
}

scale_down() {
    if [[ "${SCALE_KEEP_UP:-}" == 1 ]]; then
        echo "== SCALE_KEEP_UP=1 — 스택을 내리지 않는다 (내리기: docker compose -p $SCALE_PROJECT … down) =="
        return 0
    fi
    echo "== 스택 내림 (down, -v 없음) =="
    scale_dc down --remove-orphans >/dev/null 2>&1 || true
    [[ -n "${SCALE_CHECK_DIR:-}" ]] && rm -rf "$SCALE_CHECK_DIR"
    return 0
}

# 한 대의 관리 포트 지표 한 줄 값 — scale_metric <서비스> <지표 이름 정규식> (컨테이너 안에서 읽으므로 호스트 포트가 필요 없다)
scale_metric() {
    scale_dc exec -T "$1" curl -fsS http://localhost:8081/actuator/prometheus 2>/dev/null \
        | awk -v pat="$2" '$0 ~ "^" pat { s += $NF } END { printf "%d\n", s }'
}

# 컨테이너 시작 시각(StartedAt) — 재시작 여부를 비교하는 데 쓴다.
scale_started_at() {
    docker inspect -f '{{.State.StartedAt}}' "$(scale_dc ps -q "$1")"
}
