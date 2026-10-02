-- R0 — 목표 규모 시드 (부하 한계 측정 계획 §3 R0).
-- 학원 10 · 버스 100 · 학생 2,000 · 회차 200 을 한 트랜잭션으로 심는다.
--
-- 기존 시드(db/fixture V2)를 건드리지 않고 전부 새로 만든다 — 이름이 'LOADCAP-' 로
-- 시작하는 행만 이 스크립트의 산출물이라, 정리도 그 접두사 하나로 가른다.
--
-- 명단(roster)이 실제로 붙는 것이 이 시드의 요점이다. 확정 배치는 회차의 학생을 직접 알지 못하고
-- WeeklyAddressRepository.findDailyStopsByStopIds 로 "편성된 노선의 정차지를 그 요일·방향에 쓰는
-- 학생"을 거꾸로 찾는다(RunConfirmationService.confirmOne 직접 확인) — 그래서 weekly_address 는
-- verified=true · stop_id NOT NULL · weekday=오늘 · 방향 2종을 모두 갖춰야 한다. 2026-09-05 라운드의
-- scenario1_prep.sql 은 명단을 만들지 않아 빈 roster 로 쟀다. 이번 측정은 그 반대다.
--
-- 이름 규칙이 곧 외래키다(별도 매핑 표를 두지 않는다):
--   bus.bus_no  = 'LOADCAP-A{학원2자리}-B{버스2자리}'
--   stop.name   = <bus_no> || '-S{1..5}'
--   student.name= <bus_no> || '-U{1..20}'        학생 k → 정차지 ((k-1) % 5) + 1
--   manager.name= <bus_no> || '-DRV'
--   account     = lower(<bus_no>) || '-drv'      (비밀번호 평문 "password")
--
-- 회차는 idle 로 심고 confirm_at 을 미래(+6h·+8h)에 둔다 — RunConfirmationScheduler 가 먼저 집어가면
-- R3(혼합) 이 재현하려는 "동시 도래" 자체가 안 생긴다. R3 는 confirm_at 을 과거로 옮겨 촉발한다.
--
-- 실행:
--   docker exec -i school-bus-postgres-1 psql -U schoolbus -d schoolbus_load -q < sql/r0_capacity_seed.sql

\set n_academy 10
\set buses_per_academy 10
\set stops_per_route 5
\set students_per_bus 20
\set seed_password_hash '$2a$10$Noeszx0nzJUfNo4ubCD03eNfMVfMD9feMo04y/8DHiFLUBF.JZ/Fq'

BEGIN;

-- ① 학원 10곳. 좌표가 없으면 confirmOne 이 ACADEMY_COORDINATES_MISSING 으로 그 회차만 실패시킨다
--    (Ruling 190) — 전 학원에 좌표를 준다. 서울 남부에 0.02도씩 벌려 학원끼리 겹치지 않게 한다.
INSERT INTO academy (code, name, region, address, contact, status, lat, lng)
SELECT 'LOADCAP-A' || lpad(i::text, 2, '0'),
       'LOADCAP 학원 ' || i, '서울', 'LOADCAP 주소 ' || i, '02-0000-0000', 'active',
       37.45 + i * 0.02, 127.00 + i * 0.02
FROM generate_series(1, :n_academy) AS i;

INSERT INTO academy_setting (academy_id)
SELECT id FROM academy WHERE code LIKE 'LOADCAP-%';

-- ② 버스 100대. student_capacity = capacity - driver_count - escort_count 를 CHECK 가 강제한다.
INSERT INTO bus (academy_id, bus_no, plate_no, capacity, driver_count, escort_count, student_capacity, operable)
SELECT a.id,
       a.code || '-B' || lpad(b::text, 2, '0'),
       a.code || '-B' || lpad(b::text, 2, '0'),
       25, 1, 1, 23, true
FROM academy a
CROSS JOIN generate_series(1, :buses_per_academy) AS b
WHERE a.code LIKE 'LOADCAP-%';

-- ③ 승하차지 500곳(버스당 5곳). 학원 좌표 주변에 흩어 둔다 — 같은 좌표에 몰면 노선 계산의
--    정차지 순서 결정이 자명해져 계산 부하가 실제보다 가벼워진다.
INSERT INTO stop (academy_id, name, address, lat, lng)
SELECT b.academy_id,
       b.bus_no || '-S' || s,
       b.bus_no || '-S' || s || ' 주소',
       a.lat + 0.001 * s + 0.0005 * (b.id % 10),
       a.lng + 0.001 * s - 0.0005 * (b.id % 10)
FROM bus b
JOIN academy a ON a.id = b.academy_id
CROSS JOIN generate_series(1, :stops_per_route) AS s
WHERE b.bus_no LIKE 'LOADCAP-%';

-- ④ 고정 노선 200개(버스 × 방향 2). weekday 는 오늘 — 로케일에 좌우되는 to_char(...,'DY') 대신
--    isodow 로 3글자 소문자 코드를 고정한다(scenario1_prep.sql 과 같은 이유).
INSERT INTO route (academy_id, bus_id, weekday, direction, name, active)
SELECT b.academy_id, b.id,
       (ARRAY['mon','tue','wed','thu','fri','sat','sun'])[extract(isodow FROM current_date)::int],
       d.direction, b.bus_no || '-ROUTE-' || d.direction, true
FROM bus b
CROSS JOIN (VALUES ('to_academy'), ('from_academy')) AS d(direction)
WHERE b.bus_no LIKE 'LOADCAP-%';

INSERT INTO route_stop (route_id, stop_id, seq)
SELECT r.id, st.id, s
FROM route r
JOIN bus b ON b.id = r.bus_id
CROSS JOIN generate_series(1, :stops_per_route) AS s
JOIN stop st ON st.academy_id = b.academy_id AND st.name = b.bus_no || '-S' || s
WHERE b.bus_no LIKE 'LOADCAP-%' AND r.name LIKE 'LOADCAP-%';

-- ⑤ 기사 계정 100 + 운행인력 100. 위치 수신(POST /runs/{id}/position)은
--    account(driver) → manager(account_id) → assignment(run_id, role=driver) 사슬을 전부 요구한다.
INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status)
SELECT b.academy_id, lower(b.bus_no) || '-drv', :'seed_password_hash',
       b.bus_no || '-DRV', '010-1000-0000', 'driver', 'active'
FROM bus b WHERE b.bus_no LIKE 'LOADCAP-%';

INSERT INTO manager (academy_id, account_id, name, phone, role)
SELECT b.academy_id, ac.id, b.bus_no || '-DRV', '010-1000-0000', 'driver'
FROM bus b JOIN account ac ON ac.login_id = lower(b.bus_no) || '-drv'
WHERE b.bus_no LIKE 'LOADCAP-%';

-- ⑥ 학원 관계자 계정 10 — academy live 채널(/topic/academy/{id}/live)은 role=STAFF 만 구독한다
--    (StompAuthChannelInterceptor#authorizeAcademyChannel). 학원당 1명 정원은 조건부 UNIQUE 라
--    새 학원 10곳에 1명씩이면 걸리지 않는다.
INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status)
SELECT a.id, lower(a.code) || '-staff', :'seed_password_hash',
       a.code || '-STAFF', '010-2000-0000', 'staff', 'active'
FROM academy a WHERE a.code LIKE 'LOADCAP-%';

INSERT INTO academy_staff (academy_id, account_id, status)
SELECT a.id, ac.id, 'active'
FROM academy a JOIN account ac ON ac.login_id = lower(a.code) || '-staff'
WHERE a.code LIKE 'LOADCAP-%';

-- ⑦ 학생 2,000명(버스당 20).
INSERT INTO student (academy_id, name, student_phone, grade, can_go_alone)
SELECT b.academy_id, b.bus_no || '-U' || k, '010-3000-0000', '초3', false
FROM bus b
CROSS JOIN generate_series(1, :students_per_bus) AS k
WHERE b.bus_no LIKE 'LOADCAP-%';

-- ⑧ 요일별 주소 4,000행(학생 × 방향 2). verified=true · stop_id NOT NULL 이 아니면 명단 조회가
--    이 학생을 아예 못 본다(위 저장소 JPQL 의 WHERE 절).
INSERT INTO weekly_address (student_id, weekday, direction, address, lat, lng, verified, stop_id)
SELECT s.id,
       (ARRAY['mon','tue','wed','thu','fri','sat','sun'])[extract(isodow FROM current_date)::int],
       d.direction, st.name || ' 주소', st.lat, st.lng, true, st.id
FROM student s
CROSS JOIN (VALUES ('to_academy'), ('from_academy')) AS d(direction)
JOIN stop st ON st.academy_id = s.academy_id
            AND st.name = split_part(s.name, '-U', 1) || '-S'
                          || (((split_part(s.name, '-U', 2)::int - 1) % :stops_per_route) + 1)
WHERE s.name LIKE 'LOADCAP-%';

-- ⑨ 회차 200(버스 × 방향 2). ck_run_confirm_at 이 confirm_at = depart_time - 30분 을 강제한다.
INSERT INTO run (academy_id, bus_id, schedule_id, service_date, direction,
                 depart_time, confirm_at, status, origin_name, destination_name, consecutive_failures)
SELECT b.academy_id, b.id, NULL, current_date, d.direction,
       now() + d.offs, now() + d.offs - interval '30 minutes',
       'idle', 'LOADCAP-ORIGIN', 'LOADCAP-DEST', 0
FROM bus b
CROSS JOIN (VALUES ('to_academy', interval '6 hours'), ('from_academy', interval '8 hours')) AS d(direction, offs)
WHERE b.bus_no LIKE 'LOADCAP-%';

INSERT INTO assignment (run_id, manager_id, role, assigned_at)
SELECT r.id, m.id, 'driver', now()
FROM run r
JOIN bus b ON b.id = r.bus_id
JOIN manager m ON m.name = b.bus_no || '-DRV'
WHERE b.bus_no LIKE 'LOADCAP-%';

COMMIT;

-- 적재 확인 — 계획 §3 R0 의 멈추는 지점("데이터 적재 확인")이 이 표다.
SELECT 'academy' AS what, count(*) AS n FROM academy WHERE code LIKE 'LOADCAP-%'
UNION ALL SELECT 'bus', count(*) FROM bus WHERE bus_no LIKE 'LOADCAP-%'
UNION ALL SELECT 'stop', count(*) FROM stop WHERE name LIKE 'LOADCAP-%'
UNION ALL SELECT 'route', count(*) FROM route WHERE name LIKE 'LOADCAP-%'
UNION ALL SELECT 'route_stop', count(*) FROM route_stop rs JOIN route r ON r.id = rs.route_id WHERE r.name LIKE 'LOADCAP-%'
UNION ALL SELECT 'student', count(*) FROM student WHERE name LIKE 'LOADCAP-%'
UNION ALL SELECT 'weekly_address', count(*) FROM weekly_address wa JOIN student s ON s.id = wa.student_id WHERE s.name LIKE 'LOADCAP-%'
UNION ALL SELECT 'driver_account', count(*) FROM account WHERE login_id LIKE 'loadcap-%-drv'
UNION ALL SELECT 'staff_account', count(*) FROM account WHERE login_id LIKE 'loadcap-%-staff'
UNION ALL SELECT 'manager', count(*) FROM manager WHERE name LIKE 'LOADCAP-%'
UNION ALL SELECT 'run', count(*) FROM run r JOIN bus b ON b.id = r.bus_id WHERE b.bus_no LIKE 'LOADCAP-%'
UNION ALL SELECT 'assignment', count(*) FROM assignment a JOIN run r ON r.id = a.run_id JOIN bus b ON b.id = r.bus_id WHERE b.bus_no LIKE 'LOADCAP-%'
UNION ALL SELECT 'roster_per_run_min', min(c) FROM (
    SELECT count(*) AS c FROM run r
    JOIN bus b ON b.id = r.bus_id
    JOIN route rt ON rt.bus_id = b.id AND rt.direction = r.direction
    JOIN route_stop rs ON rs.route_id = rt.id
    JOIN weekly_address wa ON wa.stop_id = rs.stop_id AND wa.direction = r.direction AND wa.verified
    WHERE b.bus_no LIKE 'LOADCAP-%' GROUP BY r.id) q;
