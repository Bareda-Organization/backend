#!/usr/bin/env bash
# 배포 게이트 — 운행 중(run.status='moving')인 회차가 있으면 배포를 막는다
# (TECH_DECISIONS §14.3, Phase 14 목표 9). deploy.sh 가 이미지 pull 앞에서 부른다.
#
# ⚠️ 이 스크립트는 세고 알리기만 한다. moving 회차를 지우거나 강제로 끝내는 경로를 두지
#    않는다 — 운행 중인 버스의 상태를 배포 스크립트가 대신 판단해서는 안 된다(§14.3).
#
# 결과는 네 갈래다(BR-306).
#   postgres 컨테이너 없음(최초 배포 · 재해 복구)       → 통과 + 이유 한 줄. 세어야 할 운행이 없다
#   컨테이너는 있고 run 테이블 없음(Flyway 전)          → 통과 + 이유 한 줄
#   컨테이너는 있는데 질의가 실패(ps·접속 불가 포함)    → 실패. "없다" 와 "물어보지 못했다" 는 다르다
#   moving 회차가 1건 이상                              → 실패
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

RUN_TABLE_QUERY="SELECT to_regclass('public.run') IS NOT NULL;"
MOVING_QUERY="SELECT count(*) FROM run WHERE status = 'moving';"

if [[ -n "${GATE_PG_URL:-}" ]]; then
    # 로컬 실증 경로 — docker compose 를 거치지 않고 지정한 주소로 직접 붙는다.
    pg_query() { psql "$GATE_PG_URL" -tAc "$1"; }
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

    # 최초 배포 · 재해 복구(새 EC2)에는 postgres 컨테이너가 아직 없다 — 세어야 할 운행이 없으니 막지 않는다.
    # ⚠ 통과는 "ps 가 성공했는데 비었다" 일 때뿐이다. ps 자체가 실패하면(docker 불통) 알 수 없는 상태라 set -e 로 여기서 멈춘다.
    RUNNING_ID="$($COMPOSE ps --status running -q "$PG_SERVICE")"
    if [[ -z "$RUNNING_ID" ]]; then
        echo "배포 게이트 통과 — $PG_SERVICE 컨테이너가 실행 중이 아니다(최초 배포·재해 복구). 세어야 할 운행 중 회차가 없다"
        exit 0
    fi
    pg_query() { $COMPOSE exec -T "$PG_SERVICE" psql -U "$PG_USER" -d "$PG_DB" -tAc "$1"; }
fi

# 컨테이너는 떠 있는데 Flyway 가 아직 안 돈 DB 에는 run 테이블이 없다 — 마찬가지로 막지 않는다.
# 질의가 실패하는 것(접속 불가 등)은 여기서도 set -e 로 멈춘다 — "테이블이 없다" 와 "물어보지 못했다" 는 다르다.
HAS_RUN_TABLE="$(pg_query "$RUN_TABLE_QUERY")"
HAS_RUN_TABLE="$(echo "$HAS_RUN_TABLE" | tr -d '[:space:]')"
if [[ "$HAS_RUN_TABLE" == "f" ]]; then
    echo "배포 게이트 통과 — run 테이블이 아직 없다(마이그레이션 전). 세어야 할 운행 중 회차가 없다"
    exit 0
fi

MOVING_COUNT="$(pg_query "$MOVING_QUERY")"
MOVING_COUNT="$(echo "$MOVING_COUNT" | tr -d '[:space:]')"

if [[ "$MOVING_COUNT" -gt 0 ]]; then
    echo "배포 중단 — 운행 중(moving) 회차 ${MOVING_COUNT}건 존재. 전부 종료될 때까지 배포를 미룬다." >&2
    exit 1
fi

echo "배포 게이트 통과 — 운행 중(moving) 회차 없음"
exit 0
