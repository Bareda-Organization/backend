#!/usr/bin/env bash
# 한 대 정지 확인(I-3) — nginx 뒤 백엔드 2대 중 한 대(backend)를 REST 연속 요청과 WebSocket 세션이 열린 채로 멈추고, 남은 대로 이어지는지 본다.
#
#   infra/scripts/check-instance-failover.sh         # 스택을 띄우고 확인한 뒤 내린다(프로젝트 r59-infra · down 만 · -v 없음)
#
# 판정
#   1. 멈춘 시각(ms) 이후에 시작한 REST 요청은 전부 2xx 이고 응답이 3초 안이어야 한다 — 멈추는 순간 이미 나가 있던 요청(시작이 멈춘 시각 이하)은 실패해도 센 것에서 뺀다.
#   2. 멈추기 전에 멈출 대에 세션이 하나 이상 붙어 있어야 한다(아니면 확인이 의미 없다 — 종료 코드 2).
#   3. 멈춘 지 25초 뒤 남은 대(backend2)의 세션 수가 전체 세션 수와 같아야 한다 — 멈춘 대에 붙었던 세션이 전부 nginx 를 거쳐 다시 붙었다는 뜻이다.
# 종료 코드: 0 = 통과 · 1 = 판정 실패 · 2 = 확인 자체가 깨졌다. 환경변수는 scale-stack.sh 머리말 + SESSIONS(기본 20).
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/scale-stack.sh"
command -v k6 >/dev/null || { echo "k6 가 필요하다 (brew install k6)" >&2; exit 2; }
trap 'scale_down; rm -f "${K6_ERR:-}" "${RESULT:-}"' EXIT

SESSIONS="${SESSIONS:-20}"
DURATION=60
now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }

scale_up || { echo "스택 기동 실패" >&2; exit 2; }
RESULT="$(mktemp "${TMPDIR:-/tmp}/r59-failover.XXXXXX.json")"; K6_ERR="$(mktemp "${TMPDIR:-/tmp}/r59-failover.XXXXXX.err")"

echo "== k6 시작 (nginx=$SCALE_NGINX_HTTPS · 세션 $SESSIONS · ${DURATION}초) =="
k6 run --quiet -e NGINX_URL="$SCALE_NGINX_HTTPS" -e BE1_URL="$SCALE_BE1" -e SESSIONS="$SESSIONS" -e DURATION_SEC="$DURATION" -e RESULT_FILE="$RESULT" \
    "$SCALE_REPO/backend/load/k6/r59_failover.js" >/dev/null 2>"$K6_ERR" &
K6_PID=$!

sleep 15
s1=$(scale_metric backend 'schoolbus_stomp_sessions'); s2=$(scale_metric backend2 'schoolbus_stomp_sessions')
echo "멈추기 전 세션 수: backend=$s1 backend2=$s2 (합 $((s1 + s2)) / 기대 $SESSIONS)"
[[ "$s1" -gt 0 ]] || { echo "BROKEN — 멈출 대(backend)에 붙은 세션이 없다"; kill "$K6_PID" 2>/dev/null; exit 2; }

STOP_AT=$(now_ms)
echo "== backend 정지 (docker compose stop · SIGTERM, 유예 35초) at=$STOP_AT =="
scale_dc stop backend >/dev/null 2>&1
echo "정지 완료 ($(( $(now_ms) - STOP_AT ))ms)"

sleep 25
s2_after=$(scale_metric backend2 'schoolbus_stomp_sessions')
echo "정지 25초 뒤 backend2 세션 수: $s2_after / 기대 $SESSIONS"

wait "$K6_PID" || true
[[ -s "$RESULT" ]] || { echo "BROKEN — k6 결과 파일이 없다"; exit 2; }
sent=$(jq .rest_sent "$RESULT"); failed=$(jq .rest_failed "$RESULT"); closes=$(jq .ws_closes "$RESULT"); reopens=$(jq .ws_reopens "$RESULT")
after=$(grep '^.*REST_FAIL' "$K6_ERR" | sed -n 's/.*start=\([0-9]*\).*/\1/p' | awk -v t="$STOP_AT" '$1 > t' | wc -l | tr -d ' ')
echo "REST 요청 ${sent}건 · 실패 ${failed}건 (멈춘 시각 이후 시작 ${after}건 · 멈추는 순간 이미 나가 있던 것 $((failed - after))건)"
echo "WebSocket 닫힘 $closes · 다시 붙음 $reopens"
[[ "$failed" -gt 0 ]] && grep REST_FAIL "$K6_ERR" | head -5

verdict=0
[[ "$sent" -gt 100 ]] || { echo "BROKEN — REST 요청이 너무 적다(${sent})"; verdict=2; }
[[ "$after" -eq 0 ]] || { echo "FAIL — 멈춘 뒤에 시작한 요청 ${after}건이 실패했다"; verdict=1; }
[[ "$s2_after" -eq "$SESSIONS" ]] || { echo "FAIL — 멈춘 대의 세션이 남은 대로 다 옮겨가지 않았다(backend2=$s2_after, 기대 $SESSIONS)"; [[ $verdict -eq 0 ]] && verdict=1; }
[[ $verdict -eq 0 ]] && echo "PASS — 멈춘 뒤 REST 실패 0 · 세션 $SESSIONS 개 모두 남은 대에 연결"
exit $verdict
