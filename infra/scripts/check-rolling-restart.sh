#!/usr/bin/env bash
# 한 대씩 재시작 확인(I-4) — 로컬 2대 스택에서 rolling-restart.sh(deploy.sh 2대 모드가 부르는 같은 스크립트)를 REST · WebSocket 부하 중에 돌린다.
#
#   infra/scripts/check-rolling-restart.sh           # 스택을 띄우고 확인한 뒤 내린다(프로젝트 r59-infra · down 만 · -v 없음)
#
# 판정
#   1. 한 대를 다시 만드는 동안(restart~healthy) 다른 대의 열린 세션 수가 그 구간 시작 값 아래로 내려가지 않는다 — 다른 대의 지표(schoolbus_stomp_sessions)를 1초마다 읽는다.
#   2. nginx(proxy) 컨테이너의 시작 시각이 롤링 전후로 같다 — nginx 재기동 0.
#   3. 끝나면 두 대 모두 healthy 이고 세션 합이 전체 세션 수와 같다.
#   4. REST 연속 요청 실패 건수를 기록한다(판정에 쓰지 않는다 — 멈추는 순간 진행 중 요청 기준은 I-3 이 본다). 값이 크면 눈으로 확인한다.
# 종료 코드: 0 = 통과 · 1 = 판정 실패 · 2 = 확인 자체가 깨졌다. 환경변수는 scale-stack.sh 머리말 + SESSIONS(기본 20).
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/scale-stack.sh"
command -v k6 >/dev/null || { echo "k6 가 필요하다 (brew install k6)" >&2; exit 2; }
trap 'kill "${SAMPLER_PID:-}" 2>/dev/null; scale_down; rm -f "${K6_ERR:-}" "${RESULT:-}" "${SAMPLES:-}" "${ROLL_OUT:-}"' EXIT

SESSIONS="${SESSIONS:-20}"
DURATION=110

scale_up || { echo "스택 기동 실패" >&2; exit 2; }
RESULT="$(mktemp "${TMPDIR:-/tmp}/r59-roll.XXXXXX.json")"; K6_ERR="$(mktemp "${TMPDIR:-/tmp}/r59-roll.XXXXXX.err")"
SAMPLES="$(mktemp "${TMPDIR:-/tmp}/r59-roll.XXXXXX.csv")"; ROLL_OUT="$(mktemp "${TMPDIR:-/tmp}/r59-roll.XXXXXX.out")"

echo "== k6 시작 (nginx=$SCALE_NGINX_HTTPS · 세션 $SESSIONS · ${DURATION}초) =="
k6 run --quiet -e NGINX_URL="$SCALE_NGINX_HTTPS" -e BE1_URL="$SCALE_BE1" -e SESSIONS="$SESSIONS" -e DURATION_SEC="$DURATION" -e RESULT_FILE="$RESULT" \
    "$SCALE_REPO/backend/load/k6/r59_failover.js" >/dev/null 2>"$K6_ERR" &
K6_PID=$!
sleep 12

PROXY_START_BEFORE="$(scale_started_at proxy)"
# 1초마다 두 대의 세션 수를 CSV 로 남긴다 — scale_metric 은 지표를 못 읽으면(컨테이너가 내려가 있으면) 0 을 낸다. 판정은 내려가지 않은 쪽 열만 쓴다.
(
    while :; do
        a=$(scale_metric backend 'schoolbus_stomp_sessions' 2>/dev/null); b=$(scale_metric backend2 'schoolbus_stomp_sessions' 2>/dev/null)
        echo "$(python3 -c 'import time; print(int(time.time() * 1000))'),${a},${b}" >> "$SAMPLES"
        sleep 1
    done
) &
SAMPLER_PID=$!
sleep 3

echo "== rolling-restart.sh backend backend2 =="
COMPOSE="$SCALE_COMPOSE" "$SCALE_REPO/infra/scripts/rolling-restart.sh" backend backend2 2>&1 | tee "$ROLL_OUT" | grep -v "^ Container\|^ Network"
roll_rc=${PIPESTATUS[0]}
sleep 8
# k6 가 끝나기 전에(세션이 열려 있을 때) 세션 합을 읽는다.
total=$(( $(scale_metric backend 'schoolbus_stomp_sessions') + $(scale_metric backend2 'schoolbus_stomp_sessions') ))
kill "$SAMPLER_PID" 2>/dev/null; wait "$SAMPLER_PID" 2>/dev/null; SAMPLER_PID=
PROXY_START_AFTER="$(scale_started_at proxy)"
wait "$K6_PID" || true

verdict=0
[[ $roll_rc -eq 0 && -s "$RESULT" ]] || { echo "BROKEN — 롤링 종료 코드 $roll_rc 또는 k6 결과 없음"; exit 2; }
ts() { awk -v s="$1" -v p="$2" '$1=="ROLL" && $2==s && $3==p {print $4}' "$ROLL_OUT"; }
# 구간 안(restart~healthy)에서 다른 대 열의 최솟값과, 구간 시작 직전 값
window_min() { # <다른 대 열 번호(2|3)> <시작ms> <끝ms>
    awk -F, -v c="$1" -v s="$2" -v e="$3" '$1>=s && $1<=e { if (min=="" || $c<min) min=$c } END { print (min=="" ? "none" : min) }' "$SAMPLES"
}
value_before() { awk -F, -v c="$1" -v s="$2" '$1<=s { v=$c } END { print v }' "$SAMPLES"; }

R1=$(ts backend restart); H1=$(ts backend healthy); R2=$(ts backend2 restart); H2=$(ts backend2 healthy)
for pair in "backend2:3:$R1:$H1" "backend:2:$R2:$H2"; do
    IFS=: read -r other col start end <<<"$pair"
    before=$(value_before "$col" "$start"); min=$(window_min "$col" "$start" "$end")
    echo "${other} 세션 수: 구간 시작 ${before} · 구간 중 최솟값 ${min}"
    [[ "$min" != none && "$min" -ge "$before" ]] || { echo "FAIL — 다시 만드는 동안 ${other} 의 세션 수가 줄었다"; verdict=1; }
done
[[ "$PROXY_START_BEFORE" == "$PROXY_START_AFTER" ]] && echo "nginx 시작 시각 변화 없음 ($PROXY_START_AFTER)" || { echo "FAIL — nginx(proxy)가 다시 시작됐다 ($PROXY_START_BEFORE → $PROXY_START_AFTER)"; verdict=1; }
for svc in backend backend2; do
    status="$(scale_dc ps --format '{{.Status}}' "$svc")"; [[ "$status" == *"(healthy)"* ]] || { echo "FAIL — $svc 가 healthy 가 아니다: $status"; verdict=1; }
done
echo "끝난 뒤 세션 합 ${total} / 기대 ${SESSIONS}"
[[ "$total" -eq "$SESSIONS" ]] || { echo "FAIL — 세션 합이 전체와 다르다"; verdict=1; }
echo "REST 요청 $(jq .rest_sent "$RESULT")건 · 실패 $(jq .rest_failed "$RESULT")건 (참고용)"
grep REST_FAIL "$K6_ERR" | head -5
[[ $verdict -eq 0 ]] && echo "PASS — 한 대씩 재시작 · 다른 대 세션 유지 · nginx 재시작 0"
exit $verdict
