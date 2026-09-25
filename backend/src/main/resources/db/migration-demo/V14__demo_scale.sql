-- 데모 규모 확장 — 2026-09-23 사용자 요청.
--   ① 학원 10곳이 동시에 운행하는 모습 — 학원마다 버스 3대, 버스마다 학생 20명
--   ② 승인할 거리 — 가입 승인 대기 · 구간 변경(②구간) 승인 대기가 학원마다 여러 건
--   ③ 고정 노선마다 승하차지 15곳
--
-- 왜 별도 폴더(db/migration-demo)인가 — local 프로파일 실행에서만 스캔하고 테스트 DB 에서는 뺀다(build.gradle).
-- 전 학원을 세는 시험과 확정 배치 한 틱(50건)을 이 행들이 채워 버려서다. demo 프로파일(배포)에도 넣지 않는다 —
-- 시뮬레이터가 local 전용이라 거기서는 60건이 서 있기만 하고 네이버 호출만 소비한다.
--
-- 왜 새 학원인가 — V13 과 같은 이유다. 학원 A·B·C 의 행은 계약 검사가 값으로 붙들고 있어서 거기에
-- 더하면 데모 사정으로 검사가 깨진다. **학원 11~20 에만 더하고 기존 행은 고치지 않는다.**
--
-- 왜 승하차지 15곳인가 — 확정 배치가 네이버에 보내는 지점열은 등원 기준 "첫 승차지 + 승하차지 15 + 학원"
-- = 17 이고(RouteComputationPipeline.roadRequestOf), 이것이 호출 한 번의 상한
-- (`app.routing.map.max-waypoints: 17`)과 같다. 16곳부터는 호출이 둘로 나뉜다.
--
-- 식별자 배치 (a = 학원 1~10, k = 버스 0~29 = (a-1)*3 + b, g = 학생 0~599 = k*20 + (n-1))
--   학원 10+a · 관계자 계정 1000+a · 버스 1000+k · 기사 1100+k · 동승자 1200+k · 가입 신청 계정 1300+(a-1)*10+j
--   승하차지 10000+k*15+(i-1) · 학생·보호자·학부모 계정 20000+g
--   고정 노선·배차·회차 1000+k*2+d (d: 0=등원, 1=하원)
--
-- 로그인 아이디 (비밀번호는 V2 와 같다)
--   관계자 staff01~staff10 · 기사 driver011(학원 01 의 1호차)~ · 동승자 escort011~
--   학부모 parent01001(학원 01 의 학생 001)~parent10060
--
-- 회차 두 갈래
--   등원 30건 — 출발 = 지금. 확정 폴링이 곧바로 확정하고 `DemoRunSimulator` 가 출발시켜 30대가 함께 달린다.
--   하원 30건 — 출발 = 지금 + 29분. 확정 시각이 이미 지나 곧바로 확정되고, 그때부터 출발까지가
--     ②구간(관리자 승인 필요)이다. 승인 대기 요청은 이쪽에 붙인다.
--     ⚠ **승인 대기 건의 수명은 기동 후 약 29분이다** — 출발 시각이 되면 자동 거절된다(C-04).
--     다시 보려면 `down` → `up` 으로 시드를 새로 깐다.
--
-- ⚠ 좌표는 학원마다 강을 건너지 않는 쪽으로 회랑 3개를 잡았다(V13 과 같은 이유 — 강을 가로지르면
-- 네이버가 다리로 돌아가 통학 노선처럼 안 보인다). 승하차지 1번이 가장 멀고 15번이 학원에서 약 170m.

CREATE TEMP TABLE demo_academy (a int, short text, region text, district text, lat numeric, lng numeric)
    ON COMMIT DROP;
INSERT INTO demo_academy VALUES
    (1,  '목동', '서울', '서울시 양천구', 37.526300, 126.874600),
    (2,  '구로', '서울', '서울시 구로구', 37.485200, 126.901500),
    (3,  '신림', '서울', '서울시 관악구', 37.484200, 126.929700),
    (4,  '대치', '서울', '서울시 강남구', 37.494600, 127.062600),
    (5,  '잠실', '서울', '서울시 송파구', 37.513300, 127.100100),
    (6,  '강동', '서울', '서울시 강동구', 37.530100, 127.123800),
    (7,  '분당', '경기', '성남시 분당구', 37.383700, 127.123200),
    (8,  '평촌', '경기', '안양시 동안구', 37.389700, 126.950700),
    (9,  '부천', '경기', '부천시 원미구', 37.503500, 126.764000),
    (10, '일산', '경기', '고양시 일산동구', 37.658400, 126.770000);

-- 회랑 — 학원 좌표에서 1번 승하차지까지의 변위(위도·경도). 약 2.5km.
-- 하천·고속도로·시장을 가로지르는 회랑은 네이버가 그 지점에 닿으려 크게 돌아 노선이 30~58km 가 됐다
-- (2026-09-23 실측 — 목동 남·남동 · 잠실 남(가락시장) · 분당 서(탄천) · 평촌 남). 그 5개를 비켜 잡았다.
CREATE TEMP TABLE demo_corridor (a int, b int, dlat numeric, dlng numeric) ON COMMIT DROP;
INSERT INTO demo_corridor VALUES
    (1, 0,  0.000, -0.028), (1, 1,  0.018,  0.000), (1, 2,  0.008, -0.024),
    (2, 0,  0.000, -0.028), (2, 1, -0.020,  0.000), (2, 2,  0.018,  0.005),
    (3, 0,  0.003, -0.024), (3, 1,  0.020,  0.000), (3, 2,  0.000,  0.026),
    (4, 0,  0.000, -0.026), (4, 1,  0.018,  0.000), (4, 2, -0.012, -0.018),
    (5, 0, -0.008,  0.024), (5, 1,  0.000,  0.028), (5, 2, -0.015,  0.018),
    (6, 0,  0.000,  0.028), (6, 1, -0.012,  0.020), (6, 2,  0.012,  0.018),
    (7, 0,  0.022,  0.000), (7, 1, -0.020, -0.005), (7, 2,  0.018,  0.012),
    (8, 0,  0.018,  0.005), (8, 1,  0.005, -0.026), (8, 2, -0.010, -0.020),
    (9, 0,  0.000, -0.028), (9, 1, -0.020,  0.000), (9, 2,  0.004,  0.028),
    (10, 0, -0.004, -0.028), (10, 1, 0.020,  0.000), (10, 2, -0.008, 0.026);

CREATE TEMP TABLE demo_bus ON COMMIT DROP AS
SELECT (c.a - 1) * 3 + c.b AS k, c.a, c.b, 10 + c.a AS academy_id, 1000 + (c.a - 1) * 3 + c.b AS bus_id,
       (c.b + 1) || '호차' AS bus_no, lpad(c.a::text, 2, '0') AS aa,
       d.short, d.district, d.lat, d.lng, c.dlat, c.dlng
FROM demo_corridor c JOIN demo_academy d USING (a);

CREATE TEMP TABLE demo_student ON COMMIT DROP AS
SELECT x.g, x.k, x.a, x.b, x.n, x.academy_id, x.aa, 20000 + x.g AS id,
       10000 + x.k * 15 + (x.n - 1) % 15 AS stop_id,
       x.b * 20 + x.n AS no_in_academy,
       (ARRAY['김','이','박','최','정','강','조','윤','장','임','한','오','서','신','권','황','안','송','류','홍'])[1 + x.g % 20] AS surname,
       (ARRAY['민준','서연','도윤','서윤','시우','지우','하준','하은','주원','지아','지호','수아','예준','아린','유준',
              '지유','건우','윤서','우진','채원','선우','다은','연우','소율','현우','예린','정우','하린','승현','유나'])[1 + (x.g * 7) % 30] AS given,
       (ARRAY['정훈','미경','성민','수진','동현','은정','상훈','지영','재민','혜진'])[1 + x.g % 10] AS parent_given
FROM (SELECT b.k * 20 + n - 1 AS g, b.k, b.a, b.b, n, b.academy_id, b.aa
      FROM demo_bus b CROSS JOIN generate_series(1, 20) AS n) x;

-- ── 학원 · 관계자 ─────────────────────────────────────────────────────────────
INSERT INTO academy (id, code, name, region, address, contact, status, lat, lng, created_at, updated_at)
OVERRIDING SYSTEM VALUE
SELECT 10 + a, 'DEMO-' || lpad(a::text, 2, '0'), short || ' 데모학원', region, district || ' 학원로 ' || a,
       '02-7000-' || lpad(a::text, 4, '0'), 'active', lat, lng, now(), now()
FROM demo_academy;

INSERT INTO academy_setting (academy_id, no_show_wait_minutes, updated_at)
SELECT 10 + a, 3, now() FROM demo_academy;

INSERT INTO account (id, academy_id, login_id, password_hash, name, phone, role, status, created_at, updated_at)
OVERRIDING SYSTEM VALUE
SELECT 1000 + a, 10 + a, 'staff' || lpad(a::text, 2, '0'), '${seedPasswordHash}',
       (ARRAY['김','이','박','최','정','강','조','윤','장','임'])[a] || '운영',
       '010-8000-' || lpad(a::text, 4, '0'), 'staff', 'active', now(), now()
FROM demo_academy;

INSERT INTO academy_staff (academy_id, account_id, status, created_at, updated_at)
SELECT 10 + a, 1000 + a, 'active', now(), now() FROM demo_academy;

-- ── 버스 · 기사 · 동승자 ──────────────────────────────────────────────────────
-- 학생 20명을 태워야 하므로 학생석 23(= 정원 25 − 기사 1 − 동승자 1).
INSERT INTO bus (id, academy_id, bus_no, plate_no, capacity, driver_count, escort_count,
                  student_capacity, operable, created_at, updated_at)
OVERRIDING SYSTEM VALUE
SELECT bus_id, academy_id, bus_no, '7' || aa || '바' || lpad((k + 1)::text, 4, '0'), 25, 1, 1, 23, true, now(), now()
FROM demo_bus;

INSERT INTO account (id, academy_id, login_id, password_hash, name, phone, role, status, created_at, updated_at)
OVERRIDING SYSTEM VALUE
SELECT 1100 + k, academy_id, 'driver' || aa || (b + 1), '${seedPasswordHash}',
       (ARRAY['김','이','박','최','정','강','조','윤','장','임'])[1 + (k + 3) % 10]
           || (ARRAY['영수','광호','동수','기철','상철','만수','재호','성수','병철','용호'])[1 + k % 10],
       '010-8100-' || lpad((k + 1)::text, 4, '0'), 'driver', 'active', now(), now()
FROM demo_bus
UNION ALL
SELECT 1200 + k, academy_id, 'escort' || aa || (b + 1), '${seedPasswordHash}',
       (ARRAY['한','오','서','신','권','황','안','송','류','홍'])[1 + (k + 7) % 10]
           || (ARRAY['미숙','정희','순자','영미','경자','혜숙','말순','옥순','명희','숙자'])[1 + k % 10],
       '010-8200-' || lpad((k + 1)::text, 4, '0'), 'escort', 'active', now(), now()
FROM demo_bus;

-- 근무 시간은 V13 과 같다 — 하루 종일(07~20시)이라 배치 충돌 경고가 데모를 가리지 않는다.
INSERT INTO manager (id, academy_id, account_id, name, phone, role, work_hours, created_at, updated_at)
OVERRIDING SYSTEM VALUE
SELECT a.id, a.academy_id, a.id, a.name, a.phone, a.role,
       '{"mon":[{"start":"07:00","end":"20:00"}],"tue":[{"start":"07:00","end":"20:00"}],'
       '"wed":[{"start":"07:00","end":"20:00"}],"thu":[{"start":"07:00","end":"20:00"}],'
       '"fri":[{"start":"07:00","end":"20:00"}],"sat":[{"start":"07:00","end":"20:00"}],'
       '"sun":[{"start":"07:00","end":"20:00"}]}'::jsonb,
       now(), now()
FROM account a WHERE a.id BETWEEN 1100 AND 1229;

-- ── 승하차지 · 학생 · 보호자 ──────────────────────────────────────────────────
-- 버스마다 15곳. 1번이 회랑 끝(가장 멂), 15번이 학원 쪽 끝이다.
INSERT INTO stop (id, academy_id, name, address, lat, lng, created_at, updated_at)
OVERRIDING SYSTEM VALUE
SELECT 10000 + b.k * 15 + i - 1, b.academy_id,
       b.short || ' ' || b.bus_no || ' ' || lpad(i::text, 2, '0') || '번 승하차지',
       b.district || ' 데모로 ' || ((b.b + 1) * 100 + i) || '길',
       round(b.lat + b.dlat * (16 - i) / 15.0, 6), round(b.lng + b.dlng * (16 - i) / 15.0, 6),
       now(), now()
FROM demo_bus b CROSS JOIN generate_series(1, 15) AS i;

-- 학생 20명이 승하차지 15곳을 나눠 쓴다 — 1~5번 승하차지에는 2명씩 선다(한 곳에서 여럿이 타는 경우).
INSERT INTO student (id, academy_id, account_id, name, birth_date, gender, grade, class_name,
                      student_phone, created_at, updated_at)
OVERRIDING SYSTEM VALUE
SELECT id, academy_id, NULL, surname || given, DATE '2013-03-01' + (g % 700),
       CASE WHEN n % 2 = 0 THEN 'female' ELSE 'male' END, '초' || (3 + n % 4), (b + 1) || '호차반',
       NULL, now(), now()
FROM demo_student;

INSERT INTO weekly_address (student_id, weekday, direction, address, lat, lng, verified, stop_id, updated_at)
SELECT s.id, w.d, dir.d, st.address, st.lat, st.lng, true, st.id, now()
FROM demo_student s
JOIN stop st ON st.id = s.stop_id
CROSS JOIN unnest(ARRAY['mon','tue','wed','thu','fri','sat','sun']) AS w(d)
CROSS JOIN unnest(ARRAY['to_academy','from_academy']) AS dir(d);

-- 학생마다 학부모 계정 1개 — 구간 변경 요청의 요청자이자, 학부모 앱으로 데모 학생을 볼 입구다.
INSERT INTO account (id, academy_id, login_id, password_hash, name, phone, role, status, created_at, updated_at)
OVERRIDING SYSTEM VALUE
SELECT id, academy_id, 'parent' || aa || lpad(no_in_academy::text, 3, '0'), '${seedPasswordHash}',
       surname || parent_given, '010-83' || aa || '-' || lpad(no_in_academy::text, 4, '0'),
       'parent', 'active', now(), now()
FROM demo_student;

INSERT INTO guardian (id, academy_id, account_id, name, phone, created_at, updated_at)
OVERRIDING SYSTEM VALUE
SELECT s.id, s.academy_id, s.id, a.name, a.phone, now(), now()
FROM demo_student s JOIN account a ON a.id = s.id;

INSERT INTO guardian_student (guardian_id, student_id, linked_at)
SELECT id, id, now() - interval '30 days' FROM demo_student;

INSERT INTO notification_setting (account_id, arrive, boarding, no_show, updated_at)
SELECT id, true, true, true, now() FROM demo_student;

-- ── 고정 노선 · 배차 · 회차 ───────────────────────────────────────────────────
-- 요일은 한국시간 오늘(V13 과 같은 이유 — UTC 요일은 한국 09:00 이전에 하루 어긋난다).
CREATE TEMP TABLE demo_leg ON COMMIT DROP AS
SELECT b.*, d.d, 1000 + b.k * 2 + d.d AS leg_id,
       CASE d.d WHEN 0 THEN 'to_academy' ELSE 'from_academy' END AS direction,
       (ARRAY['sun','mon','tue','wed','thu','fri','sat'])[extract(dow from (now() AT TIME ZONE 'Asia/Seoul'))::int + 1] AS weekday
FROM demo_bus b CROSS JOIN (VALUES (0), (1)) AS d(d);

INSERT INTO route (id, academy_id, bus_id, weekday, direction, name, active, created_at, updated_at)
OVERRIDING SYSTEM VALUE
SELECT leg_id, academy_id, bus_id, weekday, direction,
       bus_no || CASE d WHEN 0 THEN ' 등원' ELSE ' 하원' END, true, now(), now()
FROM demo_leg;

-- 등원은 먼 곳부터 태워 학원으로(1→15), 하원은 학원에서 가까운 곳부터 내린다(15→1).
INSERT INTO route_stop (route_id, stop_id, seq)
SELECT l.leg_id, 10000 + l.k * 15 + (CASE l.d WHEN 0 THEN i ELSE 16 - i END) - 1, i
FROM demo_leg l CROSS JOIN generate_series(1, 15) AS i;

INSERT INTO schedule (id, academy_id, bus_id, weekday, direction, depart_time, origin_name,
                       destination_name, est_duration_min, active, created_at, updated_at)
OVERRIDING SYSTEM VALUE
SELECT l.leg_id, l.academy_id, l.bus_id, l.weekday, l.direction,
       CASE l.d WHEN 0 THEN TIME '08:30' ELSE TIME '17:30' END,
       CASE l.d WHEN 0 THEN l.bus_no || ' 첫 승차지' ELSE l.short || ' 데모학원' END,
       CASE l.d WHEN 0 THEN l.short || ' 데모학원' ELSE l.bus_no || ' 마지막 하차지' END,
       40, true, now(), now()
FROM demo_leg l;

-- ⚠ 한계(BR-139, Ruling 346) — V2 와 같은 식이다: `service_date` 는 적용 시각의 한국 날짜,
-- `depart_time` 은 적용 시각 기준 상대값. 이 시드는 오프셋이 최대 29분이라 자정 근접(23:31~
-- 23:59 KST) 적용일 때만 두 값의 한국 날짜가 갈린다 — V2 만큼 넓은 창은 아니지만 근거는 같다.
INSERT INTO run (id, academy_id, bus_id, schedule_id, service_date, direction, depart_time, confirm_at,
                  status, origin_name, destination_name, est_duration_min, created_at, updated_at)
OVERRIDING SYSTEM VALUE
SELECT l.leg_id, l.academy_id, l.bus_id, l.leg_id, (now() AT TIME ZONE 'Asia/Seoul')::date, l.direction,
       now() + l.d * interval '29 minutes', now() + l.d * interval '29 minutes' - interval '30 minutes',
       'idle',
       CASE l.d WHEN 0 THEN l.bus_no || ' 첫 승차지' ELSE l.short || ' 데모학원' END,
       CASE l.d WHEN 0 THEN l.short || ' 데모학원' ELSE l.bus_no || ' 마지막 하차지' END,
       40, now(), now()
FROM demo_leg l;

INSERT INTO assignment (run_id, manager_id, role, assigned_at, assigned_by)
SELECT leg_id, 1100 + k, 'driver', now(), 1000 + a FROM demo_leg
UNION ALL
SELECT leg_id, 1200 + k, 'escort', now(), 1000 + a FROM demo_leg;

-- ── 가입 승인 ─────────────────────────────────────────────────────────────────
-- 학원마다: 대기 — 학부모 3 · 학생 2(관계자가 승인) · 관계자 1(메인 관리자가 승인), 거절 이력 — 학부모 1,
-- 승인 이력 — 그 학원 관계자 본인. 학생 승인은 명부의 학생과 잇는 것이 필수라(AUTH-11) 계정 없는
-- 데모 학생 60명 중 아무나 골라 이으면 된다.
CREATE TEMP TABLE demo_applicant ON COMMIT DROP AS
SELECT 1300 + (d.a - 1) * 10 + j.j AS id, d.a, 10 + d.a AS academy_id, lpad(d.a::text, 2, '0') AS aa, j.j,
       j.role, j.approver, j.status, j.suffix
FROM demo_academy d
CROSS JOIN (VALUES
    (1, 'parent',  'staff',        'pending',  'p1'),
    (2, 'parent',  'staff',        'pending',  'p2'),
    (3, 'parent',  'staff',        'pending',  'p3'),
    (4, 'student', 'staff',        'pending',  's1'),
    (5, 'student', 'staff',        'pending',  's2'),
    (6, 'staff',   'system_admin', 'pending',  't1'),
    (7, 'parent',  'staff',        'rejected', 'r1')
) AS j(j, role, approver, status, suffix);

INSERT INTO account (id, academy_id, login_id, password_hash, name, phone, role, status, created_at, updated_at)
OVERRIDING SYSTEM VALUE
SELECT id, academy_id, 'apply' || aa || suffix, '${seedPasswordHash}',
       (ARRAY['문','배','백','허','유','남','심','노','하','곽'])[1 + (a + j) % 10]
           || (ARRAY['가은','태윤','서준','나연','시윤','민서','준서'])[j],
       '010-84' || aa || '-' || lpad(j::text, 4, '0'), role, status, now(), now()
FROM demo_applicant;

INSERT INTO signup_request (account_id, academy_id, requested_role, approver_type, status,
                             requested_at, decided_by, decided_at, reject_reason)
SELECT id, academy_id, role, approver, status, now() - (j * 5 + a) * interval '1 hour',
       CASE status WHEN 'rejected' THEN 1000 + a END,
       CASE status WHEN 'rejected' THEN now() - interval '1 hour' END,
       CASE status WHEN 'rejected' THEN '재원생 명부에 없는 이름' END
FROM demo_applicant
UNION ALL
SELECT 1000 + a, 10 + a, 'staff', 'system_admin', 'accepted', now() - interval '30 days', 1,
       now() - interval '29 days', NULL
FROM demo_academy;

-- ── 구간 변경 승인 ────────────────────────────────────────────────────────────
-- 대기 — 하원 회차마다 2건(승하차지 이동 1 · 탑승 취소 1) = 학원마다 6건. ②구간 요청은 접수될 때 회차당
-- 1회 한도를 소비하므로(ChangeRequestStore.applyApprovalRequired) 탑승 의사 행에 소비 흔적을 함께 남긴다 —
-- 없으면 승인·자동 거절이 되돌릴 한도를 못 찾는다.
-- 이력 — 등원 회차마다 거절 1 · 자동 거절 1. 승인 이력은 넣지 않는다 — 승인된 이동은 확정 배치가 노선에
-- 반영하므로(RunConfirmationService.confirmOne) 넣는 순간 데모 노선이 바뀐다.
INSERT INTO change_request (academy_id, run_id, student_id, requested_by, source, type, new_address, new_lat,
                             new_lng, new_stop_id, reason, stop_removed, status, reject_reason, window_segment,
                             requested_at, deadline_at, decided_by, decided_at)
SELECT s.academy_id, r.id, s.id, s.id, x.source, x.type,
       CASE x.type WHEN 'relocate' THEN st.address END,
       CASE x.type WHEN 'relocate' THEN st.lat END,
       CASE x.type WHEN 'relocate' THEN st.lng END,
       CASE x.type WHEN 'relocate' THEN st.id END,
       x.reason, false, x.status,
       CASE x.status WHEN 'rejected' THEN '출발 직전이라 노선을 바꿀 수 없습니다' END,
       2, r.depart_time - x.ago, r.depart_time,
       CASE x.status WHEN 'rejected' THEN 1000 + s.a END,
       CASE x.status WHEN 'rejected' THEN r.depart_time - x.ago + interval '3 minutes'
                     WHEN 'auto_rejected' THEN r.depart_time END
FROM demo_student s
-- `ago` = 출발 몇 분 전에 접수했나. ②구간은 출발 30분 전부터라 그 안쪽이어야 하고, 하원 회차(출발 = 지금 + 29분)
-- 의 대기 건은 29분보다 길어야 접수 시각이 미래로 가지 않는다.
JOIN (VALUES
    (3,  1, 'change_request', 'relocate', 'pending',       '오늘은 할머니 댁에서 하원합니다', interval '29 minutes 40 seconds'),
    (8,  1, 'intent',         'cancel',   'pending',       NULL,                             interval '29 minutes 20 seconds'),
    (5,  0, 'change_request', 'relocate', 'rejected',      '친구 집에서 승차합니다',          interval '20 minutes'),
    (11, 0, 'intent',         'cancel',   'auto_rejected', NULL,                             interval '5 minutes')
) AS x(n, d, source, type, status, reason, ago) ON x.n = s.n
JOIN run r ON r.id = 1000 + s.k * 2 + x.d
JOIN stop st ON st.id = s.stop_id + 1;

INSERT INTO boarding_intent (run_id, student_id, riding, change_used_count)
SELECT run_id, student_id, true, 1 FROM change_request
WHERE status = 'pending' AND academy_id BETWEEN 11 AND 20;

-- ── 운행 리포트(현장 예외 보고) 10건 — 2026-09-23 사용자 요청 ─────────────────────────────────
-- 목동 학원(11)만. "보호자 부재" 는 그 회차의 탑승자(run_rider)를 가리켜야 하는데, 오늘 회차의 탑승자는 기동 뒤
-- 확정 배치가 만든다(시드가 id 를 미리 알 수 없다). 그래서 **어제 끝난 하원 회차 3건**을 탑승자까지 넣어 두고
-- 보고는 그 회차에 단다. 보호자 부재는 하원에서 생기는 일이다(하차지에 보호자가 없음).
INSERT INTO run (id, academy_id, bus_id, schedule_id, service_date, direction, depart_time, confirm_at, status,
                  origin_name, destination_name, est_duration_min, confirmed_at, started_at, finished_at,
                  created_at, updated_at)
OVERRIDING SYSTEM VALUE
SELECT 1100 + b.b, b.academy_id, b.bus_id, NULL, (now() AT TIME ZONE 'Asia/Seoul')::date - 1, 'from_academy',
       x.depart, x.depart - interval '30 minutes', 'finished', b.short || ' 데모학원', b.bus_no || ' 마지막 하차지',
       40, x.depart - interval '30 minutes', x.depart, x.depart + interval '45 minutes', now(), now()
FROM demo_bus b
CROSS JOIN LATERAL (SELECT (((now() AT TIME ZONE 'Asia/Seoul')::date - 1) + TIME '17:30') AT TIME ZONE 'Asia/Seoul' AS depart) x
WHERE b.a = 1;

INSERT INTO assignment (run_id, manager_id, role, assigned_at, assigned_by)
SELECT 1100 + b, 1100 + k, 'driver', now(), 1001 FROM demo_bus WHERE a = 1
UNION ALL
SELECT 1100 + b, 1200 + k, 'escort', now(), 1001 FROM demo_bus WHERE a = 1;

-- 탑승자 — 대부분 하차 완료, 번호 7·14 는 결석(탑승 의사 취소), 번호 19 는 미승차.
INSERT INTO run_rider (run_id, student_id, stop_id, status, boarded_at, alighted_at)
SELECT 1100 + s.b, s.id, s.stop_id,
       CASE WHEN s.n IN (7, 14) THEN 'absent' WHEN s.n = 19 THEN 'no_show' ELSE 'alighted' END,
       CASE WHEN s.n IN (7, 14, 19) THEN NULL ELSE r.started_at + interval '2 minutes' END,
       CASE WHEN s.n IN (7, 14, 19) THEN NULL ELSE r.started_at + (s.n * 2) * interval '1 minute' END
FROM demo_student s JOIN run r ON r.id = 1100 + s.b
WHERE s.a = 1;

INSERT INTO exception_report (academy_id, run_id, run_rider_id, type, memo, reported_by, reported_at)
SELECT 11, x.run_id,
       (SELECT rr.id FROM run_rider rr WHERE rr.run_id = x.run_id AND rr.student_id = x.student_id),
       x.type, x.memo, x.reporter, x.at
FROM (VALUES
    (1100, 20002, 'guardian_absent', '하차지에 보호자 없음 — 5분 대기 후 학원으로 복귀, 보호자 통화 후 인계', 1200, now() - interval '1 day' + interval '3 minutes'),
    (1100, 20010, 'guardian_absent', '보호자 대신 조부모 마중 — 사전 연락 없어 신분 확인 후 인계', 1200, now() - interval '1 day' + interval '9 minutes'),
    (1101, 20025, 'guardian_absent', '보호자 부재, 전화 연결 안 됨 — 학원 관계자에게 인계', 1201, now() - interval '1 day' + interval '12 minutes'),
    (1102, 20047, 'guardian_absent', '보호자 10분 늦게 도착 — 차량 안에서 대기 후 인계', 1202, now() - interval '1 day' + interval '15 minutes'),
    (1100, NULL,  'road_block',      '목동서로 공사로 1차로 통제 — 우회로 약 6분 지연', 1100, now() - interval '1 day' + interval '5 minutes'),
    (1101, NULL,  'road_block',      '신정네거리 사고 처리 중 — 우회, 도착 예정 8분 지연', 1101, now() - interval '1 day' + interval '18 minutes'),
    (1102, NULL,  'vehicle_issue',   '뒷좌석 안전벨트 버클 고장 — 해당 좌석 비우고 운행, 정비 요청', 1102, now() - interval '1 day' + interval '1 minute'),
    (1000, NULL,  'vehicle_issue',   '타이어 공기압 경고등 점등 — 운행 후 점검 예정', 1100, now() - interval '20 minutes'),
    (1002, NULL,  'etc',             '승차 중 학생 가방 끈이 문에 걸림 — 즉시 조치, 부상 없음', 1201, now() - interval '12 minutes'),
    (1004, NULL,  'etc',             '승하차지 앞 불법 주차로 정차 위치 20m 이동', 1102, now() - interval '5 minutes')
) AS x(run_id, student_id, type, memo, reporter, at);

SELECT setval(pg_get_serial_sequence('academy', 'id'), (SELECT COALESCE(MAX(id), 1) FROM academy));
SELECT setval(pg_get_serial_sequence('account', 'id'), (SELECT COALESCE(MAX(id), 1) FROM account));
SELECT setval(pg_get_serial_sequence('bus', 'id'), (SELECT COALESCE(MAX(id), 1) FROM bus));
SELECT setval(pg_get_serial_sequence('manager', 'id'), (SELECT COALESCE(MAX(id), 1) FROM manager));
SELECT setval(pg_get_serial_sequence('stop', 'id'), (SELECT COALESCE(MAX(id), 1) FROM stop));
SELECT setval(pg_get_serial_sequence('student', 'id'), (SELECT COALESCE(MAX(id), 1) FROM student));
SELECT setval(pg_get_serial_sequence('guardian', 'id'), (SELECT COALESCE(MAX(id), 1) FROM guardian));
SELECT setval(pg_get_serial_sequence('route', 'id'), (SELECT COALESCE(MAX(id), 1) FROM route));
SELECT setval(pg_get_serial_sequence('schedule', 'id'), (SELECT COALESCE(MAX(id), 1) FROM schedule));
SELECT setval(pg_get_serial_sequence('run', 'id'), (SELECT COALESCE(MAX(id), 1) FROM run));
SELECT setval(pg_get_serial_sequence('run_rider', 'id'), (SELECT COALESCE(MAX(id), 1) FROM run_rider));
