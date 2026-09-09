-- 시나리오 1 준비 — "동시 도래 폭주" (IMPLEMENTATION_PLAN §5.2 #1).
--
-- academy_id=1(db/migration-local 시드, 좌표 보유)을 재사용하고, 그 위에 N개의 새 bus+route+
-- route_stop+run 을 심는다. run.confirm_at 을 "방금 지난 시각"으로 박아 두면 RunConfirmationScheduler
-- 의 다음 틱(최대 30초, app.run.confirmation.poll-interval-ms)이 전부를 idle 도래분으로 집어간다.
--
-- 학생 명단은 만들지 않는다 — RunConfirmationService.confirmOne() → RouteComputationPipeline 은
-- 빈 roster(studentIds=[])를 유효한 입력으로 받아들인다(DailyStopResolver 가드, 직접 코드 확인).
-- route_stop 은 academy 1의 기존 stop 1개(첫 stop)를 그대로 재사용한다 — confirmOne 은 route_stop
-- 목록의 첫/끝 항목만 origin/destination 판정에 쓰므로 1-stop 노선으로 충분하다.
--
-- 실행 (재호출 시 매번 새 bus_no 로 심어 이전 회차와 충돌하지 않는다 — N=10→50→200 누적 실행 전제):
--   psql "postgresql://schoolbus:schoolbus@localhost:15432/schoolbus_load" -v n=10 -f scenario1_prep.sql
--
-- 필수 변수: n (심을 회차 수). 학원은 항상 1로 고정한다(academy_id 변수화는 이번 라운드 범위 밖 —
-- 학원 여러 곳을 동시에 쓰는 변형은 §5.2 표의 "학원 N곳"을 그대로 옮기려면 필요하지만, 이 좌석의
-- 시나리오 1 판정(도래→확정 지연 · 미확정 회차 수)은 총 도래 건수만으로 이미 성립한다).
\if :{?n}
\else
    \echo '변수 n 이 없다 — 예: psql -v n=10 -f scenario1_prep.sql'
    \quit
\endif

\set academy_id 1

BEGIN;

WITH series AS (
    SELECT generate_series(1, :n) AS i
),
run_tag AS (
    -- clock_timestamp() 는 트랜잭션 시작 시각(now())이 아니라 실제 호출 시각을 반환한다 —
    -- 반복 실행마다 값이 달라져야 bus_no UNIQUE(academy_id, bus_no) 충돌이 나지 않는다.
    SELECT to_char(clock_timestamp(), 'HH24MISSMS') AS tag
),
target_stop AS (
    SELECT id AS stop_id FROM stop WHERE academy_id = :academy_id ORDER BY id LIMIT 1
),
weekday_today AS (
    -- to_char(current_date, 'DY') 는 서버 로케일에 좌우된다 — RunConfirmationService 가 기대하는
    -- 3글자 소문자 요일 코드(mon..sun, Java DayOfWeek.name() 앞 3글자)를 로케일 무관하게 고정한다.
    SELECT (ARRAY['mon','tue','wed','thu','fri','sat','sun'])[extract(isodow FROM current_date)::int] AS wd
),
new_bus AS (
    INSERT INTO bus (academy_id, bus_no, plate_no, capacity, driver_count, escort_count, student_capacity, operable)
    SELECT :academy_id,
           'LOAD-' || run_tag.tag || '-' || series.i,
           'LOAD-' || run_tag.tag || '-' || series.i,
           20, 1, 1, 18, true
    FROM series, run_tag
    RETURNING id AS bus_id, bus_no
),
new_route AS (
    INSERT INTO route (academy_id, bus_id, weekday, direction, name, active)
    SELECT :academy_id, new_bus.bus_id, weekday_today.wd, 'to_academy',
           'LOAD-ROUTE-' || new_bus.bus_no, true
    FROM new_bus, weekday_today
    RETURNING id AS route_id, bus_id
),
new_route_stop AS (
    INSERT INTO route_stop (route_id, stop_id, seq)
    SELECT new_route.route_id, target_stop.stop_id, 1
    FROM new_route, target_stop
    RETURNING route_id
),
new_run AS (
    -- confirm_at = now() - 1초 → 이미 도래한 상태로 심는다.
    -- ck_run_confirm_at CHECK(confirm_at = depart_time - interval '30 minutes') 를 만족시키려면
    -- depart_time 도 같은 오프셋으로 함께 정해야 한다.
    INSERT INTO run (academy_id, bus_id, schedule_id, service_date, direction,
                      depart_time, confirm_at, status, origin_name, destination_name,
                      consecutive_failures)
    SELECT :academy_id, new_bus.bus_id, NULL, current_date, 'to_academy',
           (now() - interval '1 second') + interval '30 minutes',
           now() - interval '1 second',
           'idle', 'LOAD-ORIGIN', 'LOAD-DEST', 0
    FROM new_bus
    RETURNING id
)
SELECT
    (SELECT tag FROM run_tag) AS tag,
    (SELECT count(*) FROM new_bus) AS buses_created,
    (SELECT count(*) FROM new_route) AS routes_created,
    (SELECT count(*) FROM new_route_stop) AS route_stops_created,
    (SELECT count(*) FROM new_run) AS runs_created;

COMMIT;
