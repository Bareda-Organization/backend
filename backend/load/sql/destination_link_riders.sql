-- 학생 목적지 시험 — 위치용 회차(LP-…, moving)에 destination_seed.sql 의 학생(L3DEST-…)을 명단으로 붙인다.
-- r46_link_position_riders.sql 이 LOADCAP 학생 20명씩을 붙인 뒤에 한 번 더 실행한다. 같은 학원의 학생 · 승하차지만 잇는다(Ruling 675).
-- 위치용 회차 n 번째(그 학원 안에서 순) ↔ 그 학원 LOADCAP 버스의 (n-1) % 버스 수 + 1 번째 버스의 L3DEST 학생. 다시 실행해도 겹치지 않는다.
--
-- 실행: docker exec -i school-bus-postgres-1 psql -U schoolbus -d schoolbus_load -q < sql/destination_link_riders.sql
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
SELECT lp.run_id, s.id, st.id, 'waiting'
FROM lp
JOIN cap ON cap.academy_id = lp.academy_id AND cap.rn = ((lp.n - 1) % cap.per_academy) + 1
JOIN academy a ON a.id = lp.academy_id
JOIN student s ON s.name LIKE 'L3DEST-' || substr(cap.bus_no, 9) || '-V%' AND s.academy_id = lp.academy_id
JOIN stop st ON st.academy_id = lp.academy_id AND st.name = 'L3DEST-' || a.code || '-S0'
ON CONFLICT (run_id, student_id) DO NOTHING;

SELECT count(*) AS linked_riders, count(DISTINCT rr.student_id) AS distinct_students, count(DISTINCT rr.run_id) AS runs
FROM run_rider rr JOIN run r ON r.id = rr.run_id JOIN bus b ON b.id = r.bus_id
WHERE b.bus_no LIKE 'LP-%' AND r.status = 'moving';
