#!/usr/bin/env bash
# Alertmanager 설정 파일을 만든다 — deploy.sh 가 매 배포마다 SSM 값으로 부른다(R46 ops2 · Ruling 480 ④ · 483).
#
# 사용법:  ALERT_...=값 render-alertmanager.sh <출력파일>
#
# 왜 파일을 만드나 — Alertmanager 설정은 환경변수를 읽지 못한다. 그런데 봇 토큰·SMTP 비밀번호는 저장소(공개)에 못 둔다.
# 그래서 값은 SSM 선택 항목으로 두고, 있는 채널만 수신자에 넣어 파일을 새로 쓴다. **값이 없으면 수신자 없이 뜬다**(기동은 된다 —
# 알림이 어디로도 가지 않을 뿐이다. 그 상태는 Prometheus /alerts 에서만 보인다, DEPLOYMENT.md §11).
#
#   텔레그램  ALERT_TELEGRAM_BOT_TOKEN · ALERT_TELEGRAM_CHAT_ID     (둘이 짝)
#   이메일    ALERT_EMAIL_TO · ALERT_SMTP_HOST(호스트:포트) · ALERT_SMTP_USER · ALERT_SMTP_PASSWORD   (넷이 짝 — 발신 주소는 SMTP 계정과 같다)
#
# 한 채널의 값이 반쪽이거나 형식이 틀리면 **파일을 쓰지 않고** 멈춘다 — 반쯤 채워진 수신자는 Alertmanager 기동 실패(= 경보 경로 전체 정지)다.
# 값은 어디에도 출력하지 않는다.
set -euo pipefail

OUT="${1:?사용법: render-alertmanager.sh <출력파일>}"
umask 077

TELEGRAM_TOKEN="${ALERT_TELEGRAM_BOT_TOKEN:-}"
TELEGRAM_CHAT="${ALERT_TELEGRAM_CHAT_ID:-}"
EMAIL_TO="${ALERT_EMAIL_TO:-}"
SMTP_HOST="${ALERT_SMTP_HOST:-}"
SMTP_USER="${ALERT_SMTP_USER:-}"
SMTP_PASSWORD="${ALERT_SMTP_PASSWORD:-}"

fail() { echo "오류: $*" >&2; exit 1; }

# 값 전부가 있거나 전부 없어야 한다. $1 = 채널 이름, 나머지 = "이름=값" 쌍.
require_all_or_none() {
    local channel="$1" pair set=0 total=0 missing=""
    shift
    for pair in "$@"; do
        total=$((total + 1))
        if [[ -n "${pair#*=}" ]]; then set=$((set + 1)); else missing="$missing ${pair%%=*}"; fi
    done
    if (( set > 0 && set < total )); then
        fail "$channel 경보 값이 일부만 있다 — 비어 있는 것:$missing (함께 등록하거나 함께 비운다)"
    fi
}

# YAML 작은따옴표 스칼라 안에서 그대로 쓸 수 없는 값(작은따옴표 · 줄바꿈)을 막는다.
require_plain() {
    [[ "$2" != *"'"* && "$2" != *$'\n'* ]] || fail "$1 값에 작은따옴표나 줄바꿈이 있다 — 설정 파일 인용이 깨진다"
}

require_all_or_none 텔레그램 "ALERT_TELEGRAM_BOT_TOKEN=$TELEGRAM_TOKEN" "ALERT_TELEGRAM_CHAT_ID=$TELEGRAM_CHAT"
require_all_or_none 이메일 "ALERT_EMAIL_TO=$EMAIL_TO" "ALERT_SMTP_HOST=$SMTP_HOST" "ALERT_SMTP_USER=$SMTP_USER" "ALERT_SMTP_PASSWORD=$SMTP_PASSWORD"

if [[ -n "$TELEGRAM_TOKEN" ]]; then
    [[ "$TELEGRAM_TOKEN" =~ ^[0-9]+:[A-Za-z0-9_-]+$ ]] || fail "ALERT_TELEGRAM_BOT_TOKEN 이 봇 토큰 형식(숫자:문자열)이 아니다"
    [[ "$TELEGRAM_CHAT" =~ ^-?[0-9]+$ ]] || fail "ALERT_TELEGRAM_CHAT_ID 가 숫자가 아니다(그룹은 음수)"
fi
if [[ -n "$EMAIL_TO" ]]; then
    require_plain ALERT_EMAIL_TO "$EMAIL_TO"
    require_plain ALERT_SMTP_HOST "$SMTP_HOST"
    require_plain ALERT_SMTP_USER "$SMTP_USER"
    require_plain ALERT_SMTP_PASSWORD "$SMTP_PASSWORD"
    [[ "$SMTP_HOST" =~ ^[A-Za-z0-9.-]+:[0-9]+$ ]] || fail "ALERT_SMTP_HOST 가 호스트:포트 형식이 아니다(예 smtp.gmail.com:587)"
    [[ "$EMAIL_TO" == *@* && "$SMTP_USER" == *@* ]] || fail "ALERT_EMAIL_TO · ALERT_SMTP_USER 가 이메일 주소가 아니다"
fi

mkdir -p "$(dirname "$OUT")"
{
    cat <<'YAML'
# 이 파일은 deploy.sh 가 render-alertmanager.sh 로 매 배포마다 새로 만든다 — 손으로 고치지 않는다(비밀값이 들어 있고 저장소 밖이다).
# 아래 수신자 항목에 채널 설정이 하나도 없으면 알림을 어디로도 보내지 않는다.
route:
  receiver: notify
  group_by: [alertname]
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h

receivers:
  - name: notify
YAML
    if [[ -n "$TELEGRAM_TOKEN" ]]; then
        printf "    telegram_configs:\n      - bot_token: '%s'\n        chat_id: %s\n        send_resolved: true\n" \
            "$TELEGRAM_TOKEN" "$TELEGRAM_CHAT"
    fi
    if [[ -n "$EMAIL_TO" ]]; then
        printf "    email_configs:\n      - to: '%s'\n        from: '%s'\n        smarthost: '%s'\n        auth_username: '%s'\n        auth_password: '%s'\n        send_resolved: true\n" \
            "$EMAIL_TO" "$SMTP_USER" "$SMTP_HOST" "$SMTP_USER" "$SMTP_PASSWORD"
    fi
} > "$OUT.tmp"
mv "$OUT.tmp" "$OUT"
# 값 자체가 아니라 있음·없음만 출력한다.
telegram_state=없음; [[ -z "$TELEGRAM_TOKEN" ]] || telegram_state=있음
email_state=없음; [[ -z "$EMAIL_TO" ]] || email_state=있음
echo "Alertmanager 설정 생성: $OUT (텔레그램 $telegram_state · 이메일 $email_state)"
