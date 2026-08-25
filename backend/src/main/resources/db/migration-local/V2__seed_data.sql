-- 로컬·데모 전용 데모 시드 — docs/IMPLEMENTATION_PLAN.md §3.4(정본)를 따라 39개 테이블에 채운다.
-- 이 위치(db/migration-local)는 local·demo 프로파일에서만 스캔되므로 prod 에는 적용되지 않는다.
--
-- ── 판단 원칙 ──────────────────────────────────────────────────────────────
-- 1) 성공 판단 기준은 행 개수가 아니라 docs/USER_FLOWS.md 의 흐름을 Swagger 로 끝까지 밟을 수
--    있는가다. 역할 6종·상태 4종 계정, 3구간(idle/confirmed/moving+finished) 회차, 확정 노선
--    버전 히스토리, 승하차 상태 5종, 변경요청 4종, 노쇼·비상 각 1건을 실제로 채운다.
-- 2) 시간 값은 전부 now() 상대식이다 — 절대 리터럴을 쓰면 재기동 다음날부터 "당일" 조회가 빈다.
--    Flyway 는 이 스크립트를 하나의 트랜잭션으로 실행하므로 now() 는 스크립트 전체에서 같은
--    값을 반환한다(clock_timestamp() 와 달리 트랜잭션 시작 시각 고정) — 같은 now() 식을 여러
--    컬럼에 반복해도 서로 어긋나지 않는다.
-- 3) 스키마의 identity 컬럼(GENERATED ALWAYS)에 명시 PK 를 넣어야 하므로 OVERRIDING SYSTEM VALUE
--    를 쓰고, 파일 맨 끝에서 각 테이블의 시퀀스를 MAX(id) 이후로 setval 한다 — 그러지 않으면
--    앱이 새로 INSERT 할 때 여기서 쓴 PK 와 충돌한다.
-- 4) academy_setting(PK=academy_id)·confirmed_route(PK=run_id)·notification_setting(PK=account_id)
--    3개 테이블은 identity 가 아니라 부모 PK 를 그대로 쓰므로 OVERRIDING SYSTEM VALUE 가 필요 없다.
-- 5) 학원 B 는 계정만이 아니라 학생·보호자·버스·회차·확정 노선까지 독립된 데이터 계통을 갖는다
--    — 그래야 "A 계정으로 B 데이터 조회 시 공집합" 격리 검증이 실제로 무언가를 격리한 것이 된다.

-- ── 그룹① 학원 · 계정 · 권한 ─────────────────────────────────────────────────
-- 학원 A(운영중, 메인 데모) · B(운영중, 격리 검증용 독립 계통) · C(운영정지, 목록 필터 데모)
INSERT INTO academy (id, code, name, region, contact, status, created_at, updated_at)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 'BARAEDA-A', '바래다학원 A', '서울', '02-1234-5678', 'active', now(), now()),
    (2, 'BARAEDA-B', '바래다학원 B', '경기', '031-2345-6789', 'active', now(), now()),
    (3, 'BARAEDA-C', '바래다학원 C', '인천', '032-3456-7890', 'inactive', now(), now());

INSERT INTO academy_setting (academy_id, no_show_wait_minutes, updated_at)
VALUES
    (1, 3, now()),
    (2, 5, now()),
    (3, 3, now());

-- 계정: 역할 6종(parent/student/driver/escort/staff/system_admin) × 상태 4종을 전수 시연한다.
-- system_admin·escort 는 §3.4 기재상 active 외 상태를 요구하지 않는다(판단: 미기재분).
INSERT INTO account (id, academy_id, login_id, password_hash, name, phone, role, status,
                      failed_attempts, blocked_at, block_reason, created_at, updated_at)
OVERRIDING SYSTEM VALUE
VALUES
    (1, NULL, 'sysadmin', '${seedPasswordHash}', '시스템관리자', '010-0000-0001', 'system_admin', 'active', 0, NULL, NULL, now(), now()),
    (2, 1, 'staffA', '${seedPasswordHash}', '김운영', '010-0000-0002', 'staff', 'active', 0, NULL, NULL, now(), now()),
    (3, 2, 'staffB', '${seedPasswordHash}', '이운영', '010-0000-0003', 'staff', 'active', 0, NULL, NULL, now(), now()),
    (4, 1, 'staffPending', '${seedPasswordHash}', '박대기', '010-0000-0004', 'staff', 'pending', 0, NULL, NULL, now(), now()),
    (5, 1, 'parentA1', '${seedPasswordHash}', '최부모', '010-1000-0001', 'parent', 'active', 0, NULL, NULL, now(), now()),
    (6, 1, 'parentA2', '${seedPasswordHash}', '정부모', '010-1000-0002', 'parent', 'active', 0, NULL, NULL, now(), now()),
    (7, 1, 'parentA3', '${seedPasswordHash}', '한부모', '010-1000-0003', 'parent', 'active', 0, NULL, NULL, now(), now()),
    (8, 1, 'parentPending', '${seedPasswordHash}', '조대기', '010-1000-0004', 'parent', 'pending', 0, NULL, NULL, now(), now()),
    (9, 2, 'parentB1', '${seedPasswordHash}', '윤부모', '010-2000-0001', 'parent', 'active', 0, NULL, NULL, now(), now()),
    (10, 1, 'studentA4', '${seedPasswordHash}', '이하늘', '010-3000-0004', 'student', 'active', 0, NULL, NULL, now(), now()),
    (11, 1, 'studentRejected', '${seedPasswordHash}', '거절학생', '010-3000-0099', 'student', 'rejected', 0, NULL, NULL, now(), now()),
    (12, 2, 'studentB1', '${seedPasswordHash}', '정다은', '010-4000-0001', 'student', 'active', 0, NULL, NULL, now(), now()),
    (13, 1, 'driverA1', '${seedPasswordHash}', '강기사', '010-5000-0001', 'driver', 'active', 0, NULL, NULL, now(), now()),
    (14, 1, 'driverA2', '${seedPasswordHash}', '오기사', '010-5000-0002', 'driver', 'active', 0, NULL, NULL, now(), now()),
    (15, 1, 'driverBlocked', '${seedPasswordHash}', '차단기사', '010-5000-0099', 'driver', 'blocked', 5, now() - interval '10 minutes', '연속 로그인 실패 5회', now(), now()),
    (16, 2, 'driverB1', '${seedPasswordHash}', '남기사', '010-6000-0001', 'driver', 'active', 0, NULL, NULL, now(), now()),
    (17, 1, 'escortA1', '${seedPasswordHash}', '서동승', '010-7000-0001', 'escort', 'active', 0, NULL, NULL, now(), now()),
    (18, 1, 'escortA2', '${seedPasswordHash}', '문동승', '010-7000-0002', 'escort', 'active', 0, NULL, NULL, now(), now()),
    (19, 2, 'escortB1', '${seedPasswordHash}', '류동승', '010-8000-0001', 'escort', 'active', 0, NULL, NULL, now(), now());

-- 가입 요청: 대기 2건(staff·parent) + 거절 1건(student, 거절 사유 보유) — SIGNUP-01~04 화면.
INSERT INTO signup_request (id, account_id, academy_id, requested_role, approver_type, status,
                             requested_at, decided_by, decided_at, reject_reason)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 4, 1, 'staff', 'system_admin', 'pending', now() - interval '2 days', NULL, NULL, NULL),
    (2, 8, 1, 'parent', 'staff', 'pending', now() - interval '1 day', NULL, NULL, NULL),
    (3, 11, 1, 'student', 'staff', 'rejected', now() - interval '3 days', 2, now() - interval '2 days', '재학증명서 미제출');

INSERT INTO academy_staff (id, academy_id, account_id, status, created_at, updated_at)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 1, 2, 'active', now(), now()),
    (2, 2, 3, 'active', now(), now());

INSERT INTO system_admin (id, account_id, created_at)
OVERRIDING SYSTEM VALUE
VALUES (1, 1, now());

-- refresh_token: 0행 — 런타임 로그인 시 서버가 발급하는 값이라 시드가 미리 채울 대상이 아니다(판단).

-- ── 그룹② 학생 · 보호자 · 주소 ────────────────────────────────────────────────
-- 승하차지 마스터: A 4곳(학생 5명이 나눠 쓰며 1명은 이전 승인으로 정류장이 바뀐 상태) + B 1곳.
INSERT INTO stop (id, academy_id, name, address, lat, lng, created_at, updated_at)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 1, '중앙로 스타빌딩 앞', '서울시 중앙로 1', 37.566500, 126.978000, now(), now()),
    (2, 1, '한빛아파트 정문', '서울시 한빛로 20', 37.567500, 126.979000, now(), now()),
    (3, 1, '그린빌라 입구', '서울시 그린로 33', 37.568500, 126.980000, now(), now()),
    (4, 1, '코스모스마트 앞', '서울시 코스모스로 5', 37.569500, 126.981000, now(), now()),
    (5, 2, 'B학원 앞 정류장', '경기도 B로 1', 37.400000, 127.100000, now(), now());

-- 학생 5명(A) + 1명(B). S1·S2 는 같은 보호자(형제) — 다자녀 케이스. S3 는 계정 미연결(AUTH-11).
INSERT INTO student (id, academy_id, account_id, name, birth_date, gender, grade, class_name, student_phone,
                      created_at, updated_at)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 1, NULL, '김철수', DATE '2015-03-02', 'male', '초3', 'A반', NULL, now(), now()),
    (2, 1, NULL, '김영희', DATE '2017-05-11', 'female', '초1', 'A반', NULL, now(), now()),
    (3, 1, NULL, '박민수', DATE '2014-11-20', 'male', '초4', 'B반', NULL, now(), now()),
    (4, 1, 10, '이하늘', DATE '2013-07-08', 'female', '초5', 'B반', '010-3000-0004', now(), now()),
    (5, 1, NULL, '최지우', DATE '2016-01-15', 'male', '초2', 'A반', NULL, now(), now()),
    (6, 2, 12, '정다은', DATE '2015-09-09', 'female', '초3', 'A반', '010-4000-0001', now(), now());

-- 보호자 4명 = active parent 계정 4개(5,6,7,9)와 1:1. G1(S1,S2) · G2(S3,S4) · G3(S5) · GB1(SB1).
INSERT INTO guardian (id, academy_id, account_id, name, phone, created_at, updated_at)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 1, 5, '최부모', '010-1000-0001', now(), now()),
    (2, 1, 6, '정부모', '010-1000-0002', now(), now()),
    (3, 1, 7, '한부모', '010-1000-0003', now(), now()),
    (4, 2, 9, '윤부모', '010-2000-0001', now(), now());

INSERT INTO guardian_student (id, guardian_id, student_id, linked_at, unlinked_at)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 1, 1, now(), NULL),
    (2, 1, 2, now(), NULL),
    (3, 2, 3, now(), NULL),
    (4, 2, 4, now(), NULL),
    (5, 3, 5, now(), NULL),
    (6, 4, 6, now(), NULL);

-- 보호자-학생 연결 요청 1건(대기) — 이미 등록된 G1 이 S5 를 추가로 연결하려는 시도.
INSERT INTO link_request (id, guardian_id, student_id, status, requested_at, expires_at)
OVERRIDING SYSTEM VALUE
VALUES (1, 1, 5, 'pending', now() - interval '10 minutes', now() + interval '20 minutes');

-- 학생이 발급한 연결 코드 1건(유효, 미사용) — 위 요청과 짝을 이룬다.
INSERT INTO link_code (id, link_request_id, code, expires_at, used_at)
OVERRIDING SYSTEM VALUE
VALUES (1, 1, '482913', now() + interval '10 minutes', NULL);

-- 인증 코드 1건 — §3.4 미기재(정의 부재)지만 비밀번호 찾기 흐름을 Swagger 로 시연하려 최소 추가.
INSERT INTO verification_code (id, phone, code, purpose, expires_at, consumed_at, attempt_count)
OVERRIDING SYSTEM VALUE
VALUES (1, '010-9999-0000', '123456', 'password', now() + interval '5 minutes', NULL, 0);

-- 주간 주소: A 학생 5명 × 요일7 × 방향2 = 70행(§3.4 명시). B 학생은 격리 검증 최소 범위라 미포함(판단).
-- S4(id=4)는 사전 승인된 이전으로 그린빌라(stop 3)를 쓴다 — 아래 change_request(CR2)와 짝을 이룬다.
INSERT INTO weekly_address (id, student_id, weekday, direction, address, verified, stop_id, lat, lng, updated_at)
OVERRIDING SYSTEM VALUE
SELECT (row_number() OVER (ORDER BY s.id, wd.ord, dir.ord)),
       s.id,
       wd.weekday,
       dir.direction,
       '학생' || s.id || ' 주소(' || wd.weekday || '/' || dir.direction || ')',
       true,
       s.stop_id,
       37.500000 + s.id * 0.001,
       127.000000 + s.id * 0.001,
       now()
FROM (VALUES (1, 1), (2, 2), (3, 3), (4, 3), (5, 4)) AS s(id, stop_id)
CROSS JOIN (VALUES ('mon', 1), ('tue', 2), ('wed', 3), ('thu', 4), ('fri', 5), ('sat', 6), ('sun', 7)) AS wd(weekday, ord)
CROSS JOIN (VALUES ('to_academy', 1), ('from_academy', 2)) AS dir(direction, ord);

-- ── 그룹③ 차량 · 인력 · 운행 · 노선 ───────────────────────────────────────────
-- 버스 3대 — A2 는 정원 4석(기사·동승자 제외 학생석 2)으로 "잔여 1석" 근접 시연용.
INSERT INTO bus (id, academy_id, bus_no, plate_no, capacity, driver_count, escort_count,
                  student_capacity, operable, created_at, updated_at)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 1, '1호차', '12가1234', 16, 1, 1, 14, true, now(), now()),
    (2, 1, '2호차', '12가5678', 4, 1, 1, 2, true, now(), now()),
    (3, 2, '1호차', '34나1111', 12, 1, 1, 10, true, now(), now());

-- 매니저(기사·동승자) — A 기사2·동승자2(+차단 1) · B 각 1.
INSERT INTO manager (id, academy_id, account_id, role, name, phone, work_hours, created_at, updated_at)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 1, 13, 'driver', '강기사', '010-5000-0001', '{"start":"07:30","end":"17:30"}'::jsonb, now(), now()),
    (2, 1, 14, 'driver', '오기사', '010-5000-0002', '{"start":"07:40","end":"17:40"}'::jsonb, now(), now()),
    (3, 1, 17, 'escort', '서동승', '010-7000-0001', '{"start":"07:30","end":"17:30"}'::jsonb, now(), now()),
    (4, 1, 18, 'escort', '문동승', '010-7000-0002', '{"start":"07:40","end":"17:40"}'::jsonb, now(), now()),
    (5, 2, 16, 'driver', '남기사', '010-6000-0001', '{"start":"07:30","end":"17:30"}'::jsonb, now(), now()),
    (6, 2, 19, 'escort', '류동승', '010-8000-0001', '{"start":"07:30","end":"17:30"}'::jsonb, now(), now()),
    (7, 1, 15, 'driver', '차단기사', '010-5000-0099', NULL, now(), now());

-- 정기 배차: 오늘 요일 기준으로 버스별 등원·하원 각 1건씩(SCH-01) — dow(0=일)를 mon~sun 배열로 변환.
INSERT INTO schedule (id, academy_id, bus_id, weekday, direction, depart_time, origin_name,
                       destination_name, active, created_at, updated_at)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 1, 1, (ARRAY['sun','mon','tue','wed','thu','fri','sat'])[extract(dow from now())::int + 1], 'to_academy', TIME '08:00', '중앙 집결지', '바래다학원 A', true, now(), now()),
    (2, 1, 1, (ARRAY['sun','mon','tue','wed','thu','fri','sat'])[extract(dow from now())::int + 1], 'from_academy', TIME '16:00', '바래다학원 A', '중앙 집결지', true, now(), now()),
    (3, 1, 2, (ARRAY['sun','mon','tue','wed','thu','fri','sat'])[extract(dow from now())::int + 1], 'to_academy', TIME '08:10', '중앙 집결지', '바래다학원 A', true, now(), now()),
    (4, 1, 2, (ARRAY['sun','mon','tue','wed','thu','fri','sat'])[extract(dow from now())::int + 1], 'from_academy', TIME '16:10', '바래다학원 A', '중앙 집결지', true, now(), now()),
    (5, 2, 3, (ARRAY['sun','mon','tue','wed','thu','fri','sat'])[extract(dow from now())::int + 1], 'to_academy', TIME '08:20', 'B 집결지', '바래다학원 B', true, now(), now()),
    (6, 2, 3, (ARRAY['sun','mon','tue','wed','thu','fri','sat'])[extract(dow from now())::int + 1], 'from_academy', TIME '16:20', '바래다학원 B', 'B 집결지', true, now(), now());

-- 고정 노선 1개(1호차 등원) — Phase1 이 최소 확정하는 고정 노선(ROUTE-01).
INSERT INTO route (id, academy_id, bus_id, weekday, direction, name, active, created_at, updated_at)
OVERRIDING SYSTEM VALUE
VALUES (1, 1, 1, (ARRAY['sun','mon','tue','wed','thu','fri','sat'])[extract(dow from now())::int + 1], 'to_academy', '본선(등원)', true, now(), now());

INSERT INTO route_stop (id, route_id, stop_id, seq)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 1, 1, 1),
    (2, 1, 2, 2);

-- 회차 5건 — 3구간 전수: R1 idle(출발 3시간 전) · R2 confirmed(20분 전, 이미 확정 지남) ·
-- R3 moving(10분 전 출발) · R4 finished(3시간 전 출발, 종료) · R5 confirmed(B 학원, 격리 검증용).
-- confirm_at 은 반드시 depart_time - 30분이어야 하므로(ck_run_confirm_at) 같은 now() 식에서 유도한다.
INSERT INTO run (id, academy_id, bus_id, schedule_id, service_date, direction, depart_time, confirm_at,
                  status, origin_name, destination_name, confirmed_at, started_at, finished_at, created_at, updated_at)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 1, 1, 1, CURRENT_DATE, 'to_academy',
        now() + interval '3 hours', (now() + interval '3 hours') - interval '30 minutes',
        'idle', '중앙 집결지', '바래다학원 A', NULL, NULL, NULL, now(), now()),
    (2, 1, 1, 2, CURRENT_DATE, 'from_academy',
        now() + interval '20 minutes', (now() + interval '20 minutes') - interval '30 minutes',
        'confirmed', '바래다학원 A', '중앙 집결지', (now() + interval '20 minutes') - interval '30 minutes', NULL, NULL, now(), now()),
    (3, 1, 2, 3, CURRENT_DATE, 'to_academy',
        now() - interval '10 minutes', (now() - interval '10 minutes') - interval '30 minutes',
        'moving', '중앙 집결지', '바래다학원 A', (now() - interval '10 minutes') - interval '30 minutes', now() - interval '8 minutes', NULL, now(), now()),
    (4, 1, 2, 4, CURRENT_DATE, 'from_academy',
        now() - interval '3 hours', (now() - interval '3 hours') - interval '30 minutes',
        'finished', '바래다학원 A', '중앙 집결지', (now() - interval '3 hours') - interval '30 minutes',
        (now() - interval '3 hours') + interval '1 minute', (now() - interval '3 hours') + interval '40 minutes', now(), now()),
    (5, 2, 3, 5, CURRENT_DATE, 'to_academy',
        now() + interval '25 minutes', (now() + interval '25 minutes') - interval '30 minutes',
        'confirmed', 'B 집결지', '바래다학원 B', (now() + interval '25 minutes') - interval '30 minutes', NULL, NULL, now(), now());

-- 강제 경유지 1건(R3, moving 중 반영) — MGR-04 시연.
INSERT INTO waypoint (id, run_id, label, lat, lng, applied, created_by, created_at)
OVERRIDING SYSTEM VALUE
VALUES (1, 3, '임시 집결지', 37.570000, 126.980000, true, 2, now() - interval '4 minutes');

-- 확정 노선 3건(R2·R3·R5) — R2 는 배포 버전이 2개(v1→v2, 변경요청 승인으로 재배포)까지 간다.
INSERT INTO confirmed_route (run_id, current_version_id, confirmed_at)
VALUES
    (2, NULL, (SELECT confirm_at FROM run WHERE id = 2)),
    (3, NULL, (SELECT confirm_at FROM run WHERE id = 3)),
    (5, NULL, (SELECT confirm_at FROM run WHERE id = 5));

INSERT INTO route_version (id, confirmed_route_id, version_no, source, published_at, input_fingerprint,
                            engine_name, policy_snapshot, fallback_used, created_by)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 2, 1, 'confirm_batch', (SELECT confirm_at FROM run WHERE id = 2), 'fp-run2-v1', 'nearest-neighbor', '{}'::jsonb, false, NULL),
    (2, 2, 2, 'approval', now() - interval '5 minutes', 'fp-run2-v2', 'nearest-neighbor', '{}'::jsonb, false, 2),
    (3, 3, 1, 'confirm_batch', (SELECT confirm_at FROM run WHERE id = 3), 'fp-run3-v1', 'nearest-neighbor', '{}'::jsonb, true, NULL),
    (4, 5, 1, 'confirm_batch', (SELECT confirm_at FROM run WHERE id = 5), 'fp-run5-v1', 'nearest-neighbor', '{}'::jsonb, false, NULL);

-- 각 확정 노선의 현재 버전을 최신 배포로 맞춘다(순환 FK 라 confirmed_route 삽입 뒤에 UPDATE).
UPDATE confirmed_route SET current_version_id = 2 WHERE run_id = 2;
UPDATE confirmed_route SET current_version_id = 3 WHERE run_id = 3;
UPDATE confirmed_route SET current_version_id = 4 WHERE run_id = 5;

-- 정차 항목: v1(R2)=2, v2(R2)=2(S4 재배치로 stop3 추가), v1(R3)=4+경유지1, v1(R5)=1.
INSERT INTO run_stop (id, route_version_id, stop_id, waypoint_id, seq, change)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 1, 1, NULL, 1, NULL),
    (2, 1, 2, NULL, 2, NULL),
    (3, 2, 1, NULL, 1, NULL),
    (4, 2, 3, NULL, 2, 'added'),
    (5, 3, 2, NULL, 1, NULL),
    (6, 3, 3, NULL, 2, NULL),
    (7, 3, 1, NULL, 3, NULL),
    (8, 3, 4, NULL, 4, NULL),
    (9, 3, NULL, 1, 5, 'added'),
    (10, 4, 5, NULL, 1, NULL);

-- 탑승 상태 5종 전수(waiting/boarded/alighted/absent/no_show) — R2 에 waiting 2, R3 에 나머지 4.
INSERT INTO run_rider (id, run_id, student_id, stop_id, status, boarded_at, alighted_at)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 2, 1, 1, 'waiting', NULL, NULL),
    (2, 2, 4, 3, 'waiting', NULL, NULL),
    (3, 3, 2, 2, 'boarded', now() - interval '5 minutes', NULL),
    (4, 3, 3, 3, 'alighted', now() - interval '9 minutes', now() - interval '2 minutes'),
    (5, 3, 1, 1, 'absent', NULL, NULL),
    (6, 3, 5, 4, 'no_show', NULL, NULL),
    (7, 5, 6, 5, 'waiting', NULL, NULL);

-- 배차: 회차 5건 × (기사·동승자) 각 1 — R2 는 v2 미확인(acked=v1) 상태로 배지 시연.
INSERT INTO assignment (id, run_id, manager_id, role, assigned_at, assigned_by, acked_route_version_id, acked_at)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 1, 1, 'driver', now() - interval '1 day', 2, NULL, NULL),
    (2, 1, 3, 'escort', now() - interval '1 day', 2, NULL, NULL),
    (3, 2, 1, 'driver', now() - interval '1 day', 2, 1, now() - interval '15 minutes'),
    (4, 2, 3, 'escort', now() - interval '1 day', 2, 1, now() - interval '15 minutes'),
    (5, 3, 2, 'driver', now() - interval '1 day', 2, 3, now() - interval '9 minutes'),
    (6, 3, 4, 'escort', now() - interval '1 day', 2, 3, now() - interval '9 minutes'),
    (7, 4, 2, 'driver', now() - interval '1 day', 2, NULL, NULL),
    (8, 4, 4, 'escort', now() - interval '1 day', 2, NULL, NULL),
    (9, 5, 5, 'driver', now() - interval '1 day', 3, 4, now() - interval '10 minutes'),
    (10, 5, 6, 'escort', now() - interval '1 day', 3, 4, now() - interval '10 minutes');

-- ── 그룹④ 요청 · 예외 · 알림 · 이력 ──────────────────────────────────────────
-- 탑승 의사 토글 2건(R2) — 한도 미소진(0) 1 · 소진(1) 1.
INSERT INTO boarding_intent (id, run_id, student_id, riding, change_used_count, applied_segment, changed_at, changed_by)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 2, 1, true, 0, 2, now() - interval '20 minutes', 5),
    (2, 2, 4, false, 1, 2, now() - interval '15 minutes', 6);

-- 변경요청 4종 전수(pending/approved/rejected/auto_rejected) — R2, ②구간.
-- CR2(승인)는 S4 를 stop3 로 재배치한 실제 변경이며 route_version v2 배포의 계기다.
INSERT INTO change_request (id, academy_id, run_id, student_id, requested_by, source, type, new_address, new_stop_id,
                             stop_removed, status, reject_reason, window_segment, requested_at, deadline_at,
                             decided_by, decided_at, applied_route_version_id)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 1, 2, 1, 5, 'intent', 'cancel', NULL, NULL, false, 'pending', NULL, 2, now() - interval '18 minutes', (SELECT depart_time FROM run WHERE id = 2), NULL, NULL, NULL),
    (2, 1, 2, 4, 6, 'change_request', 'relocate', '서울시 그린로 33', 3, false, 'approved', NULL, 2, now() - interval '17 minutes', (SELECT depart_time FROM run WHERE id = 2), 2, now() - interval '5 minutes', 2),
    (3, 1, 2, 2, 5, 'change_request', 'relocate', '서울시 새길로 10', NULL, false, 'rejected', '마감 시간 경과', 2, now() - interval '16 minutes', (SELECT depart_time FROM run WHERE id = 2), 2, now() - interval '4 minutes', NULL),
    (4, 1, 2, 3, 6, 'intent', 'cancel', NULL, NULL, false, 'auto_rejected', NULL, 2, now() - interval '1 minute', (SELECT depart_time FROM run WHERE id = 2), NULL, now(), NULL);

-- 탑승 상태 이력 2건 — run_rider 3(boarded), 4(alighted) 의 전이 기록.
INSERT INTO rider_status_history (id, run_rider_id, from_status, to_status, changed_at, actor_type, changed_by)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 3, 'waiting', 'boarded', now() - interval '5 minutes', 'escort', 18),
    (2, 4, 'boarded', 'alighted', now() - interval '2 minutes', 'escort', 18);

-- 노쇼 1건(R3, run_rider 6) — 대응 시도 2건(무응답 후 메시지 응답, 출발 결정)까지.
INSERT INTO no_show_case (id, run_rider_id, started_at, expires_at, escalated_at, resolved_at, decision)
OVERRIDING SYSTEM VALUE
VALUES (1, 6, (SELECT depart_time FROM run WHERE id = 3), (SELECT depart_time FROM run WHERE id = 3) + interval '3 minutes',
        (SELECT depart_time FROM run WHERE id = 3) + interval '3 minutes', (SELECT depart_time FROM run WHERE id = 3) + interval '3 minutes', 'depart');

INSERT INTO no_show_contact (id, no_show_case_id, attempt_type, result, decision, attempted_at, attempted_by)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 1, 'call', 'no_answer', NULL, (SELECT depart_time FROM run WHERE id = 3) + interval '1 minute', 18),
    (2, 1, 'message', 'answered', 'depart', (SELECT depart_time FROM run WHERE id = 3) + interval '2 minutes', 18);

-- 비상 알림 1건(R3, 미확인 상태로 배지 시연).
INSERT INTO emergency_alert (id, academy_id, run_id, bus_no, raised_by, raised_by_role, type, memo,
                              lat, lng, rider_count, occurred_at, received_at, client_key, acked_by, acked_at)
OVERRIDING SYSTEM VALUE
VALUES (1, 1, 3, '2호차', 14, 'driver', 'vehicle_fault', '엔진 경고등 점등, 정비 필요',
        37.568000, 126.979500, 4, now() - interval '3 minutes', now() - interval '3 minutes', gen_random_uuid(), NULL, NULL);

-- exception_report: 0행 — §3.4 미기재(정의 부재, Phase9/11 영역), FK 제약도 없어 생략해도 무해(판단).

-- 위치 이력 3건(R3, moving 중 5분 간격) — TRACK-01.
INSERT INTO run_position (id, run_id, lat, lng, speed, heading, recorded_at, received_at)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 3, 37.567000, 126.978500, 32.0, 90.0, now() - interval '9 minutes', now() - interval '9 minutes'),
    (2, 3, 37.567500, 126.979000, 28.5, 95.0, now() - interval '6 minutes', now() - interval '6 minutes'),
    (3, 3, 37.568000, 126.979500, 0.0, 100.0, now() - interval '3 minutes', now() - interval '3 minutes');

-- 알림 로그 10건 — pending(재시도 대상) 1 · failed 1 · read/unread 각 1 이상, type 다양화.
INSERT INTO notification_log (id, academy_id, recipient_account_id, recipient_name, recipient_role,
                               student_id, student_name, run_id, type, title, body,
                               push_state, popup, sent_at, read_at, push_attempts, fail_reason, dedup_key, created_at)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 1, 5, '최부모', 'parent', 1, '김철수', 2, 'boarding', '탑승 안내', '김철수 학생이 곧 탑승합니다.', 'sent', false, now() - interval '10 minutes', NULL, 1, NULL, 'boarding:2:1:seed', now()),
    (2, 1, 5, '최부모', 'parent', 1, '김철수', 3, 'alighting', '하차 안내', '김철수 학생이 하차했습니다.', 'sent', false, now() - interval '2 minutes', now() - interval '1 minute', 1, NULL, 'alighting:3:1:seed', now()),
    (3, 1, 6, '정부모', 'parent', 4, '이하늘', 2, 'route_changed', '노선 변경 안내', '이하늘 학생 승차지가 변경되었습니다.', 'sent', true, now() - interval '5 minutes', NULL, 1, NULL, 'route_changed:2:4:seed', now()),
    (4, 1, 6, '정부모', 'parent', 3, '박민수', 3, 'no_show', '노쇼 안내', '박민수 학생이 승차 예정 시간에 나타나지 않았습니다.', 'pending', true, NULL, NULL, 0, NULL, 'no_show:3:3:seed', now()),
    (5, 2, 9, '윤부모', 'parent', 6, '정다은', 5, 'arrive', '도착 안내', '버스가 정류장에 도착했습니다.', 'sent', false, now() - interval '1 minute', NULL, 1, NULL, 'arrive:5:6:seed', now()),
    (6, 1, 8, '조대기', 'parent', NULL, NULL, NULL, 'signup_decided', '가입 심사 안내', '가입 심사가 진행 중입니다.', 'sent', false, now() - interval '1 day', now() - interval '20 hours', 1, NULL, 'signup_decided:na:8:seed', now()),
    (7, 1, 2, '김운영', 'staff', 1, '김철수', 2, 'approval_requested', '변경 승인 요청', '김철수 학생 취소 요청이 접수되었습니다.', 'sent', false, now() - interval '18 minutes', NULL, 1, NULL, 'approval_requested:2:1:seed', now()),
    (8, 1, 2, '김운영', 'staff', NULL, NULL, 3, 'emergency', '비상 알림', '2호차에서 비상 상황이 발생했습니다.', 'sent', true, now() - interval '3 minutes', NULL, 1, NULL, 'emergency:3:na:seed', now()),
    (9, 1, 6, '정부모', 'parent', 4, '이하늘', 3, 'delay', '지연 안내', '버스 출발이 지연되고 있습니다.', 'failed', false, NULL, NULL, 3, 'FCM 토큰 만료', 'delay:3:4:seed', now()),
    (10, 2, 9, '윤부모', 'parent', 6, '정다은', 5, 'run_started', '운행 시작 안내', '버스가 출발했습니다.', 'sent', false, now() - interval '25 minutes', now() - interval '24 minutes', 1, NULL, 'run_started:5:6:seed', now());

-- 기기 토큰 3건.
INSERT INTO device_token (id, account_id, device_id, token, platform, revoked_at, created_at, updated_at)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 5, 'device-parent-a1', 'tok-abc123', 'android', NULL, now(), now()),
    (2, 6, 'device-parent-a2', 'tok-def456', 'ios', NULL, now(), now()),
    (3, 13, 'device-driver-a1', 'tok-ghi789', 'android', NULL, now(), now());

-- 알림 설정: 학부모·학생 역할 active 계정에만 존재한다(§3.4 미기재분, 판단: 스태프/기사/동승자는 대상 아님).
INSERT INTO notification_setting (account_id, arrive, boarding, no_show, updated_at)
VALUES
    (5, true, true, true, now()),
    (6, true, true, true, now()),
    (7, true, true, false, now()),
    (9, true, true, true, now()),
    (10, false, true, true, now()),
    (12, true, true, true, now());

-- 감사 로그 3건 — 개인정보 열람(L3) 1 · 로그인 성공 1 · 계정 차단 1.
INSERT INTO audit_log (id, academy_id, actor_account_id, actor_login_id, category, action, target_type,
                        target_id, ip, detail, block_event, occurred_at)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 1, 2, 'staffA', 'data_access', 'read', 'student', 1, '127.0.0.1'::inet, '{"field":"student_phone"}'::jsonb, false, now() - interval '1 hour'),
    (2, 1, 5, 'parentA1', 'login', 'login_success', NULL, NULL, '127.0.0.1'::inet, NULL, false, now() - interval '30 minutes'),
    (3, 1, 15, 'driverBlocked', 'login', 'block', NULL, NULL, '127.0.0.1'::inet, NULL, true, now() - interval '10 minutes');

-- ── 시퀀스 재조정 ─────────────────────────────────────────────────────────────
-- 위에서 OVERRIDING SYSTEM VALUE 로 명시 PK 를 넣은 테이블은 identity 시퀀스가 그대로라
-- 다음 INSERT(앱 런타임)가 1부터 다시 시작해 방금 넣은 값과 충돌한다 — MAX(id) 이후로 미리 당겨둔다.
SELECT setval(pg_get_serial_sequence('academy', 'id'), (SELECT COALESCE(MAX(id), 1) FROM academy));
SELECT setval(pg_get_serial_sequence('account', 'id'), (SELECT COALESCE(MAX(id), 1) FROM account));
SELECT setval(pg_get_serial_sequence('signup_request', 'id'), (SELECT COALESCE(MAX(id), 1) FROM signup_request));
SELECT setval(pg_get_serial_sequence('academy_staff', 'id'), (SELECT COALESCE(MAX(id), 1) FROM academy_staff));
SELECT setval(pg_get_serial_sequence('system_admin', 'id'), (SELECT COALESCE(MAX(id), 1) FROM system_admin));
SELECT setval(pg_get_serial_sequence('stop', 'id'), (SELECT COALESCE(MAX(id), 1) FROM stop));
SELECT setval(pg_get_serial_sequence('student', 'id'), (SELECT COALESCE(MAX(id), 1) FROM student));
SELECT setval(pg_get_serial_sequence('guardian', 'id'), (SELECT COALESCE(MAX(id), 1) FROM guardian));
SELECT setval(pg_get_serial_sequence('guardian_student', 'id'), (SELECT COALESCE(MAX(id), 1) FROM guardian_student));
SELECT setval(pg_get_serial_sequence('link_request', 'id'), (SELECT COALESCE(MAX(id), 1) FROM link_request));
SELECT setval(pg_get_serial_sequence('verification_code', 'id'), (SELECT COALESCE(MAX(id), 1) FROM verification_code));
SELECT setval(pg_get_serial_sequence('link_code', 'id'), (SELECT COALESCE(MAX(id), 1) FROM link_code));
SELECT setval(pg_get_serial_sequence('weekly_address', 'id'), (SELECT COALESCE(MAX(id), 1) FROM weekly_address));
SELECT setval(pg_get_serial_sequence('bus', 'id'), (SELECT COALESCE(MAX(id), 1) FROM bus));
SELECT setval(pg_get_serial_sequence('manager', 'id'), (SELECT COALESCE(MAX(id), 1) FROM manager));
SELECT setval(pg_get_serial_sequence('schedule', 'id'), (SELECT COALESCE(MAX(id), 1) FROM schedule));
SELECT setval(pg_get_serial_sequence('route', 'id'), (SELECT COALESCE(MAX(id), 1) FROM route));
SELECT setval(pg_get_serial_sequence('route_stop', 'id'), (SELECT COALESCE(MAX(id), 1) FROM route_stop));
SELECT setval(pg_get_serial_sequence('run', 'id'), (SELECT COALESCE(MAX(id), 1) FROM run));
SELECT setval(pg_get_serial_sequence('waypoint', 'id'), (SELECT COALESCE(MAX(id), 1) FROM waypoint));
SELECT setval(pg_get_serial_sequence('route_version', 'id'), (SELECT COALESCE(MAX(id), 1) FROM route_version));
SELECT setval(pg_get_serial_sequence('run_stop', 'id'), (SELECT COALESCE(MAX(id), 1) FROM run_stop));
SELECT setval(pg_get_serial_sequence('run_rider', 'id'), (SELECT COALESCE(MAX(id), 1) FROM run_rider));
SELECT setval(pg_get_serial_sequence('assignment', 'id'), (SELECT COALESCE(MAX(id), 1) FROM assignment));
SELECT setval(pg_get_serial_sequence('boarding_intent', 'id'), (SELECT COALESCE(MAX(id), 1) FROM boarding_intent));
SELECT setval(pg_get_serial_sequence('change_request', 'id'), (SELECT COALESCE(MAX(id), 1) FROM change_request));
SELECT setval(pg_get_serial_sequence('rider_status_history', 'id'), (SELECT COALESCE(MAX(id), 1) FROM rider_status_history));
SELECT setval(pg_get_serial_sequence('no_show_case', 'id'), (SELECT COALESCE(MAX(id), 1) FROM no_show_case));
SELECT setval(pg_get_serial_sequence('no_show_contact', 'id'), (SELECT COALESCE(MAX(id), 1) FROM no_show_contact));
SELECT setval(pg_get_serial_sequence('emergency_alert', 'id'), (SELECT COALESCE(MAX(id), 1) FROM emergency_alert));
SELECT setval(pg_get_serial_sequence('run_position', 'id'), (SELECT COALESCE(MAX(id), 1) FROM run_position));
SELECT setval(pg_get_serial_sequence('notification_log', 'id'), (SELECT COALESCE(MAX(id), 1) FROM notification_log));
SELECT setval(pg_get_serial_sequence('device_token', 'id'), (SELECT COALESCE(MAX(id), 1) FROM device_token));
SELECT setval(pg_get_serial_sequence('audit_log', 'id'), (SELECT COALESCE(MAX(id), 1) FROM audit_log));
-- academy_setting(PK=academy_id) · confirmed_route(PK=run_id) · notification_setting(PK=account_id) 는
-- identity 가 아니라 부모 PK 를 그대로 쓰므로 별도 시퀀스가 없다(재조정 대상 아님).
