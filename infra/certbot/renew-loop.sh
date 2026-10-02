#!/bin/sh
# certbot 컨테이너의 갱신 루프(docker-compose.prod.yml 의 certbot 서비스가 entrypoint 로 돈다) — 12시간마다 `certbot renew` 를 돌리고
# **오류 없이 끝났을 때만** 성공 시각 지표를 쓴다. 인증서는 만료 30일 전부터 갱신을 시도하고, 갱신할 때가 아니어서 아무것도 안 한 것도 성공(종료 코드 0)이다.
#
# 왜 지표인가 — 갱신이 막혀도(80 포트 규칙 · certbot-www 볼륨 · nginx 챌린지 location 변경) 컨테이너는 계속 돌고 결과는 CloudWatch 로그에만 남는다.
# 아무도 로그를 안 보면 90일 인증서가 만료되는 날에야 모든 클라이언트(앱 2종 · 웹)의 HTTPS 가 한꺼번에 거절된다(BR-334).
# 지표는 backup-db.sh 와 같은 방식이다 — node-exporter textfile 수집기가 읽고, 경보 CertbotRenewStale(alerts.yml)이 2일 넘게 성공하지 않으면 울린다.
#
# RENEW_ONCE 를 주면 한 번만 돌고 끝난다(시험용 — CertbotRenewLoopGuardTest).
TEXTFILE_DIR="${TEXTFILE_DIR:-/textfile}"
RENEW_INTERVAL="${RENEW_INTERVAL:-12h}"
METRIC=schoolbus_certbot_renew_last_success_timestamp_seconds

record_success() {
    # textfile 수집기는 *.prom 만 읽는다 — 임시 파일에 쓴 뒤 옮겨 반쯤 쓴 파일을 읽히지 않는다.
    file="$TEXTFILE_DIR/schoolbus_certbot.prom"
    mkdir -p "$TEXTFILE_DIR"
    printf '# HELP %s 마지막 인증서 갱신 시도가 오류 없이 끝난 시각(epoch 초)\n# TYPE %s gauge\n%s %s\n' \
        "$METRIC" "$METRIC" "$METRIC" "$(date +%s)" > "$file.tmp"
    mv "$file.tmp" "$file"
}

trap exit TERM
while :; do
    if certbot renew --webroot -w /var/www/certbot --quiet; then
        record_success
    else
        echo "인증서 갱신 실패 — 성공 시각 지표를 갱신하지 않는다(CertbotRenewStale 이 2일 뒤 알린다)" >&2
    fi
    [ -n "${RENEW_ONCE:-}" ] && exit 0
    sleep "$RENEW_INTERVAL" &
    wait $!
done
