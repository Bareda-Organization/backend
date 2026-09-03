#!/usr/bin/env bash
# 배포 게이트 — 운행 중(run.status='moving')인 회차가 있으면 배포를 막는다
# (TECH_DECISIONS §14.3, Phase 14 목표 9). deploy.sh 가 이미지 pull 앞에서 부른다.
#
# ⚠️ 이 스크립트는 세고 알리기만 한다. moving 회차를 지우거나 강제로 끝내는 경로를 두지
#    않는다 — 운행 중인 버스의 상태를 배포 스크립트가 대신 판단해서는 안 된다(§14.3).
#
# 사용법
#   운영: infra/scripts/deploy-gate.sh [COMPOSE_FILE] [ENV_FILE]
#         (기본값은 deploy.sh 가 쓰는 경로와 같다: /opt/school-bus/docker-compose.prod.yml, /opt/school-bus/.env)
#         compose 서비스명·DB명·역할은 COMPOSE_FILE 에서 직접 읽는다 — 이 값들을 스크립트에
#         박아두면 compose 파일이 바뀔 때 게이트만 조용히 낡은 이름을 조회하게 된다.
#   로컬 실증: GATE_PG_URL 을 주면 compose 를 거치지 않고 그 주소로 직접 붙는다.
#         예) GATE_PG_URL=postgresql://schoolbus@localhost:15432/sb_p14_t3 infra/scripts/deploy-gate.sh
set -euo pipefail

COMPOSE_FILE="${1:-/opt/school-bus/docker-compose.prod.yml}"
ENV_FILE="${2:-/opt/school-bus/.env}"

MOVING_QUERY="SELECT count(*) FROM run WHERE status = 'moving';"

if [[ -n "${GATE_PG_URL:-}" ]]; then
    # 로컬 실증 경로 — docker compose 를 거치지 않고 지정한 주소로 직접 붙는다.
    MOVING_COUNT="$(psql "$GATE_PG_URL" -tAc "$MOVING_QUERY")"
else
    if [[ ! -f "$COMPOSE_FILE" ]]; then
        echo "오류: compose 파일을 찾을 수 없다 — $COMPOSE_FILE" >&2
        exit 1
    fi

    # 운영 경로 — docker-compose.prod.yml 의 postgres 서비스 블록에서 서비스명·DB명·역할을
    # 직접 읽는다. 서비스 이름 줄은 2칸 들여쓰기(`  postgres:`), 그 안의 환경변수는 더 깊이
    # 들여쓰여 있다는 이 저장소 compose 파일의 형식에 기대어 훑는다(하드코딩 금지).
    read -r PG_SERVICE PG_DB PG_USER < <(awk '
        /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { svc = $1; sub(/:$/, "", svc) }
        /POSTGRES_DB:/   { db = $2 }
        /POSTGRES_USER:/ { user = $2 }
        svc == "postgres" && db && user && !done { print svc, db, user; done = 1 }
    ' "$COMPOSE_FILE")

    if [[ -z "${PG_SERVICE:-}" || -z "${PG_DB:-}" || -z "${PG_USER:-}" ]]; then
        echo "오류: $COMPOSE_FILE 에서 postgres 서비스명·DB명·역할을 읽지 못했다 — 배포를 중단한다." >&2
        exit 1
    fi

    COMPOSE="docker compose -f $COMPOSE_FILE --env-file $ENV_FILE"
    MOVING_COUNT="$($COMPOSE exec -T "$PG_SERVICE" psql -U "$PG_USER" -d "$PG_DB" -tAc "$MOVING_QUERY")"
fi

MOVING_COUNT="$(echo "$MOVING_COUNT" | tr -d '[:space:]')"

if [[ "$MOVING_COUNT" -gt 0 ]]; then
    echo "배포 중단 — 운행 중(moving) 회차 ${MOVING_COUNT}건 존재. 전부 종료될 때까지 배포를 미룬다." >&2
    exit 1
fi

echo "배포 게이트 통과 — 운행 중(moving) 회차 없음"
exit 0
