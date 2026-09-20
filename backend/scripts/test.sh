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

export PATH="/Applications/Code/Docker.app/Contents/Resources/bin:$PATH"
PG_CONTAINER="${PG_CONTAINER:-school-bus-postgres-1}"
PG_USER="${PG_USER:-schoolbus}"

docker exec "$PG_CONTAINER" true 2>/dev/null || {
  echo "postgres 컨테이너($PG_CONTAINER)가 없다 — 먼저 인프라를 띄워라:"
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
