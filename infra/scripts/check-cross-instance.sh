#!/usr/bin/env bash
# 인스턴스 간 전달 확인(I-1) — 백엔드 2대 구성에서 한 대(BE1)에 보낸 위치 · 비상 이벤트를 다른 대(BE2)에 붙은 구독자가 받는지 센다.
#
#   infra/scripts/check-cross-instance.sh            # 스택을 띄우고 확인한 뒤 내린다(프로젝트 r59-infra · down 만 · -v 없음)
#
# 출력: 이벤트 종류마다 `sent=N received=M` 한 줄, 같은 인스턴스(BE1) 대조 줄.
# 종료 코드: 0 = 교차 수신 건수가 모두 보낸 건수와 같다 · 1 = 교차 수신이 모자란다(전달 손실) · 2 = 확인 자체가 깨졌다(대조가 모자라거나 구독 실패 · 전송 실패).
# 환경변수: SCALE_SKIP_BUILD=1 · SCALE_KEEP_UP=1 · POSITIONS(기본 20) — 나머지는 scale-stack.sh 머리말.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/scale-stack.sh"
command -v k6 >/dev/null || { echo "k6 가 필요하다 (brew install k6)" >&2; exit 2; }
trap scale_down EXIT

scale_up || { echo "스택 기동 실패" >&2; exit 2; }

RESULT="$(mktemp "${TMPDIR:-/tmp}/r59-cross.XXXXXX.json")"
echo "== k6 실행 (BE1=$SCALE_BE1 → BE2=$SCALE_BE2) =="
k6 run --quiet -e BE1_URL="$SCALE_BE1" -e BE2_URL="$SCALE_BE2" -e POSITIONS="${POSITIONS:-20}" -e RESULT_FILE="$RESULT" \
    "$SCALE_REPO/backend/load/k6/r59_cross_instance.js" >/dev/null || { echo "k6 실행 실패" >&2; exit 2; }

verdict=0
sent_pos=$(jq .sent.position "$RESULT"); sent_emg=$(jq .sent.emergency_raised "$RESULT")
report() { # <이름> <보낸 건수> <교차 수신 키> <대조 수신 키>
    local cross control
    cross=$(jq ".received.$3" "$RESULT"); control=$(jq ".received.$4" "$RESULT")
    echo "$1 sent=$2 received=$cross   (대조: 같은 인스턴스 BE1 received=$control)"
    [[ "$control" -eq "$2" ]] || verdict=2
    [[ "$cross" -eq "$2" ]] || { [[ $verdict -eq 0 ]] && verdict=1; }
}
echo "== 결과 =="
report "학생 채널  position         " "$sent_pos" be2_student_position be1_student_position
report "학원 채널  position         " "$sent_pos" be2_academy_position be1_academy_position
report "학원 채널  emergency_raised " "$sent_emg" be2_academy_emergency_raised be1_academy_emergency_raised
[[ "$(jq '.subscribe_failures + .send_failures' "$RESULT")" -eq 0 && "$(jq .subscribers_connected "$RESULT")" -eq 4 && "$sent_pos" -gt 0 && "$sent_emg" -eq 1 ]] || { echo "구독 실패 · 전송 실패가 있거나 구독자 4명이 모두 붙지 않았다 — 확인이 깨졌다 ($(jq -c '{subscribe_failures,subscribers_connected,send_failures,sent}' "$RESULT"))"; verdict=2; }
rm -f "$RESULT"
case $verdict in
    0) echo "PASS — 받은 건수 = 보낸 건수" ;;
    1) echo "FAIL — 다른 인스턴스에 붙은 구독자가 보낸 이벤트를 다 받지 못했다" ;;
    *) echo "BROKEN — 확인 자체가 깨졌다(위 줄 참고)" ;;
esac
exit $verdict
