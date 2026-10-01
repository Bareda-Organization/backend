-- R46-LOAD — 위치용 회차(LP-…, moving)에 명단을 붙인다. r3_mixed.sh 의 R3_MODE=realistic 이 회차마다 한 번 실행한다.
-- scenario2_prep.sql 이 만든 위치용 회차는 명단(run_rider)이 없어, 학부모가 자기 학생 채널을 구독해도 위치 방송이
-- 학생 채널로 나가지 않는다(PositionBroadcastListener 가 run_rider 로 학생 채널을 고른다). 실제 회차는 학생 약 20명이 붙어
-- 위치 1건이 학생 채널 20곳 + 학원 + 관제로 나간다 — 그 구조를 시험에 올리려면 명단이 있어야 한다.
--
-- 위치용 회차 i 번째 ↔ R0 시드의 i 번째 버스(id 순, 100 으로 나눈 나머지)의 학생 20명. 위치용 회차가 100개면 2,000명 전원이
-- 정확히 한 회차에 붙는다. 학원이 서로 달라도(위치용 회차는 학원 1 고정) 방송 쪽은 학원을 보지 않는다.
-- 다시 실행해도 겹치지 않는다(UNIQUE (run_id, student_id) + ON CONFLICT).
--
-- 실행: docker exec -i school-bus-postgres-1 psql -U schoolbus -d schoolbus_load -q < sql/r46_link_position_riders.sql
WITH lp AS (
    SELECT r.id AS run_id, (regexp_match(b.bus_no, '-(\d+)$'))[1]::int AS i
    FROM run r JOIN bus b ON b.id = r.bus_id
    WHERE b.bus_no LIKE 'LP-%' AND r.status = 'moving'
),
cap AS (
    SELECT bus_no, row_number() OVER (ORDER BY id) AS rn FROM bus WHERE bus_no LIKE 'LOADCAP-%'
)
INSERT INTO run_rider (run_id, student_id, stop_id, status)
SELECT lp.run_id, s.id, wa.stop_id, 'waiting'
FROM lp
JOIN cap ON cap.rn = ((lp.i - 1) % 100) + 1
JOIN student s ON s.name LIKE cap.bus_no || '-U%'
JOIN weekly_address wa ON wa.student_id = s.id AND wa.direction = 'to_academy'
ON CONFLICT (run_id, student_id) DO NOTHING;

SELECT count(*) AS linked_riders, count(DISTINCT run_id) AS runs
FROM run_rider rr JOIN run r ON r.id = rr.run_id JOIN bus b ON b.id = r.bus_id
WHERE b.bus_no LIKE 'LP-%' AND r.status = 'moving';
