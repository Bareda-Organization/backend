-- 로컬 개발 전용 데모 시드 — DataInitializer.java(2026-07-20 Flyway 전환으로 제거)를 그대로 SQL로 옮겼다.
-- 프론트 frontend/lib/simulation.js 의 학원(한빛/가온/미래)·정류장·버스·학생 구성과 맞춘다.
-- 비밀번호 해시는 Flyway placeholder(seedPasswordHash)로 주입한다 — 프로파일마다 다른 값을 쓰기 위해서다.
--   local: application.yml 에 박힌 기본값(평문 "password")
--   demo : SSM Parameter Store 의 값(공개 도메인이라 "password" 를 쓰면 안 된다)
-- 이 위치(db/migration-local)는 local·demo 프로파일에서만 스캔에 추가되므로 prod 에는 적용되지 않는다.
--
-- ── 이 시드가 시연 가능하게 하는 것(2026-08-02 보강) ──────────────────────────────
-- 1) 계정·버스·학생·노선: 로그인 → 역할별 목록 조회까지 데이터 없이 비는 화면이 없다.
-- 2) 당일 하원 노선 계획(PUBLISHED): 기사/선탑자 노선 조회·버스 상세 plans[]·시뮬레이션 baseline(delta 비교)이 산다.
-- 3) 당일 등원 운행 세션(IN_PROGRESS) + 승차 기록 2건: 선탑자 명단 사슬(내 버스→세션→명단)과 승하차 이력이 산다.
-- 4) 위치변경 요청·알림 로그 각 1~2건: 관리자/학부모 목록 API 가 빈 배열로 떨어지지 않는다.
-- ⚠️ 날짜는 전부 CURRENT_DATE 다 — 고정 날짜를 박으면 다음 날부터 "당일" 조회가 전부 빈다.

DO $$
DECLARE
    v_hash        varchar := '${seedPasswordHash}';
    v_hanbit_id    bigint;
    v_gaon_id      bigint;
    v_student_user_id  bigint;
    v_parent_user_id   bigint;
    v_driver_user_id   bigint;
    v_admin_user_id    bigint;
    v_platform_user_id bigint;
    v_att3_user_id     bigint;
    v_att1_user_id     bigint;
    v_gaon_att_user_id bigint;
    v_route_a_id   bigint;
    v_route_b_id   bigint;
    v_gaon_route_id bigint;
    v_stop_a_id    bigint;
    v_stop_b_id    bigint;
    v_bus3_id      bigint;
    v_bus1_id      bigint;
    v_gaon_bus_id  bigint;
    v_kim_student_id bigint;
    v_lee_student_id bigint;
    v_park_student_id bigint;
    v_plan_id      bigint;
    v_session_id   bigint;
BEGIN
    -- ── 학원 3곳(좌표는 routing depot 기준점) ──
    INSERT INTO tenant (name, lat, lng, created_at, updated_at)
        VALUES ('한빛학원', 37.5075, 127.0355, now(), now()) RETURNING id INTO v_hanbit_id;
    INSERT INTO tenant (name, lat, lng, created_at, updated_at)
        VALUES ('가온에듀', 37.4980, 127.0276, now(), now()) RETURNING id INTO v_gaon_id;
    INSERT INTO tenant (name, lat, lng, created_at, updated_at)
        VALUES ('미래코딩', 37.5145, 127.0300, now(), now());

    -- ── 역할별 계정(비밀번호 공통 "password") ──
    -- 사진은 데모용 외부 URL(pravatar). 오프라인·차단 환경에서 404 가 나도
    -- 프론트는 이니셜 플레이스홀더로 폴백해야 하며 화면이 깨져선 안 된다.
    INSERT INTO app_user (email, name, password, phone, photo_url, created_at, updated_at)
        VALUES ('student@school.com', '김민준', v_hash, '010-0000-0001', 'https://i.pravatar.cc/150?img=11', now(), now()) RETURNING id INTO v_student_user_id;
    INSERT INTO app_user (email, name, password, phone, photo_url, created_at, updated_at)
        VALUES ('parent@school.com', '이부모', v_hash, '010-0000-0002', 'https://i.pravatar.cc/150?img=45', now(), now()) RETURNING id INTO v_parent_user_id;
    INSERT INTO app_user (email, name, password, phone, photo_url, created_at, updated_at)
        VALUES ('driver@school.com', '박기사', v_hash, '010-0000-0003', 'https://i.pravatar.cc/150?img=13', now(), now()) RETURNING id INTO v_driver_user_id;
    INSERT INTO app_user (email, name, password, phone, photo_url, created_at, updated_at)
        VALUES ('admin@school.com', '한빛관리자', v_hash, '010-0000-0004', 'https://i.pravatar.cc/150?img=32', now(), now()) RETURNING id INTO v_admin_user_id;
    INSERT INTO app_user (email, name, password, phone, photo_url, created_at, updated_at)
        VALUES ('platform@school.com', '플랫폼관리자', v_hash, '010-0000-0005', 'https://i.pravatar.cc/150?img=60', now(), now()) RETURNING id INTO v_platform_user_id;

    -- 선탑자(ATTENDANT) — 버스 3대에 1:1 로 붙는다(I-1). 시드는 최소 데모 상태만 만들고,
    -- 계정을 늘리는 것은 관리자 화면(BE-12 / FE-11)의 몫이다(D-M).
    INSERT INTO app_user (email, name, password, phone, photo_url, created_at, updated_at)
        VALUES ('attendant3@school.com', '최선탑', v_hash, '010-0000-0006', 'https://i.pravatar.cc/150?img=5', now(), now()) RETURNING id INTO v_att3_user_id;
    INSERT INTO app_user (email, name, password, phone, photo_url, created_at, updated_at)
        VALUES ('attendant1@school.com', '윤선탑', v_hash, '010-0000-0007', 'https://i.pravatar.cc/150?img=9', now(), now()) RETURNING id INTO v_att1_user_id;
    INSERT INTO app_user (email, name, password, phone, photo_url, created_at, updated_at)
        VALUES ('gaon.attendant@school.com', '가온선탑', v_hash, '010-0000-0008', 'https://i.pravatar.cc/150?img=25', now(), now()) RETURNING id INTO v_gaon_att_user_id;

    INSERT INTO user_tenant_role (user_id, tenant_id, role, created_at, updated_at)
        VALUES (v_student_user_id, v_hanbit_id, 'STUDENT', now(), now());
    INSERT INTO user_tenant_role (user_id, tenant_id, role, created_at, updated_at)
        VALUES (v_parent_user_id, v_hanbit_id, 'PARENT', now(), now());
    INSERT INTO user_tenant_role (user_id, tenant_id, role, created_at, updated_at)
        VALUES (v_driver_user_id, v_hanbit_id, 'DRIVER', now(), now());
    INSERT INTO user_tenant_role (user_id, tenant_id, role, created_at, updated_at)
        VALUES (v_admin_user_id, v_hanbit_id, 'ACADEMY_ADMIN', now(), now());
    INSERT INTO user_tenant_role (user_id, tenant_id, role, created_at, updated_at)
        VALUES (v_platform_user_id, NULL, 'PLATFORM_ADMIN', now(), now());   -- 플랫폼 관리자는 전역 역할(tenant 없음)
    INSERT INTO user_tenant_role (user_id, tenant_id, role, created_at, updated_at)
        VALUES (v_att3_user_id, v_hanbit_id, 'ATTENDANT', now(), now());
    INSERT INTO user_tenant_role (user_id, tenant_id, role, created_at, updated_at)
        VALUES (v_att1_user_id, v_hanbit_id, 'ATTENDANT', now(), now());
    INSERT INTO user_tenant_role (user_id, tenant_id, role, created_at, updated_at)
        VALUES (v_gaon_att_user_id, v_gaon_id, 'ATTENDANT', now(), now());   -- 가온에듀 소속 — 크로스테넌트 차단 검증용

    -- ── 한빛학원 노선·정류장 ──
    INSERT INTO route (tenant_id, name, assign_capacity, created_at, updated_at)
        VALUES (v_hanbit_id, '하원 A노선', 25, now(), now()) RETURNING id INTO v_route_a_id;
    INSERT INTO stop (route_id, name, seq, lat, lng, created_at, updated_at)
        VALUES (v_route_a_id, '정류장 A', 1, 37.5010, 127.0275, now(), now()) RETURNING id INTO v_stop_a_id;
    INSERT INTO stop (route_id, name, seq, lat, lng, created_at, updated_at)
        VALUES (v_route_a_id, '정류장 B', 2, 37.5045, 127.0310, now(), now()) RETURNING id INTO v_stop_b_id;
    INSERT INTO stop (route_id, name, seq, lat, lng, created_at, updated_at)
        VALUES (v_route_a_id, '학원', 3, 37.5075, 127.0355, now(), now());

    -- 배정 정원 2인 노선 — 학생 3명 배정으로 초과 경고 데모(기능9)
    INSERT INTO route (tenant_id, name, assign_capacity, created_at, updated_at)
        VALUES (v_hanbit_id, '하원 B노선', 2, now(), now()) RETURNING id INTO v_route_b_id;

    -- ── 한빛학원 버스 ──
    INSERT INTO bus (tenant_id, name, plate_number, seat_capacity, driver_id, attendant_id, route_id, insurance_expiry, created_at, updated_at)
        VALUES (v_hanbit_id, '3호차', '서울12가3456', 25, v_driver_user_id, v_att3_user_id, v_route_a_id, DATE '2026-12-31', now(), now())
        RETURNING id INTO v_bus3_id;
    INSERT INTO bus (tenant_id, name, plate_number, seat_capacity, driver_id, attendant_id, route_id, insurance_expiry, created_at, updated_at)
        VALUES (v_hanbit_id, '1호차', '서울34나5678', 25, NULL, v_att1_user_id, v_route_b_id, DATE '2026-08-15', now(), now())   -- 기사 미배차, 선탑자는 배정
        RETURNING id INTO v_bus1_id;

    -- ── 한빛학원 학생 ──
    -- 등원(pickup) 좌표는 6명 중 3명(김민준·박도윤·최지우)에게만 준다 — D-K 우선순위 규칙의 두 갈래를
    -- 시드만으로 검증하기 위해서다. 좌표가 있으면 그것을, 없으면 stop_id(공유 정류장)를 쓴다.
    -- 버스 2대 각각이 "좌표 학생 + 폴백 학생"을 동시에 갖도록 섞어 뒀다.
    -- stop_id 는 좌표를 준 학생도 그대로 유지한다 — 규칙은 정류장을 지우는 게 아니라 좌표를 우선하는 것이다.

    -- 3호차(정상): 김민준(학생 계정 연결), 이서연, 박도윤 — 하차지 좌표는 routing 데모용으로 서로 흩어지게 부여
    INSERT INTO student (tenant_id, user_id, name, phone, photo_url, bus_id, stop_id,
                         pickup_address, pickup_lat, pickup_lng, dropoff_address, dropoff_lat, dropoff_lng, created_at, updated_at)
        VALUES (v_hanbit_id, v_student_user_id, '김민준', '010-1000-0001', 'https://i.pravatar.cc/150?img=11', v_bus3_id, v_stop_a_id,
                '서울 서초구 자택 앞', 37.5002, 127.0262, '서울 서초구 자택', 37.4998, 127.0245, now(), now())
        RETURNING id INTO v_kim_student_id;
    INSERT INTO student (tenant_id, user_id, name, phone, photo_url, bus_id, stop_id, dropoff_address, dropoff_lat, dropoff_lng, created_at, updated_at)
        VALUES (v_hanbit_id, NULL, '이서연', '010-1000-0002', 'https://i.pravatar.cc/150?img=47', v_bus3_id, v_stop_a_id, '서울 서초구 자택2', 37.5032, 127.0398, now(), now())
        RETURNING id INTO v_lee_student_id;
    INSERT INTO student (tenant_id, user_id, name, phone, photo_url, bus_id, stop_id,
                         pickup_address, pickup_lat, pickup_lng, dropoff_address, dropoff_lat, dropoff_lng, created_at, updated_at)
        VALUES (v_hanbit_id, NULL, '박도윤', '010-1000-0003', 'https://i.pravatar.cc/150?img=14', v_bus3_id, v_stop_b_id,
                '서울 강남구 자택 앞', 37.5051, 127.0322, '서울 강남구 자택', 37.5060, 127.0290, now(), now())
        RETURNING id INTO v_park_student_id;

    -- 1호차(초과): 배정 정원 2인 노선에 3명 → overCapacity
    INSERT INTO student (tenant_id, user_id, name, phone, photo_url, bus_id, stop_id,
                         pickup_address, pickup_lat, pickup_lng, created_at, updated_at)
        VALUES (v_hanbit_id, NULL, '최지우', '010-1000-0004', 'https://i.pravatar.cc/150?img=26', v_bus1_id, v_stop_a_id,
                '서울 서초구 아파트 정문', 37.4995, 127.0288, now(), now());
    INSERT INTO student (tenant_id, user_id, name, phone, photo_url, bus_id, stop_id, created_at, updated_at)
        VALUES (v_hanbit_id, NULL, '정하율', '010-1000-0005', 'https://i.pravatar.cc/150?img=31', v_bus1_id, v_stop_a_id, now(), now());
    INSERT INTO student (tenant_id, user_id, name, phone, photo_url, bus_id, stop_id, created_at, updated_at)
        VALUES (v_hanbit_id, NULL, '강서준', '010-1000-0006', 'https://i.pravatar.cc/150?img=52', v_bus1_id, v_stop_b_id, now(), now());

    -- ── 학부모 ↔ 자녀 연결(형제자매: 김민준·이서연) ──
    INSERT INTO student_guardian (student_id, guardian_id, relation, created_at, updated_at)
        VALUES (v_kim_student_id, v_parent_user_id, '모', now(), now());
    INSERT INTO student_guardian (student_id, guardian_id, relation, created_at, updated_at)
        VALUES (v_lee_student_id, v_parent_user_id, '모', now(), now());

    -- ── 가온에듀: 크로스 테넌트 조회용 최소 데이터(플랫폼 관리자 데모) ──
    INSERT INTO route (tenant_id, name, assign_capacity, created_at, updated_at)
        VALUES (v_gaon_id, '가온 1노선', 25, now(), now()) RETURNING id INTO v_gaon_route_id;
    INSERT INTO bus (tenant_id, name, plate_number, seat_capacity, attendant_id, route_id, insurance_expiry, created_at, updated_at)
        VALUES (v_gaon_id, '2호차', '서울56다7890', 25, v_gaon_att_user_id, v_gaon_route_id, DATE '2027-03-01', now(), now())
        RETURNING id INTO v_gaon_bus_id;

    -- ════════════════════════════════════════════════════════════════════════════
    -- 운영 데모 데이터 — Swagger 예시값 그대로 Try it out 이 "데이터 없음" 으로 실패하지 않게 한다.
    -- 날짜는 전부 CURRENT_DATE 다(고정 날짜 금지 — 다음 날 전부 빈다).
    -- ════════════════════════════════════════════════════════════════════════════

    -- ── ① 3호차 당일 하원(DROPOFF) 노선 계획 — 배포 완료 ──
    -- 살리는 API: GET /api/route-plans/driver/{busId}(기사·선탑자 당일 배포본),
    --             GET /api/buses/{id} 의 plans[], GET /api/route-plans?busId=1,
    --             POST /api/route-plans/simulate 의 baseline(이게 없으면 delta 가 null 로만 나온다).
    -- polyline 은 MapRouteClient(OSRM/Naver)가 만드는 것과 같은 형식 — JSON "문자열" 이고
    -- 각 원소는 [경도, 위도] 순서다(위경도를 뒤집어 넣으면 프론트가 서해에 선을 그린다).
    INSERT INTO route_plan (tenant_id, bus_id, direction, status, version, service_date,
                            total_distance_m, total_duration_s, polyline, approved_by, published_by,
                            created_at, updated_at)
        VALUES (v_hanbit_id, v_bus3_id, 'DROPOFF', 'PUBLISHED', 1, CURRENT_DATE,
                4200, 900,
                '[[127.0355,37.5075],[127.0300,37.5040],[127.0245,37.4998],[127.0320,37.5015],[127.0398,37.5032],[127.0350,37.5048],[127.0290,37.5060]]',
                v_admin_user_id, v_admin_user_id, now(), now())
        RETURNING id INTO v_plan_id;

    -- 정차 3건 = 학생 1·2·3 의 하차 좌표. eta_seconds 는 출발 기준 누적값이라 단조 증가해야 한다.
    INSERT INTO route_plan_stop (route_plan_id, seq, student_id, lat, lng, eta_seconds)
        VALUES (v_plan_id, 1, v_kim_student_id,  37.4998, 127.0245, 300);
    INSERT INTO route_plan_stop (route_plan_id, seq, student_id, lat, lng, eta_seconds)
        VALUES (v_plan_id, 2, v_lee_student_id,  37.5032, 127.0398, 600);
    INSERT INTO route_plan_stop (route_plan_id, seq, student_id, lat, lng, eta_seconds)
        VALUES (v_plan_id, 3, v_park_student_id, 37.5060, 127.0290, 900);

    -- ── ② 3호차 당일 등원(PICKUP) 운행 세션 — 진행 중 ──
    -- 살리는 API: GET /api/drive-sessions/bus/{busId} → GET /api/drive-sessions/{id}/roster.
    --   선탑자 명단 화면 사슬(GET /api/buses/me → 세션 목록 → 명단)이 로그인 직후 바로 돈다.
    --   이게 없으면 attendant3@school.com 으로 로그인해도 명단을 볼 수 없다.
    -- ⚠️ DROPOFF 세션은 일부러 만들지 않는다. POST /api/location-change-requests 는 당일 세션이
    --   있으면 BLOCKED 로 거부하므로(불변조건 I-4), PICKUP 만 두면
    --   PICKUP 요청 → BLOCKED / DROPOFF 요청 → 정상 판정 두 분기를 모두 시연할 수 있다. 이 배치가 의도다.
    -- route_plan_id 는 위 계획(하원)과 방향이 달라 연결하지 않는다(null 허용).
    INSERT INTO drive_session (tenant_id, bus_id, driver_id, direction, service_date, route_plan_id,
                               status, started_at, created_at, updated_at)
        VALUES (v_hanbit_id, v_bus3_id, v_driver_user_id, 'PICKUP', CURRENT_DATE, NULL,
                'IN_PROGRESS', now() - interval '20 minutes', now(), now())
        RETURNING id INTO v_session_id;

    -- ── ③ 위 등원 세션의 승차 기록 2건 ──
    -- 살리는 API: GET /api/ride-events/bus/{busId}, GET /api/ride-events/students/{id} 및 명단의 "탑승 완료" 표시.
    -- 박도윤(학생 3)은 일부러 남긴다 — 미승차 상태를 함께 보여주기 위함이다.
    INSERT INTO ride_event (tenant_id, bus_id, student_id, stop_id, type, source, occurred_at, lat, lng,
                            created_at, updated_at)
        VALUES (v_hanbit_id, v_bus3_id, v_kim_student_id, v_stop_a_id, 'BOARD', 'MANUAL',
                now() - interval '15 minutes', 37.5002, 127.0262, now(), now());
    INSERT INTO ride_event (tenant_id, bus_id, student_id, stop_id, type, source, occurred_at, lat, lng,
                            created_at, updated_at)
        VALUES (v_hanbit_id, v_bus3_id, v_lee_student_id, v_stop_a_id, 'BOARD', 'MANUAL',
                now() - interval '14 minutes', 37.5010, 127.0275, now(), now());

    -- ── ④ 등하원 위치 변경 요청 1건(판정 완료) ──
    -- 살리는 API: GET /api/location-change-requests(관리자), GET /api/location-change-requests/children(학부모).
    -- 학부모 이부모(user 2)가 자녀 김민준의 하원 위치를 바꾼 이력 — 배차 전 즉시 반영(APPLIED) 사례다.
    INSERT INTO location_change_request (tenant_id, student_id, requested_by, direction, target_date,
                                         new_lat, new_lng, new_address, decision,
                                         delta_distance_m, delta_duration_s, applied_plan_id, reason,
                                         created_at, updated_at)
        VALUES (v_hanbit_id, v_kim_student_id, v_parent_user_id, 'DROPOFF', CURRENT_DATE,
                37.4998, 127.0245, '서울 서초구 자택', 'APPLIED',
                NULL, NULL, NULL, '배차 전이라 요청 위치를 바로 반영했습니다', now(), now());

    -- ── ⑤ 알림 로그 2건 ──
    -- 살리는 API: GET /api/notifications(관리자), GET /api/notifications/children(학부모).
    -- ⚠️ dedup_key 는 unique 이고 런타임 공식이 "TYPE:studentId:date:stage" 다. 그 형식을 그대로 쓰면
    --   데모 중 실제 승차 알림이 "이미 보냈다" 로 조용히 삼켜진다 → SEED: 접두어로 네임스페이스를 분리한다.
    -- ⚠️ type 은 이 시점(V2) 의 CHECK 목록만 쓸 수 있다. HANDOVER_DONE·ROUTE_PUBLISHED·
    --   LOCATION_CHANGE_RESULT 는 V4·V5 가 나중에 추가하므로 여기서 쓰면 제약 위반으로 마이그레이션이 깨진다.
    INSERT INTO notification_log (tenant_id, student_id, type, message, dedup_key, created_at, updated_at)
        VALUES (v_hanbit_id, v_kim_student_id, 'BOARD_DONE', '김민준 학생이 승차했습니다',
                'SEED:BOARD_DONE:' || v_kim_student_id || ':' || CURRENT_DATE || ':stop' || v_stop_a_id, now(), now());
    INSERT INTO notification_log (tenant_id, student_id, type, message, dedup_key, created_at, updated_at)
        VALUES (v_hanbit_id, v_park_student_id, 'APPROACH', '박도윤 학생의 정류장 도착이 임박했습니다',
                'SEED:APPROACH:' || v_park_student_id || ':' || CURRENT_DATE || ':stop' || v_stop_b_id, now(), now());
END $$;
