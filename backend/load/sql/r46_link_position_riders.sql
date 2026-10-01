-- R46-LOAD — 위치용 회차(LP-…, moving)에 명단을 붙인다. r3_mixed.sh 의 R3_MODE=realistic 이 회차마다 한 번 실행한다.
-- scenario2_prep.sql 이 만든 위치용 회차는 명단(run_rider)이 없어, 학부모가 자기 학생 채널을 구독해도 위치 방송이
-- 학생 채널로 나가지 않는다(PositionBroadcastListener 가 run_rider 로 학생 채널을 고른다). 실제 회차는 학생 약 20명이 붙어
-- 위치 1건이 학생 채널 20곳 + 학원 + 관제로 나간다 — 그 구조를 시험에 올리려면 명단이 있어야 한다.
--
-- ⚠ 같은 학원의 학생·승하차지만 잇는다(R46-LATERBE B-4, Ruling 675). 이 스크립트의 옛 판은 위치용 회차(학원 1 고정)에 다른 학원(LOADCAP-…)의
-- 학생·승하차지를 붙여 run_rider 18,000건이 학원 경계를 넘었다 — 앱 경로로는 만들 수 없는 데이터라 학원 범위 조인 수치와 화면 확인이 현실과
-- 달라졌다. 이제 위치용 회차는 학생이 속한 학원에 심고(scenario2_prep.sql -v academy_id=<LOADCAP 학원 id> · r3_mixed.sh 가 학원마다 한 번씩
-- 부른다), 위치용 회차 n 번째(그 학원 안에서 i 순) ↔ 그 학원 LOADCAP 버스의 (n-1) % 버스 수 + 1 번째 버스의 학생 20명을 붙인다. 학원 하나의
-- 위치용 회차가 버스 수(10)를 넘으면 같은 학생이 여러 회차에 붙을 수 있다(UNIQUE (run_id, student_id) 는 회차마다라 허용).
-- LOADCAP 버스가 없는 학원의 위치용 회차에는 아무것도 붙이지 않는다(다른 학원 학생으로 채우지 않는다).
-- 다시 실행해도 겹치지 않는다(UNIQUE (run_id, student_id) + ON CONFLICT).
-- 점검: check_academy_boundary.sql 의 mismatches 가 전부 0 이어야 한다.
--
-- 실행: docker exec -i school-bus-postgres-1 psql -U schoolbus -d schoolbus_load -q < sql/r46_link_position_riders.sql
WITH lp AS (
    SELECT r.id AS run_id, r.academy_id,
           row_number() OVER (PARTITION BY r.academy_id ORDER BY (regexp_match(b.bus_no, '-(\d+)$'))[1]::int) AS n
    FROM run r JOIN bus b ON b.id = r.bus_id
    WHERE b.bus_no LIKE 'LP-%' AND r.status = 'moving'
),
cap AS (
    SELECT bus_no, academy_id,
           row_number() OVER (PARTITION BY academy_id ORDER BY id) AS rn,
           count(*) OVER (PARTITION BY academy_id) AS per_academy
    FROM bus WHERE bus_no LIKE 'LOADCAP-%'
)
INSERT INTO run_rider (run_id, student_id, stop_id, status)
SELECT lp.run_id, s.id, wa.stop_id, 'waiting'
FROM lp
JOIN cap ON cap.academy_id = lp.academy_id AND cap.rn = ((lp.n - 1) % cap.per_academy) + 1
JOIN student s ON s.name LIKE cap.bus_no || '-U%' AND s.academy_id = lp.academy_id
JOIN weekly_address wa ON wa.student_id = s.id AND wa.direction = 'to_academy'
JOIN stop st ON st.id = wa.stop_id AND st.academy_id = lp.academy_id
ON CONFLICT (run_id, student_id) DO NOTHING;

SELECT count(*) AS linked_riders, count(DISTINCT run_id) AS runs
FROM run_rider rr JOIN run r ON r.id = rr.run_id JOIN bus b ON b.id = r.bus_id
WHERE b.bus_no LIKE 'LP-%' AND r.status = 'moving';
