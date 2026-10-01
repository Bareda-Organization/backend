-- 학원 경계 점검(R46-LATERBE B-4 · Ruling 675) — academy_id 컬럼이 없는 자식 행이 다른 학원의 부모를 가리키지 않는지 센다.
-- 결과의 mismatches 는 전부 0 이어야 한다. academy_id 를 가진 자식은 복합 FK(V1)가 DB 에서 막으므로 여기 없다.
-- 쓰는 곳: SeedBoundaryCheckTest(시드 적재 직후 0건) · 부하 DB 점검:
--   docker exec -i school-bus-postgres-1 psql -U schoolbus -d <DB> < backend/load/sql/check_academy_boundary.sql
-- 옛 r46_link_position_riders.sql 이 이미 만든 어긋난 행은 이 한 문장으로 지운다(새 스크립트는 추가만 하고 지우지 않는다):
--   DELETE FROM run_rider rr USING run r, student s WHERE r.id = rr.run_id AND s.id = rr.student_id AND s.academy_id <> r.academy_id;
SELECT 'run_rider.student_id ↔ run' AS check_name, count(*) AS mismatches
FROM run_rider rr JOIN run r ON r.id = rr.run_id JOIN student s ON s.id = rr.student_id
WHERE s.academy_id <> r.academy_id
UNION ALL
SELECT 'run_rider.stop_id ↔ run', count(*)
FROM run_rider rr JOIN run r ON r.id = rr.run_id JOIN stop st ON st.id = rr.stop_id
WHERE st.academy_id <> r.academy_id
UNION ALL
SELECT 'route_stop.stop_id ↔ route', count(*)
FROM route_stop rs JOIN route ro ON ro.id = rs.route_id JOIN stop st ON st.id = rs.stop_id
WHERE st.academy_id <> ro.academy_id
UNION ALL
SELECT 'run_stop.stop_id ↔ run', count(*)
FROM run_stop x JOIN route_version rv ON rv.id = x.route_version_id JOIN run r ON r.id = rv.confirmed_route_id
     JOIN stop st ON st.id = x.stop_id
WHERE st.academy_id <> r.academy_id;
