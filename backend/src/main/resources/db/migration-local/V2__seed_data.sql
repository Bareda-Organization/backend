-- 로컬 개발 전용 데모 시드 — DataInitializer.java(2026-07-20 Flyway 전환으로 제거)를 그대로 SQL로 옮겼다.
-- 프론트 frontend/lib/simulation.js 의 학원(한빛/가온/미래)·정류장·버스·학생 구성과 맞춘다.
-- 비밀번호는 모두 "password"(BCryptPasswordEncoder 기본 strength 10으로 사전 생성한 해시를 그대로 사용).
-- 이 위치(db/migration-local)는 application.yml의 local 프로파일에서만 Flyway 스캔 경로에 추가되므로
-- prod에는 절대 적용되지 않는다.

DO $$
DECLARE
    v_hash        varchar := '$2a$10$Noeszx0nzJUfNo4ubCD03eNfMVfMD9feMo04y/8DHiFLUBF.JZ/Fq';
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
                '서울 강남구 자택 앞', 37.5051, 127.0322, '서울 강남구 자택', 37.5060, 127.0290, now(), now());

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
END $$;
