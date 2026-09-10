-- R0 이 심은 회차를 다시 idle 로 되돌린다 — 확정 배치 라운드를 반복하려면 필요하다.
-- 확정 산출물(확정 노선·버전·정차 순서·배정 학생)을 지우지 않고 status 만 되돌리면, 다음 확정이
-- 같은 회차에 두 번째 버전을 쌓아 "동시 도래 100건" 이 아니라 "재확정 100건" 을 재게 된다.
--
-- 실행: docker exec -i school-bus-postgres-1 psql -U schoolbus -d schoolbus_load -q < sql/r0_reset_runs.sql
BEGIN;

CREATE TEMP TABLE target_run AS
SELECT r.id FROM run r JOIN bus b ON b.id = r.bus_id WHERE b.bus_no LIKE 'LOADCAP-%';

DELETE FROM run_rider WHERE run_id IN (SELECT id FROM target_run);
DELETE FROM run_stop WHERE route_version_id IN (
    SELECT rv.id FROM route_version rv WHERE rv.confirmed_route_id IN (SELECT id FROM target_run));
UPDATE confirmed_route SET current_version_id = NULL WHERE run_id IN (SELECT id FROM target_run);
DELETE FROM route_version WHERE confirmed_route_id IN (SELECT id FROM target_run);
DELETE FROM confirmed_route WHERE run_id IN (SELECT id FROM target_run);

-- 등원 +6h · 하원 +8h 로 되돌린다(R0 시드와 같은 값). confirm_at 은 CHECK 가 depart_time - 30분 을 강제한다.
UPDATE run r
SET status = 'idle', confirmed_at = NULL, started_at = NULL, finished_at = NULL, consecutive_failures = 0,
    depart_time = now() + (CASE WHEN r.direction = 'to_academy' THEN interval '6 hours' ELSE interval '8 hours' END),
    confirm_at  = now() + (CASE WHEN r.direction = 'to_academy' THEN interval '6 hours' ELSE interval '8 hours' END)
                  - interval '30 minutes'
WHERE r.id IN (SELECT id FROM target_run);

COMMIT;

SELECT status, direction, count(*) FROM run r JOIN bus b ON b.id = r.bus_id
WHERE b.bus_no LIKE 'LOADCAP-%' GROUP BY 1, 2 ORDER BY 1, 2;
