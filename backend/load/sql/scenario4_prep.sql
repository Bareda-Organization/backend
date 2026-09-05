-- 시나리오 4 준비 — "온디맨드 계산 경합" (IMPLEMENTATION_PLAN §5.2 #4).
--
-- 목표: GET /staff/approvals/{id} 호출마다 ApprovalPreviewResolver 가 재최적화를 1회 실행하고
-- (ApprovalQueryService.detail, 직접 코드 확인) 그 안에서 RouteComputationPipeline 이 지도 API
-- (부하 프로파일에서는 StubMapRouteClient, CallerPolicy.ON_DEMAND)를 호출한다. 이 호출이 배치
-- 확정(§9, CallerPolicy.BATCH)과 같은 스텁 인스턴스를 동시에 두드리게 만드는 것이 시나리오 4다.
--
-- ⚠ change_request.type='cancel' 로 대상 학생을 빼기만 하면 roster 가 0명이 되어(candidateRosterOf,
-- 직접 코드 확인) RouteComputationPipeline 이 지점 1개(학원)만 받아 지도 API를 아예 부르지 않을
-- 수 있다 — 그러면 스텁 부하 주입이 한 번도 안 걸려 이 시나리오가 성립하지 않는다. 그래서 회차마다
-- 학생을 2명(취소 대상 1 + 잔류 1) 심어 "취소 후에도 최소 1명이 남아 지도 API를 반드시 부른다."
--
-- change_request 마다 approval_id(=id) 가 달라야 매 GET 이 최초 조회(캐시 미스 → 재계산)가 된다 —
-- 같은 approval_id 를 반복 조회하면 두 번째부터 ApprovalPreviewCache 가 재계산 없이 캐시를 돌려줘
-- (직접 코드 확인, resolvePreview) 부하가 사라진다. 그래서 N 개의 서로 다른 change_request 를 심는다.
--
-- RETURNING 은 그 INSERT 대상 테이블의 컬럼만 참조할 수 있다(다른 테이블의 컬럼은 안 된다) — student
-- 는 run/bus 로 가는 FK 가 없어 join key 를 담을 자리가 없다. note(text, 제약 없음)에 bus_id 를
-- 문자열로 적어 두고 나중에 다시 bigint 로 캐스트해 join key 로 쓴다. 실제 서비스 데이터에는 note 를
-- 이런 용도로 쓰지 않는다 — 이 스크립트 안에서만 통용되는 임시 편법이다.
--
-- 실행:
--   psql "$DB_URL" -v n=20 -f scenario4_prep.sql -t -A -F',' | grep -v '^$' > scenario4_approvals.csv
-- 출력 — (tag, login_id, approval_id) — login_id 는 항상 staffA(academy 1 소속 관계자, db/migration-local
-- V2 시드) 로 고정한다. §5.5 권한은 "학원 관계자" 라 계정을 새로 만들 필요가 없다.
--
-- 필수 변수: n.
\if :{?n}
\else
    \echo '변수 n 이 없다 — 예: psql -v n=20 -f scenario4_prep.sql -t -A -F","'
    \quit
\endif

\set academy_id 1
\set staff_login_id 'staffA'
\set staff_account_id 2

BEGIN;

WITH series AS (
    SELECT generate_series(1, :n) AS i
),
run_tag AS (
    SELECT to_char(clock_timestamp(), 'HH24MISSMS') AS tag
),
target_stops AS (
    -- academy 1의 stop 두 곳 — "취소 전 2정차 → 취소 후 1정차"로 before/after 가 실제로 갈리게 한다.
    SELECT id, row_number() OVER (ORDER BY id) AS rn
    FROM (SELECT id FROM stop WHERE academy_id = :academy_id ORDER BY id LIMIT 2) s
),
stop1 AS (SELECT id AS stop_id FROM target_stops WHERE rn = 1),
stop2 AS (SELECT id AS stop_id FROM target_stops WHERE rn = 2),
weekday_today AS (
    SELECT (ARRAY['mon','tue','wed','thu','fri','sat','sun'])[extract(isodow FROM current_date)::int] AS wd
),
new_bus AS (
    INSERT INTO bus (academy_id, bus_no, plate_no, capacity, driver_count, escort_count, student_capacity, operable)
    SELECT :academy_id,
           'LOADAPV-' || run_tag.tag || '-' || series.i,
           'LOADAPV-' || run_tag.tag || '-' || series.i,
           20, 1, 1, 18, true
    FROM series, run_tag
    RETURNING id AS bus_id
),
new_route AS (
    INSERT INTO route (academy_id, bus_id, weekday, direction, name, active)
    SELECT :academy_id, new_bus.bus_id, weekday_today.wd, 'to_academy',
           'LOADAPV-ROUTE-' || new_bus.bus_id, true
    FROM new_bus, weekday_today
    RETURNING id AS route_id, bus_id
),
new_route_stop_1 AS (
    INSERT INTO route_stop (route_id, stop_id, seq)
    SELECT new_route.route_id, stop1.stop_id, 1
    FROM new_route, stop1
    RETURNING route_id
),
new_route_stop_2 AS (
    INSERT INTO route_stop (route_id, stop_id, seq)
    SELECT new_route.route_id, stop2.stop_id, 2
    FROM new_route, stop2
    RETURNING route_id
),
-- confirmed 상태로 심는다 — 출발 전(depart_time 을 미래로) 승인 대기가 열려 있는 정상 상태를 흉내
-- 낸다. 배치 스케줄러(§9, status='idle' 만 집어감)와 겹치지 않아 시나리오 1과 동시에 돌려도 서로의
-- 대상을 침범하지 않는다.
new_run AS (
    INSERT INTO run (academy_id, bus_id, schedule_id, service_date, direction,
                      depart_time, confirm_at, status, origin_name, destination_name,
                      confirmed_at, consecutive_failures)
    SELECT :academy_id, new_bus.bus_id, NULL, current_date, 'to_academy',
           now() + interval '45 minutes',
           now() + interval '15 minutes',
           'confirmed', 'LOADAPV-ORIGIN', 'LOADAPV-DEST',
           now(), 0
    FROM new_bus
    RETURNING id AS run_id, bus_id, depart_time
),
-- join key(bus_id)를 note 에 문자열로 실어 나른다 — student 는 run/bus 로 가는 FK 가 없어 RETURNING
-- 이 새로 만들 수 있는 컬럼이 note 뿐이다(위 파일 머리 설명 참조).
new_student_cancel AS (
    INSERT INTO student (academy_id, name, can_go_alone, note)
    SELECT :academy_id, 'LOADAPV-CANCEL-' || new_bus.bus_id, false, new_bus.bus_id::text
    FROM new_bus
    RETURNING id AS student_id, note::bigint AS bus_id
),
new_student_stay AS (
    INSERT INTO student (academy_id, name, can_go_alone, note)
    SELECT :academy_id, 'LOADAPV-STAY-' || new_bus.bus_id, false, new_bus.bus_id::text
    FROM new_bus
    RETURNING id AS student_id, note::bigint AS bus_id
),
-- confirmed_route.current_version_id ↔ route_version.confirmed_route_id 순환 FK — NULL 로 먼저
-- 넣고 버전을 심은 뒤 되채운다(schema 마이그레이션 주석이 설명하는 것과 같은 순서).
new_confirmed_route AS (
    INSERT INTO confirmed_route (run_id, current_version_id, confirmed_at)
    SELECT new_run.run_id, NULL, now()
    FROM new_run
    RETURNING run_id
),
new_route_version AS (
    INSERT INTO route_version (confirmed_route_id, version_no, source, est_duration_min, est_distance_km,
                                published_at, input_fingerprint, engine_name, policy_snapshot, fallback_used)
    SELECT new_confirmed_route.run_id, 1, 'confirm_batch', 10, 5.00, now(),
           'LOADAPV-SEED-' || new_confirmed_route.run_id, 'seed-loadtest', '{}'::jsonb, false
    FROM new_confirmed_route
    RETURNING id AS version_id, confirmed_route_id
),
update_confirmed_route AS (
    UPDATE confirmed_route
    SET current_version_id = new_route_version.version_id
    FROM new_route_version
    WHERE confirmed_route.run_id = new_route_version.confirmed_route_id
    RETURNING confirmed_route.run_id
),
new_run_stop_1 AS (
    INSERT INTO run_stop (route_version_id, stop_id, seq, eta)
    SELECT new_route_version.version_id, stop1.stop_id, 1, now() + interval '10 minutes'
    FROM new_route_version, stop1
    RETURNING route_version_id
),
new_run_stop_2 AS (
    INSERT INTO run_stop (route_version_id, stop_id, seq, eta)
    SELECT new_route_version.version_id, stop2.stop_id, 2, now() + interval '20 minutes'
    FROM new_route_version, stop2
    RETURNING route_version_id
),
new_run_rider_cancel AS (
    INSERT INTO run_rider (run_id, student_id, stop_id, status)
    SELECT new_run.run_id, new_student_cancel.student_id, stop1.stop_id, 'waiting'
    FROM new_run
    JOIN new_student_cancel ON new_student_cancel.bus_id = new_run.bus_id
    CROSS JOIN stop1
    RETURNING run_id
),
new_run_rider_stay AS (
    INSERT INTO run_rider (run_id, student_id, stop_id, status)
    SELECT new_run.run_id, new_student_stay.student_id, stop2.stop_id, 'waiting'
    FROM new_run
    JOIN new_student_stay ON new_student_stay.bus_id = new_run.bus_id
    CROSS JOIN stop2
    RETURNING run_id
),
new_change_request AS (
    INSERT INTO change_request (academy_id, run_id, student_id, source, type, status, window_segment,
                                 requested_by, requested_at, deadline_at)
    SELECT :academy_id, new_run.run_id, new_student_cancel.student_id, 'intent', 'cancel', 'pending', 1,
           :staff_account_id, now(), new_run.depart_time
    FROM new_run
    JOIN new_student_cancel ON new_student_cancel.bus_id = new_run.bus_id
    RETURNING id AS approval_id
)
SELECT run_tag.tag, :'staff_login_id', new_change_request.approval_id
FROM new_change_request
CROSS JOIN run_tag
ORDER BY new_change_request.approval_id;

COMMIT;
