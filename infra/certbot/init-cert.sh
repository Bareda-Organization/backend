#!/usr/bin/env bash
# Let's Encrypt 인증서 최초 발급 (EC2 에서 1회만 실행).
#
# 왜 standalone 인가:
#   nginx 는 인증서가 없으면 기동을 못 하고(ssl_certificate 파일 부재),
#   certbot 의 webroot 방식은 80 포트에서 응답하는 웹서버를 필요로 한다 — 순환이다.
#   최초 1회는 certbot 이 자체 웹서버를 띄워(standalone) 끊고, 이후 갱신은
#   docker-compose.prod.yml 의 certbot 컨테이너가 webroot 방식으로 처리한다.
#
# 사용법:
#   ./init-cert.sh api.내도메인.com 내메일@example.com
set -euo pipefail

DOMAIN="${1:?사용법: init-cert.sh <api 도메인> <이메일>}"
EMAIL="${2:?사용법: init-cert.sh <api 도메인> <이메일>}"

# compose 프로젝트명은 디렉터리명(/opt/school-bus → school-bus)에서 나온다.
# 그래서 볼륨 실제 이름은 school-bus_certbot-conf 다. 다른 경로에 배치했다면 여기를 맞춘다.
CONF_VOLUME="${CONF_VOLUME:-school-bus_certbot-conf}"
WWW_VOLUME="${WWW_VOLUME:-school-bus_certbot-www}"

docker volume create "$CONF_VOLUME" >/dev/null
docker volume create "$WWW_VOLUME"  >/dev/null

# 80 포트를 잠깐 비워야 한다 — 이미 proxy 가 떠 있으면 발급이 실패한다.
if docker ps --format '{{.Names}}' | grep -q 'proxy'; then
    echo "proxy 컨테이너가 80 포트를 쓰고 있다. 먼저 내린다."
    docker compose -f /opt/school-bus/docker-compose.prod.yml stop proxy || true
fi

docker run --rm -p 80:80 \
    -v "$CONF_VOLUME:/etc/letsencrypt" \
    -v "$WWW_VOLUME:/var/www/certbot" \
    certbot/certbot certonly --standalone \
    -d "$DOMAIN" \
    --email "$EMAIL" \
    --agree-tos --no-eff-email --non-interactive

echo
echo "발급 완료: /etc/letsencrypt/live/$DOMAIN/"
echo "다음 단계:"
echo "  1) infra/proxy/nginx.prod.conf 의 api.example.com 을 $DOMAIN 으로 바꾼다"
echo "  2) docker compose -f /opt/school-bus/docker-compose.prod.yml up -d"
