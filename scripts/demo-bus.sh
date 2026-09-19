#!/bin/bash
# 로컬 시연용 — 기사 단말인 척하고 회차의 버스를 노선 위로 움직인다.
#
# 실제 버스가 없어도 화면을 확인할 수 있게 한다. 새 목 데이터를 만들지 않고
# 이미 있는 기사 API(POST /runs/{id}/position · .../arrive)만 부른다.
#
#   사용법:  ./scripts/demo-bus.sh [회차ID]      (기본 3 — 시드의 moving 회차)
#
# 스케줄러 주기: 근접·출발 판정 10초. 한 정차지를 지날 때마다 그만큼 기다린다.
set -euo pipefail

RUN_ID="${1:-3}"
BASE="${BASE:-http://localhost:3000/api/v1}"
PG="${PG:-school-bus-postgres-1}"
STEPS=6          # 정차지 사이를 몇 번에 나눠 이동하는가
STEP_SLEEP=2     # 위치 전송 간격(초)

psql_q() { docker exec "$PG" psql -U schoolbus -d schoolbus -tAc "$1"; }

DRIVER=$(psql_q "SELECT a.login_id FROM assignment asg
                   JOIN manager m ON m.id = asg.manager_id
                   JOIN account a ON a.id = m.account_id
                  WHERE asg.run_id = $RUN_ID AND m.role = 'driver' LIMIT 1")
[ -n "$DRIVER" ] || { echo "회차 $RUN_ID 에 배정된 기사가 부재"; exit 1; }

STATUS=$(psql_q "SELECT status FROM run WHERE id = $RUN_ID")
echo "회차 $RUN_ID · 상태 $STATUS · 기사 $DRIVER"
[ "$STATUS" = "moving" ] || echo "⚠ moving 이 아니라 위치 수신이 거절될 수 있다(기사 앱에서 운행 시작 먼저)"

TOKEN=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
          -d "{\"login_id\":\"$DRIVER\",\"password\":\"password\"}" \
        | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["access_token"])')

# 정차지를 seq 순으로 읽는다 — 확정 노선의 현재 버전만 본다.
# macOS 기본 bash 3.2 에는 mapfile 이 부재하므로 개행 구분 문자열로 받는다.
STOPS=$(psql_q "SELECT rs.seq||'|'||rs.stop_id||'|'||s.lat||'|'||s.lng||'|'||s.name
                  FROM run_stop rs
                  JOIN stop s ON s.id = rs.stop_id
                  JOIN confirmed_route cr ON cr.current_version_id = rs.route_version_id
                 WHERE cr.run_id = $RUN_ID ORDER BY rs.seq")
[ -n "$STOPS" ] || { echo "회차 $RUN_ID 에 확정 노선이 부재"; exit 1; }

send() { # lat lng
    curl -s -o /dev/null -w '' -X POST "$BASE/runs/$RUN_ID/position" \
        -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
        -d "{\"lat\":$1,\"lng\":$2,\"recorded_at\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"speed\":30,\"heading\":90}"
}

# 첫 정차지에서 남서쪽으로 약 1km 떨어진 곳에서 출발한다.
IFS='|' read -r _ _ LAT0 LNG0 _ <<< "$(echo "$STOPS" | head -1)"
CUR_LAT=$(python3 -c "print($LAT0 - 0.009)")
CUR_LNG=$(python3 -c "print($LNG0 - 0.009)")
send "$CUR_LAT" "$CUR_LNG"; echo "출발 지점 $CUR_LAT, $CUR_LNG"

echo "$STOPS" | while IFS='|' read -r SEQ STOP_ID LAT LNG NAME; do
    [ -n "$SEQ" ] || continue
    echo; echo "── seq $SEQ · $NAME 로 이동"
    for i in $(seq 1 $STEPS); do
        P_LAT=$(python3 -c "print(round($CUR_LAT + ($LAT - $CUR_LAT) * $i / $STEPS, 6))")
        P_LNG=$(python3 -c "print(round($CUR_LNG + ($LNG - $CUR_LNG) * $i / $STEPS, 6))")
        send "$P_LAT" "$P_LNG"
        D=$(python3 -c "
import math
dy=($LAT-$P_LAT)*111000; dx=($LNG-$P_LNG)*111000*math.cos(math.radians($LAT))
print(round(math.hypot(dx,dy)))")
        echo "   $P_LAT, $P_LNG  (남은 거리 ${D}m)"
        sleep $STEP_SLEEP
    done
    CUR_LAT=$LAT; CUR_LNG=$LNG

    echo "   근접 판정 대기(10초 주기) — 학부모에게 접근 알림이 나가야 한다"
    sleep 12

    echo "   도착 처리"
    curl -s -X POST "$BASE/runs/$RUN_ID/stops/$STOP_ID/arrive" \
        -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}' | head -c 200; echo

    # 100m 를 확실히 벗어나야 출발로 판정된다(ProximityJudge.DEPARTURE_THRESHOLD_METERS).
    OUT_LAT=$(python3 -c "print(round($LAT + 0.0025, 6))")
    send "$OUT_LAT" "$LNG"
    echo "   ${NAME} 에서 이탈 → 출발 판정 대기"
    sleep 12
    CUR_LAT=$OUT_LAT
done

echo; echo "노선 끝. 기록된 위치 $(psql_q "SELECT count(*) FROM run_position WHERE run_id = $RUN_ID")건"
