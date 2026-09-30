#!/bin/bash
# 백엔드 테스트 — **전용 DB 를 자동으로 만들고 끝나면 지운다.**
#
# 왜 (2026-09-20)
#   build.gradle 이 `-PtestDbUrl` 을 필수로 요구한다(좌석 여러 개가 공유 DB 를 밟는 사고를 막는
#   장치). 그래서 지금까지 매 회차 손으로 CREATE DATABASE / DROP DATABASE 를 했고, 실제로
#   sb_r22·sb_r23 을 만들었다 지우기를 반복했다. 잊으면 찌꺼기 DB 가 쌓인다.
#   ⇒ 이름 짓기·만들기·지우기를 이 스크립트가 맡는다. 사용법은 gradlew 와 같다.
#
# 사용
#   backend/scripts/test.sh                                  전체
#   backend/scripts/test.sh --tests '*StaffRunRouteControllerTest*'   일부 (고친 부분만 — 권장)
#   KEEP_DB=1 backend/scripts/test.sh …                      실패를 들여다보려고 DB 를 남긴다
set -euo pipefail
cd "$(dirname "$0")/.."

# docker 는 PATH 에서 찾고, 없을 때만 이 기계의 설치 위치를 시도한다(다른 기계·다른 설치 경로에서도 돈다).
command -v docker >/dev/null 2>&1 || export PATH="/Applications/Code/Docker.app/Contents/Resources/bin:$PATH"
command -v docker >/dev/null 2>&1 || { echo "docker 를 찾을 수 없다 — Docker 를 설치·실행하라"; exit 2; }

# postgres 컨테이너는 이름이 아니라 개발 인프라가 내는 포트(15432)로 찾는다 — 저장소 폴더 이름이 다른 클론·
# 워크트리에서는 compose 프로젝트 이름이 달라 `school-bus-postgres-1` 이 아니다. PG_CONTAINER 로 직접 지정도 된다.
PG_CONTAINER="${PG_CONTAINER:-$(docker ps --filter publish=15432 --format '{{.Names}}' | head -n 1)}"
PG_USER="${PG_USER:-schoolbus}"

[ -n "$PG_CONTAINER" ] && docker exec "$PG_CONTAINER" true 2>/dev/null || {
  echo "postgres 컨테이너(포트 15432)가 없다 — 먼저 인프라를 띄워라:"
  echo "  docker compose up -d postgres redis"
  exit 2
}

DB="sbtest_$(date +%H%M%S)_$$"
psql() { docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d postgres -q "$@"; }

cleanup() {
  if [ "${KEEP_DB:-0}" = "1" ]; then
    echo "전용 DB 를 남긴다: $DB  (다 보고 나면 DROP DATABASE \"$DB\";)"
    return
  fi
  # ⚠ WITH (FORCE) 를 쓰지 않는다 — 남의 연결을 강제로 끊으면 postgres 가 반복 크래시한다.
  #   테스트 JVM 이 이미 끝난 뒤라 연결이 남아 있지 않다.
  psql -c "DROP DATABASE IF EXISTS \"$DB\";" >/dev/null 2>&1 && echo "전용 DB 정리: $DB"
}
trap cleanup EXIT

psql -c "CREATE DATABASE \"$DB\";" >/dev/null
echo "전용 DB: $DB"
./gradlew test -PtestDbUrl="jdbc:postgresql://localhost:15432/$DB" "$@"
