#!/usr/bin/env bash
# 운영 크기(2 vCPU · 3GB 컨테이너)로 앱 한 대를 띄운다 — Postgres·Redis 와 같은 Docker 네트워크, 호스트 포트 18080.
#
# 운영 compose(docker-compose.prod.yml)와 같게 맞춘 것: cpus 2 · mem_limit 3072m · JAVA_TOOL_OPTIONS(그 파일에서 그대로 읽는다 — 값을 여기 복사하지 않는다)
# · Hikari 풀 20 / 연결 대기 3초 / DB 세션 시간 상한 · 톰캣 스레드 100 / accept-count 100 / 동시 연결 4000 · STOMP 송신 실행기 32.
# application-load.yml 이 운영과 달리 두는 값(풀 10 · 연결 대기 30초 · 톰캣 기본)을 인자로 덮는다 — 인자로 덮은 값은 이 파일에만 보인다.
# Flyway 의 init-sqls(마이그레이션 연결의 시간 상한)는 덮지 않는다 — 인자로 주면 항목이 세미콜론 없이 이어져 SQL 문법 오류가 나고, 정상 상태의 부하와 무관하다.
# 관리 포트는 운영처럼 8081 로 가르지 않고 앱 포트(18080)에 둔다 — 기존 회차 스크립트가 localhost:18080/actuator/prometheus 를 읽는다.
#
# 실행: REDIS_DB=13 ./start_prod_sized.sh   (이미 같은 이름의 컨테이너가 있으면 지우고 다시 만든다)
set -euo pipefail
NAME="${APP_NAME:-r59-load-app}"
IMAGE="${APP_IMAGE:-school-bus-backend:r59-load}"
REDIS_DB="${REDIS_DB:?Redis DB 번호 필요 — 0 은 쓰지 않는다}"
[ "$REDIS_DB" != "0" ] || { echo "Redis DB 0 은 쓰지 않는다" >&2; exit 1; }
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_PROD="$DIR/../../docker-compose.prod.yml"
JAVA_OPTS="$(sed -n 's/^ *JAVA_TOOL_OPTIONS: *"\(.*\)"$/\1/p' "$COMPOSE_PROD" | head -1)"
[ -n "$JAVA_OPTS" ] || { echo "docker-compose.prod.yml 에서 JAVA_TOOL_OPTIONS 를 못 읽었다" >&2; exit 1; }
echo "JAVA_TOOL_OPTIONS=$JAVA_OPTS"

docker rm -f "$NAME" > /dev/null 2>&1 || true
docker run -d --name "$NAME" --network school-bus_default --cpus 2 --memory 3072m -p 18080:18080 \
    -e JAVA_TOOL_OPTIONS="$JAVA_OPTS" \
    "$IMAGE" \
    --spring.profiles.active=load \
    --spring.devtools.restart.enabled=false \
    --server.port=18080 \
    --spring.datasource.url=jdbc:postgresql://school-bus-postgres-1:5432/schoolbus_load \
    --spring.data.redis.host=school-bus-redis-1 \
    --spring.data.redis.port=6379 \
    --spring.data.redis.database="$REDIS_DB" \
    --spring.datasource.hikari.maximum-pool-size=20 \
    --spring.datasource.hikari.connection-timeout=3000 \
    "--spring.datasource.hikari.data-source-properties.options=-c lock_timeout=5000 -c idle_in_transaction_session_timeout=30000 -c statement_timeout=30000" \
    --server.tomcat.threads.max=100 \
    --server.tomcat.accept-count=100 \
    --server.tomcat.max-connections=4000 \
    --app.ws.outbound.core-pool-size=32 \
    --app.routing.map.stub.load.min-delay-ms=300 \
    --app.routing.map.stub.load.max-delay-ms=1200 \
    --app.routing.map.stub.load.max-concurrent=4 > /dev/null

for _ in $(seq 1 60); do
    if curl -sf -m 3 http://localhost:18080/actuator/health > /dev/null 2>&1; then
        echo "기동 완료 — $NAME (Redis DB $REDIS_DB)"
        curl -s http://localhost:18080/actuator/prometheus | grep -E '^hikaricp_connections_max|^tomcat_threads_config_max|^jvm_memory_max_bytes\{area="heap"|^executor_pool_core_threads.*clientOutbound' || true
        docker inspect --format 'cpus={{.HostConfig.NanoCpus}} memory={{.HostConfig.Memory}} oomkilled={{.State.OOMKilled}}' "$NAME"
        exit 0
    fi
    sleep 3
done
echo "기동 실패 — docker logs $NAME" >&2
docker logs --tail 30 "$NAME" >&2
exit 1
