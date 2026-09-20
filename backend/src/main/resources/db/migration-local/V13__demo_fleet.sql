-- 데모용 선단(3호차·4호차·5호차) — 2026-09-20 사용자 요청.
--
-- 왜 기존 버스(1·2호차)를 늘리지 않고 새로 만드나 — 기존 회차·노선·정차지는 계약 검사가
-- 값으로 붙들고 있다(예: 웹 realBackend 의 `run_id=6` 정원 초과, 승인 미리보기의 `run_id=2`).
-- 거기에 정차지를 더하면 그 검사들이 데모 사정으로 깨진다. **더하기만 하고 고치지 않는다.**
--
-- 이 파일이 만드는 것 — 버스 3대 각각에 **승하차지 10곳 · 학생 10명 · 등원 노선 1개 · 회차 1건**.
-- 회차는 `idle` 로 두고 `confirm_at` 을 과거로 둔다 — 그러면 30초 폴링(RunConfirmationScheduler)이
-- 기동 직후 확정 배치를 돌려 **네이버에서 실제 도로 경로를 받아** route_version.road_path 를 채운다.
-- 경로 좌표를 시드에 손으로 박지 않는 이유가 이것이다(10곳을 지나는 실제 경로는 손으로 못 만든다).
-- 그다음 운행 시작과 위치 송신은 `DemoRunSimulator`(local 프로파일 전용)가 맡는다.
--
-- ⚠ 좌표는 **전부 한강 남쪽**으로 잡았다. 회랑이 강을 가로지르면 네이버가 다리로 우회시켜
-- 데모 경로가 실제 통학 노선처럼 안 보인다.

-- 버스 3대 — 학생 10명을 태워야 하므로 student_capacity 를 넉넉히 둔다(2호차는 2석이라 못 쓴다).
INSERT INTO bus (id, academy_id, bus_no, plate_no, capacity, driver_count, escort_count,
                  student_capacity, operable, created_at, updated_at)
OVERRIDING SYSTEM VALUE
VALUES
    (10, 1, '3호차', '34다1010', 18, 1, 1, 16, true, now(), now()),
    (11, 1, '4호차', '34다1111', 18, 1, 1, 16, true, now(), now()),
    (12, 1, '5호차', '34다1212', 18, 1, 1, 16, true, now(), now());

-- 기사·동승자 계정 6개. `DemoRunSimulator` 가 운행 시작을 정식 서비스로 부르는데, 그 서비스가
-- "이 회차에 배치된 기사인가" 를 계정 기준으로 판정하므로 계정이 실재해야 한다.
INSERT INTO account (id, academy_id, login_id, password_hash, name, phone, email, role, status,
                      created_at, updated_at)
OVERRIDING SYSTEM VALUE
VALUES
    (100, 1, 'driverD3', '${seedPasswordHash}', '삼기사', '010-7000-0003', NULL, 'driver', 'active', now(), now()),
    (101, 1, 'driverD4', '${seedPasswordHash}', '사기사', '010-7000-0004', NULL, 'driver', 'active', now(), now()),
    (102, 1, 'driverD5', '${seedPasswordHash}', '오기사둘', '010-7000-0005', NULL, 'driver', 'active', now(), now()),
    (103, 1, 'escortD3', '${seedPasswordHash}', '삼동승', '010-7100-0003', NULL, 'escort', 'active', now(), now()),
    (104, 1, 'escortD4', '${seedPasswordHash}', '사동승', '010-7100-0004', NULL, 'escort', 'active', now(), now()),
    (105, 1, 'escortD5', '${seedPasswordHash}', '오동승', '010-7100-0005', NULL, 'escort', 'active', now(), now());

INSERT INTO manager (id, academy_id, account_id, name, phone, role, work_hours, created_at, updated_at)
OVERRIDING SYSTEM VALUE
SELECT a.id, 1, a.id, a.name, a.phone, a.role,
       '{"mon":[{"start":"07:00","end":"20:00"}],"tue":[{"start":"07:00","end":"20:00"}],'
       '"wed":[{"start":"07:00","end":"20:00"}],"thu":[{"start":"07:00","end":"20:00"}],'
       '"fri":[{"start":"07:00","end":"20:00"}],"sat":[{"start":"07:00","end":"20:00"}],'
       '"sun":[{"start":"07:00","end":"20:00"}]}'::jsonb,
       now(), now()
FROM account a WHERE a.id BETWEEN 100 AND 105;

-- 승하차지 30곳 — 버스마다 회랑 하나를 10등분해 고르게 놓는다.
-- id 배치: 3호차 100~109 · 4호차 110~119 · 5호차 120~129.
INSERT INTO stop (id, academy_id, name, address, lat, lng, created_at, updated_at)
OVERRIDING SYSTEM VALUE
SELECT 100 + c.ord * 10 + i - 1,
       1,
       c.bus_no || ' ' || i || '번 승하차지',
       '서울시 ' || c.area || ' ' || (i * 11) || '길',
       round((c.lat0 + (c.lat1 - c.lat0) * (i - 1) / 9.0)::numeric, 6),
       round((c.lng0 + (c.lng1 - c.lng0) * (i - 1) / 9.0)::numeric, 6),
       now(), now()
FROM (VALUES
        (0, '3호차', '영등포구', 37.5170, 126.9070, 37.5010, 127.0100),
        (1, '4호차', '관악구',   37.4780, 126.9510, 37.4930, 127.0180),
        (2, '5호차', '송파구',   37.5100, 127.1180, 37.5000, 127.0400)
     ) AS c(ord, bus_no, area, lat0, lng0, lat1, lng1)
CROSS JOIN generate_series(1, 10) AS i;

-- 학생 30명 — 승하차지 한 곳당 1명. id 를 승하차지와 같게 둬서 아래 주소 배정이 단순해진다.
INSERT INTO student (id, academy_id, account_id, name, birth_date, gender, grade, class_name,
                      student_phone, created_at, updated_at)
OVERRIDING SYSTEM VALUE
SELECT s.id, 1, NULL, '데모학생' || (s.id - 99), DATE '2014-05-05', 'male', '초4', 'D반', NULL, now(), now()
FROM stop s WHERE s.id BETWEEN 100 AND 129;

-- 요일별 주소 — 확정 배치가 "오늘 이 학생이 어느 승하차지인가" 를 이 표에서 읽는다.
-- 요일을 안 가리고 7일 전부 채운다(데모를 아무 날에나 띄울 수 있어야 한다).
INSERT INTO weekly_address (student_id, weekday, direction, address, lat, lng, verified, stop_id, updated_at)
SELECT s.id, w.d, dir.d, s.address, s.lat, s.lng, true, s.id, now()
FROM stop s
CROSS JOIN unnest(ARRAY['mon','tue','wed','thu','fri','sat','sun']) AS w(d)
CROSS JOIN unnest(ARRAY['to_academy','from_academy']) AS dir(d)
WHERE s.id BETWEEN 100 AND 129;

-- 고정 노선(등원) 3개 — 요일은 한국시간 오늘. UTC 로 뽑으면 한국 09:00 이전에 하루 어긋나
-- 확정 배치의 (학원·버스·요일·방향) 4중 일치가 노선을 못 찾는다(R22 에서 겪은 결함).
INSERT INTO route (id, academy_id, bus_id, weekday, direction, name, active, created_at, updated_at)
OVERRIDING SYSTEM VALUE
SELECT 100 + b.ord, 1, b.bus_id,
       (ARRAY['sun','mon','tue','wed','thu','fri','sat'])[extract(dow from (now() AT TIME ZONE 'Asia/Seoul'))::int + 1],
       'to_academy', b.bus_no || ' 본선(등원)', true, now(), now()
FROM (VALUES (0, 10, '3호차'), (1, 11, '4호차'), (2, 12, '5호차')) AS b(ord, bus_id, bus_no);

INSERT INTO route_stop (route_id, stop_id, seq)
SELECT 100 + b.ord, 100 + b.ord * 10 + i - 1, i
FROM (VALUES (0), (1), (2)) AS b(ord)
CROSS JOIN generate_series(1, 10) AS i;

INSERT INTO schedule (id, academy_id, bus_id, weekday, direction, depart_time, origin_name,
                       destination_name, est_duration_min, active, created_at, updated_at)
OVERRIDING SYSTEM VALUE
SELECT 100 + b.ord, 1, b.bus_id,
       (ARRAY['sun','mon','tue','wed','thu','fri','sat'])[extract(dow from (now() AT TIME ZONE 'Asia/Seoul'))::int + 1],
       'to_academy', TIME '08:30', b.bus_no || ' 집결지', '바래다학원 A', 40, true, now(), now()
FROM (VALUES (0, 10, '3호차'), (1, 11, '4호차'), (2, 12, '5호차')) AS b(ord, bus_id, bus_no);

-- 데모 회차 3건. `depart_time` 을 **지금**으로 두는 이유 — 운행 시작은 예정 시각 ±3분에서만
-- 허용되고(RunStartWindowPolicy), 시뮬레이터가 그 정식 서비스를 그대로 부르기 때문이다.
-- `confirm_at` 은 30분 전이라 확정 폴링이 첫 회에 바로 집어 간다.
INSERT INTO run (id, academy_id, bus_id, schedule_id, service_date, direction, depart_time, confirm_at,
                  status, origin_name, destination_name, est_duration_min, created_at, updated_at)
OVERRIDING SYSTEM VALUE
SELECT 100 + b.ord, 1, b.bus_id, 100 + b.ord,
       (now() AT TIME ZONE 'Asia/Seoul')::date, 'to_academy',
       now(), now() - interval '30 minutes',
       'idle', b.bus_no || ' 집결지', '바래다학원 A', 40, now(), now()
FROM (VALUES (0, 10, '3호차'), (1, 11, '4호차'), (2, 12, '5호차')) AS b(ord, bus_id, bus_no);

-- 기사·동승자 배치 — 기사가 없으면 시뮬레이터가 운행을 시작하지 못한다(배치 기사만 시작 가능).
INSERT INTO assignment (run_id, manager_id, role, assigned_at, assigned_by)
SELECT 100 + b.ord, 100 + b.ord, 'driver', now(), 2 FROM (VALUES (0), (1), (2)) AS b(ord)
UNION ALL
SELECT 100 + b.ord, 103 + b.ord, 'escort', now(), 2 FROM (VALUES (0), (1), (2)) AS b(ord);

SELECT setval(pg_get_serial_sequence('account', 'id'), (SELECT COALESCE(MAX(id), 1) FROM account));
SELECT setval(pg_get_serial_sequence('manager', 'id'), (SELECT COALESCE(MAX(id), 1) FROM manager));
SELECT setval(pg_get_serial_sequence('stop', 'id'), (SELECT COALESCE(MAX(id), 1) FROM stop));
SELECT setval(pg_get_serial_sequence('student', 'id'), (SELECT COALESCE(MAX(id), 1) FROM student));
SELECT setval(pg_get_serial_sequence('weekly_address', 'id'), (SELECT COALESCE(MAX(id), 1) FROM weekly_address));
SELECT setval(pg_get_serial_sequence('bus', 'id'), (SELECT COALESCE(MAX(id), 1) FROM bus));
SELECT setval(pg_get_serial_sequence('route', 'id'), (SELECT COALESCE(MAX(id), 1) FROM route));
SELECT setval(pg_get_serial_sequence('route_stop', 'id'), (SELECT COALESCE(MAX(id), 1) FROM route_stop));
SELECT setval(pg_get_serial_sequence('schedule', 'id'), (SELECT COALESCE(MAX(id), 1) FROM schedule));
SELECT setval(pg_get_serial_sequence('run', 'id'), (SELECT COALESCE(MAX(id), 1) FROM run));
SELECT setval(pg_get_serial_sequence('assignment', 'id'), (SELECT COALESCE(MAX(id), 1) FROM assignment));
