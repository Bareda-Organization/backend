-- 시나리오 2 준비 — "위치 수신 처리량" (IMPLEMENTATION_PLAN §5.2 #2).
--
-- RunAssignmentAccess.assertAssignedDriver (직접 코드 확인) 는 POST /runs/{runId}/position 이
-- account(role=driver) → manager(account_id 로 연결) → assignment(run_id, role=driver) 사슬을
-- 전부 요구한다. 시나리오 1과 달리 회차 하나당 계정 3장(account·manager·assignment)을 더 심어야
-- 하고, run.status 는 'idle' 이 아니라 'moving' 이어야 위치 업로드가 RUN_NOT_MOVING 없이 통과한다.
--
-- k6 는 로그인에 login_id/password 평문이 필요하므로, 시드 비밀번호 해시(BCrypt, 평문 "password")를
-- application-load.yml 의 seedPasswordHash 와 동일한 값으로 여기 직접 박는다 — 같은 평문이어야
-- k6 스크립트가 실제로 로그인할 수 있다.
--
-- 출력 — 마지막 SELECT 가 (tag, login_id, run_id) 3열을 회차 수만큼 낸다. k6 스크립트가
-- SharedArray 로 읽을 CSV 로 저장하려면:
--   psql "$DB_URL" -v n=20 -f scenario2_prep.sql -t -A -F',' | grep -v '^$' > scenario2_runs.csv
--
-- 필수 변수: n (심을 회차=기사 수).
\if :{?n}
\else
    \echo '변수 n 이 없다 — 예: psql -v n=20 -f scenario2_prep.sql -t -A -F","'
    \quit
\endif

\set academy_id 1
\set seed_password_hash '$2a$10$Noeszx0nzJUfNo4ubCD03eNfMVfMD9feMo04y/8DHiFLUBF.JZ/Fq'

BEGIN;

WITH series AS (
    SELECT generate_series(1, :n) AS i
),
run_tag AS (
    SELECT to_char(clock_timestamp(), 'HH24MISSMS') AS tag
),
target_stop AS (
    SELECT id AS stop_id FROM stop WHERE academy_id = :academy_id ORDER BY id LIMIT 1
),
weekday_today AS (
    SELECT (ARRAY['mon','tue','wed','thu','fri','sat','sun'])[extract(isodow FROM current_date)::int] AS wd
),
new_account AS (
    -- 접두사 LOADPOS- 로 시나리오 1의 LOAD- 와 겹치지 않는다.
    INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status)
    SELECT :academy_id,
           'loadpos-' || run_tag.tag || '-' || series.i,
           :'seed_password_hash',
           '부하시험기사' || series.i,
           '010-0000-' || lpad(series.i::text, 4, '0'),
           'driver', 'active'
    FROM series, run_tag
    RETURNING id AS account_id, login_id,
        (regexp_match(login_id, '-(\d+)$'))[1]::int AS i
),
new_manager AS (
    INSERT INTO manager (academy_id, account_id, name, phone, role)
    SELECT :academy_id, new_account.account_id, new_account.login_id, '010-0000-0000', 'driver'
    FROM new_account
    RETURNING id AS manager_id, account_id
),
new_bus AS (
    INSERT INTO bus (academy_id, bus_no, plate_no, capacity, driver_count, escort_count, student_capacity, operable)
    SELECT :academy_id,
           'LOADPOS-' || run_tag.tag || '-' || series.i,
           'LOADPOS-' || run_tag.tag || '-' || series.i,
           20, 1, 1, 18, true
    FROM series, run_tag
    RETURNING id AS bus_id, bus_no,
        (regexp_match(bus_no, '-(\d+)$'))[1]::int AS i
),
new_route AS (
    INSERT INTO route (academy_id, bus_id, weekday, direction, name, active)
    SELECT :academy_id, new_bus.bus_id, weekday_today.wd, 'to_academy',
           'LOADPOS-ROUTE-' || new_bus.bus_no, true
    FROM new_bus, weekday_today
    RETURNING id AS route_id, bus_id
),
new_route_stop AS (
    INSERT INTO route_stop (route_id, stop_id, seq)
    SELECT new_route.route_id, target_stop.stop_id, 1
    FROM new_route, target_stop
    RETURNING route_id
),
-- moving 상태로 바로 심는다 — 배차 확정(§9)·운행 시작(§4.x) 흐름을 거치지 않고 위치 수신 처리량만
-- 재는 것이 이 시나리오의 목적이라, 중간 상태 전이는 이번 라운드 범위 밖으로 둔다(§5.6 은 L2 실행
-- 시점만 지정하고 상태 전이 재현까지 요구하지 않는다).
new_run AS (
    INSERT INTO run (academy_id, bus_id, schedule_id, service_date, direction,
                      depart_time, confirm_at, status, origin_name, destination_name,
                      confirmed_at, started_at, consecutive_failures)
    SELECT :academy_id, new_bus.bus_id, NULL, current_date, 'to_academy',
           now() + interval '30 minutes' - interval '40 minutes',
           now() - interval '40 minutes',
           'moving', 'LOADPOS-ORIGIN', 'LOADPOS-DEST',
           now() - interval '35 minutes', now() - interval '10 minutes', 0
    FROM new_bus
    RETURNING id AS run_id, bus_id
),
-- CTE 를 두 번 데이터 변경 없이 다시 참조할 수 있으므로(RETURNING 결과는 그 실행 안에서 고정),
-- i 는 new_bus 를 매개로만 옮긴다 — new_run·new_manager 어느 쪽도 자기 INSERT 대상 테이블을
-- RETURNING 안에서 재상관 조회하지 않는다(그 형태는 이 저장소에서 검증되지 않아 피한다).
new_assignment AS (
    INSERT INTO assignment (run_id, manager_id, role, assigned_at)
    SELECT new_run.run_id, new_manager.manager_id, 'driver', now() - interval '1 hour'
    FROM new_run
    JOIN new_bus ON new_bus.bus_id = new_run.bus_id
    JOIN new_account ON new_account.i = new_bus.i
    JOIN new_manager ON new_manager.account_id = new_account.account_id
    RETURNING run_id, manager_id
)
SELECT run_tag.tag, new_account.login_id, new_run.run_id
FROM new_account
JOIN new_bus ON new_bus.i = new_account.i
JOIN new_run ON new_run.bus_id = new_bus.bus_id
CROSS JOIN run_tag
ORDER BY new_account.i;

COMMIT;
