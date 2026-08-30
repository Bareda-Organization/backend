-- 바래다(BARAEDA) 초기 스키마 — docs/ERD.md §3(테이블·컬럼) · §4(관계) · §5(제약·인덱스) 전수.
--
-- 배치 순서는 FK 의존 순이다. ERD §1 의 도메인 그룹 4개를 절 구분으로 쓰되, `stop`(그룹 ③) 하나만
-- 그룹 ① 뒤로 앞당겼다 — `weekly_address.stop_id`(그룹 ②)가 그것을 참조하기 때문이다.
--
-- 규약 (ERD §0)
--   · PK 는 `bigint generated always as identity`. 예외는 1:1 확장 테이블(부모 PK 를 그대로 PK 로 사용)
--   · 시각 컬럼은 전부 timestamptz. 오프셋을 버리면 비교 결과만 틀리고 예외는 발생하지 않는다
--   · 좌표는 numeric(9,6), enum 은 varchar(n) + CHECK 목록
--   · 운행일은 date, 스케줄의 시각 원본은 time, 실제 발생 시각은 timestamptz
--
-- ⚠️ 첫 배포 이전이라 새 버전을 쌓지 않고 이 파일을 직접 고친다(CLAUDE.md · IMPLEMENTATION_PLAN §2.1).
--    고치면 체크섬이 바뀌므로 `docker compose down` 후 `up` 으로 로컬 DB 를 재구성해야 한다.
--    demo·prod 에 한 번이라도 적용된 뒤에는 원칙이 뒤집혀 V{n} 추가만 허용된다 — 그 시점에 이 주석을 갱신할 것.


-- =====================================================================================
-- ① 학원 · 계정 · 권한 (7)
-- =====================================================================================

-- 학원. 멀티테넌시의 최상위 단위이자 가입 시 사용자가 선택하는 대상.
-- lat·lng 는 확정 배치(RTE-08)가 읽는 노선 기준점이다(Ruling 190). 기존 행·주소 미등록 학원이
-- 있어 nullable 이며, 좌표가 없는 학원의 회차는 확정에 실패하고 idle 로 복귀한다(목표 5).
CREATE TABLE academy (
    id         bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code       varchar(32)  NOT NULL,
    name       varchar(100) NOT NULL,
    region     varchar(50)  NOT NULL,
    address    varchar(255),
    contact    varchar(30),
    memo       text,
    status     varchar(10)  NOT NULL,
    lat        numeric(9,6),
    lng        numeric(9,6),
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uk_academy_code UNIQUE (code),
    CONSTRAINT ck_academy_status CHECK (status IN ('active', 'inactive')),
    CONSTRAINT ck_academy_lat CHECK (lat BETWEEN -90 AND 90),
    CONSTRAINT ck_academy_lng CHECK (lng BETWEEN -180 AND 180),
    -- 한쪽만 채워지면 기준점이 성립하지 않는다 — 둘 다 NULL 이거나 둘 다 NOT NULL 만 허용.
    CONSTRAINT ck_academy_coords_paired CHECK ((lat IS NULL) = (lng IS NULL))
);

-- 학원별 임계값. 정책 상수 중 유일하게 학원별 설정으로 규정된 미승차 대기 시간을 담는다.
CREATE TABLE academy_setting (
    academy_id           bigint      PRIMARY KEY,
    no_show_wait_minutes integer     NOT NULL DEFAULT 3,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_academy_setting_academy FOREIGN KEY (academy_id) REFERENCES academy (id) ON DELETE CASCADE,
    CONSTRAINT ck_academy_setting_wait_minutes CHECK (no_show_wait_minutes > 0)
);

-- 로그인 계정. 전 인원이 form 가입으로 만드는 단일 인증 주체이며 역할·상태가 접근 범위를 결정한다.
CREATE TABLE account (
    id              bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    academy_id      bigint,
    login_id        varchar(50)  NOT NULL,
    password_hash   varchar(255) NOT NULL,
    name            varchar(50)  NOT NULL,
    phone           varchar(30)  NOT NULL,
    email           varchar(120),
    role            varchar(20)  NOT NULL,
    status          varchar(10)  NOT NULL,
    failed_attempts integer      NOT NULL DEFAULT 0,
    blocked_at      timestamptz,
    block_reason    varchar(100),
    unblocked_by    bigint,
    unblocked_at    timestamptz,
    last_login_at   timestamptz,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uk_account_login_id UNIQUE (login_id),
    CONSTRAINT fk_account_academy FOREIGN KEY (academy_id) REFERENCES academy (id) ON DELETE RESTRICT,
    CONSTRAINT ck_account_role CHECK (role IN ('parent', 'student', 'driver', 'escort', 'staff', 'system_admin')),
    CONSTRAINT ck_account_status CHECK (status IN ('pending', 'active', 'rejected', 'blocked')),
    CONSTRAINT ck_account_failed_attempts CHECK (failed_attempts BETWEEN 0 AND 5),
    CONSTRAINT ck_account_academy_scope CHECK (role = 'system_admin' OR academy_id IS NOT NULL)
);

-- 회원가입 승인 요청. 계정 생성과 활성화를 분리하는 승인 큐이며 재신청은 새 행을 쌓는다.
CREATE TABLE signup_request (
    id             bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id     bigint      NOT NULL,
    academy_id     bigint      NOT NULL,
    requested_role varchar(20) NOT NULL,
    approver_type  varchar(20) NOT NULL,
    status         varchar(10) NOT NULL,
    requested_at   timestamptz NOT NULL,
    decided_by     bigint,
    decided_at     timestamptz,
    reject_reason  varchar(200),
    CONSTRAINT fk_signup_request_account FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE CASCADE,
    CONSTRAINT fk_signup_request_academy FOREIGN KEY (academy_id) REFERENCES academy (id) ON DELETE RESTRICT,
    CONSTRAINT ck_signup_request_approver_type CHECK (approver_type IN ('staff', 'system_admin')),
    CONSTRAINT ck_signup_request_status CHECK (status IN ('pending', 'accepted', 'rejected'))
);

-- 학원 관계자. 학원당 1명 정원을 DB 레벨에서 강제하는 자리.
CREATE TABLE academy_staff (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    academy_id bigint      NOT NULL,
    account_id bigint      NOT NULL,
    status     varchar(10) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    -- 학원당 1명 정원은 조건부 UNIQUE 라 표 제약으로 쓸 수 없다 — 아래 "조건부 UNIQUE" 절의
    -- uk_academy_staff_academy_active 인덱스가 담당한다(Ruling 139).
    CONSTRAINT uk_academy_staff_account UNIQUE (account_id),
    CONSTRAINT fk_academy_staff_academy FOREIGN KEY (academy_id) REFERENCES academy (id) ON DELETE RESTRICT,
    CONSTRAINT fk_academy_staff_account FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_academy_staff_status CHECK (status IN ('active', 'inactive'))
);

-- 메인 관리자. 전 학원 범위 권한 보유자의 명시적 등록부로, 무단 역할 변경을 관측 가능하게 한다.
CREATE TABLE system_admin (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id bigint      NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_system_admin_account UNIQUE (account_id),
    CONSTRAINT fk_system_admin_account FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE RESTRICT
);

-- 장기 토큰. 로그아웃·차단·비밀번호 변경 시 무효화를 판정하려면 서버가 발급분을 보관해야 한다.
CREATE TABLE refresh_token (
    id           bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id   bigint       NOT NULL,
    token_hash   varchar(255) NOT NULL,
    issued_at    timestamptz  NOT NULL,
    expires_at   timestamptz  NOT NULL,
    revoked_at   timestamptz,
    device_label varchar(100),
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_token_account FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE CASCADE
);

-- 승하차지 마스터. 그룹 ③ 소속이나 weekly_address(그룹 ②)가 참조해 여기로 앞당겼다.
CREATE TABLE stop (
    id         bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    academy_id bigint       NOT NULL,
    name       varchar(100) NOT NULL,
    address    varchar(255) NOT NULL,
    lat        numeric(9,6) NOT NULL,
    lng        numeric(9,6) NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT fk_stop_academy FOREIGN KEY (academy_id) REFERENCES academy (id) ON DELETE RESTRICT,
    CONSTRAINT ck_stop_lat CHECK (lat BETWEEN -90 AND 90),
    CONSTRAINT ck_stop_lng CHECK (lng BETWEEN -180 AND 180)
);


-- =====================================================================================
-- ② 학생 · 보호자 · 주소 (7)
-- =====================================================================================

-- 학생. 노선·명단·알림이 모두 참조하는 중심 레코드이며 계정 연결은 가입 승인 시점이다.
CREATE TABLE student (
    id            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    academy_id    bigint       NOT NULL,
    account_id    bigint,
    name          varchar(50)  NOT NULL,
    student_phone varchar(30),
    photo_url     varchar(255),
    gender        varchar(10),
    birth_date    date,
    grade         varchar(20),
    class_name    varchar(50),
    seat_no       integer,
    note          text,
    can_go_alone  boolean      NOT NULL DEFAULT false,
    deleted_at    timestamptz,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT fk_student_academy FOREIGN KEY (academy_id) REFERENCES academy (id) ON DELETE RESTRICT,
    CONSTRAINT fk_student_account FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE SET NULL,
    CONSTRAINT ck_student_gender CHECK (gender IN ('male', 'female'))
);

-- 보호자. 자녀 N명 연결의 기준점이며 student_id 를 직접 부착하지 않는다.
CREATE TABLE guardian (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    academy_id bigint      NOT NULL,
    account_id bigint      NOT NULL,
    name       varchar(50) NOT NULL,
    phone      varchar(30) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_guardian_academy FOREIGN KEY (academy_id) REFERENCES academy (id) ON DELETE RESTRICT,
    -- account_id 가 NN 이라 SET NULL 을 걸면 계정 삭제 시 NOT NULL 위반으로 실패한다 (ERD §4.1 정정, 2026-08-25).
    CONSTRAINT fk_guardian_account FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE RESTRICT
);

-- 보호자 ↔ 학생 연결. 다자녀를 재가입 없이 연결 추가로 처리하는 유일 경로.
CREATE TABLE guardian_student (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    guardian_id bigint      NOT NULL,
    student_id  bigint      NOT NULL,
    linked_at   timestamptz NOT NULL,
    unlinked_at timestamptz,
    CONSTRAINT uk_guardian_student UNIQUE (guardian_id, student_id),
    CONSTRAINT fk_guardian_student_guardian FOREIGN KEY (guardian_id) REFERENCES guardian (id) ON DELETE CASCADE,
    CONSTRAINT fk_guardian_student_student FOREIGN KEY (student_id) REFERENCES student (id) ON DELETE RESTRICT
);

-- 자녀 연결 요청. 요청 → 코드 생성 → 코드 입력 3단계 중 첫 단계가 자체 식별자와 만료 시각을 반환한다.
CREATE TABLE link_request (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    guardian_id  bigint      NOT NULL,
    student_id   bigint      NOT NULL,
    requested_at timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL,
    status       varchar(10) NOT NULL,
    CONSTRAINT fk_link_request_guardian FOREIGN KEY (guardian_id) REFERENCES guardian (id) ON DELETE CASCADE,
    CONSTRAINT fk_link_request_student FOREIGN KEY (student_id) REFERENCES student (id) ON DELETE CASCADE,
    CONSTRAINT ck_link_request_status CHECK (status IN ('pending', 'completed', 'expired'))
);

-- 아이디·비밀번호 복구 인증 코드. 만료·불일치를 서버가 판정하려면 발급분을 보관해야 한다.
CREATE TABLE verification_code (
    id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    phone         varchar(20) NOT NULL,
    code          varchar(10) NOT NULL,
    purpose       varchar(20) NOT NULL,
    expires_at    timestamptz NOT NULL,
    consumed_at   timestamptz,
    attempt_count integer     NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_verification_code_purpose CHECK (purpose IN ('login_id', 'password'))
);

-- 자녀 연결 인증 코드. 코드 대조를 서버가 수행하는 전제라 발급분을 서버가 보관한다.
CREATE TABLE link_code (
    id              bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    link_request_id bigint      NOT NULL,
    code            varchar(10) NOT NULL,
    expires_at      timestamptz NOT NULL,
    used_at         timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_link_code_link_request FOREIGN KEY (link_request_id) REFERENCES link_request (id) ON DELETE CASCADE
);

-- 요일별 등하원 주소. 기본 주소 개념이 부재하고 요일 × 방향이 노선 산출의 유일한 기준이다.
CREATE TABLE weekly_address (
    id             bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    student_id     bigint       NOT NULL,
    weekday        varchar(3)   NOT NULL,
    direction      varchar(20)  NOT NULL,
    address        varchar(255) NOT NULL,
    address_detail varchar(255),
    lat            numeric(9,6),
    lng            numeric(9,6),
    verified       boolean      NOT NULL DEFAULT false,
    stop_id        bigint,
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uk_weekly_address_student_weekday_direction UNIQUE (student_id, weekday, direction),
    CONSTRAINT fk_weekly_address_student FOREIGN KEY (student_id) REFERENCES student (id) ON DELETE CASCADE,
    CONSTRAINT fk_weekly_address_stop FOREIGN KEY (stop_id) REFERENCES stop (id) ON DELETE SET NULL,
    CONSTRAINT ck_weekly_address_weekday CHECK (weekday IN ('mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun')),
    CONSTRAINT ck_weekly_address_direction CHECK (direction IN ('to_academy', 'from_academy'))
);


-- =====================================================================================
-- ③ 차량 · 인력 · 운행 · 노선 (13 — `stop` 은 그룹 ① 뒤에 앞당겨 정의)
-- =====================================================================================

-- 차량. 정원 초과 차단의 기준값을 보유하며 학생 정원은 CHECK 로 계산식을 강제한다.
CREATE TABLE bus (
    id               bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    academy_id       bigint      NOT NULL,
    bus_no           varchar(20) NOT NULL,
    plate_no         varchar(20) NOT NULL,
    capacity         integer     NOT NULL,
    driver_count     integer     NOT NULL DEFAULT 1,
    escort_count     integer     NOT NULL DEFAULT 1,
    student_capacity integer     NOT NULL,
    operable         boolean     NOT NULL DEFAULT true,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_bus_academy_bus_no UNIQUE (academy_id, bus_no),
    CONSTRAINT fk_bus_academy FOREIGN KEY (academy_id) REFERENCES academy (id) ON DELETE RESTRICT,
    CONSTRAINT ck_bus_student_capacity CHECK (student_capacity = capacity - driver_count - escort_count),
    CONSTRAINT ck_bus_capacity CHECK (capacity > driver_count + escort_count)
);

-- 운행인력. 기사·동승자를 한 테이블에 두고 role 로 가르며, 그 값이 앱 권한을 결정한다.
CREATE TABLE manager (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    academy_id bigint      NOT NULL,
    account_id bigint,
    name       varchar(50) NOT NULL,
    phone      varchar(30) NOT NULL,
    role       varchar(10) NOT NULL,
    work_hours jsonb,
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_manager_academy FOREIGN KEY (academy_id) REFERENCES academy (id) ON DELETE RESTRICT,
    CONSTRAINT fk_manager_account FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE SET NULL,
    CONSTRAINT ck_manager_role CHECK (role IN ('driver', 'escort'))
);

-- 운행 스케줄. 일일 회차 자동 생성의 원본이며 정규 스케줄은 불변이라 예외일 컬럼이 부재하다.
CREATE TABLE schedule (
    id               bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    academy_id       bigint       NOT NULL,
    bus_id           bigint       NOT NULL,
    weekday          varchar(3)   NOT NULL,
    direction        varchar(20)  NOT NULL,
    depart_time      time         NOT NULL,
    origin_name      varchar(100) NOT NULL,
    destination_name varchar(100) NOT NULL,
    est_duration_min integer,
    active           boolean      NOT NULL DEFAULT true,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uk_schedule_bus_weekday_direction_depart UNIQUE (bus_id, weekday, direction, depart_time),
    CONSTRAINT fk_schedule_academy FOREIGN KEY (academy_id) REFERENCES academy (id) ON DELETE RESTRICT,
    CONSTRAINT fk_schedule_bus FOREIGN KEY (bus_id) REFERENCES bus (id) ON DELETE RESTRICT,
    CONSTRAINT ck_schedule_weekday CHECK (weekday IN ('mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun')),
    CONSTRAINT ck_schedule_direction CHECK (direction IN ('to_academy', 'from_academy'))
);

-- 고정 노선. 학기 단위로 유지되는 편성이라 확정 노선과 별개 레코드로 두어 원본 오염을 막는다.
CREATE TABLE route (
    id         bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    academy_id bigint       NOT NULL,
    bus_id     bigint       NOT NULL,
    weekday    varchar(3)   NOT NULL,
    direction  varchar(20)  NOT NULL,
    name       varchar(100),
    active     boolean      NOT NULL DEFAULT true,
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uk_route_bus_weekday_direction UNIQUE (bus_id, weekday, direction),
    CONSTRAINT fk_route_academy FOREIGN KEY (academy_id) REFERENCES academy (id) ON DELETE RESTRICT,
    CONSTRAINT fk_route_bus FOREIGN KEY (bus_id) REFERENCES bus (id) ON DELETE RESTRICT,
    CONSTRAINT ck_route_weekday CHECK (weekday IN ('mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun')),
    CONSTRAINT ck_route_direction CHECK (direction IN ('to_academy', 'from_academy'))
);

-- 고정 노선의 정차 순서. 확정 노선의 정차 목록(run_stop)과 수명 주기가 달라 별도 테이블이다.
CREATE TABLE route_stop (
    id       bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    route_id bigint  NOT NULL,
    stop_id  bigint  NOT NULL,
    seq      integer NOT NULL,
    CONSTRAINT uk_route_stop_route_seq UNIQUE (route_id, seq),
    CONSTRAINT fk_route_stop_route FOREIGN KEY (route_id) REFERENCES route (id) ON DELETE CASCADE,
    CONSTRAINT fk_route_stop_stop FOREIGN KEY (stop_id) REFERENCES stop (id) ON DELETE RESTRICT
);

-- 일일 운행 회차. 3구간 판정·확정 배치·명단·위치·알림이 전부 매달리는 중심 축.
CREATE TABLE run (
    id               bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    academy_id       bigint       NOT NULL,
    bus_id           bigint       NOT NULL,
    schedule_id      bigint,
    service_date     date         NOT NULL,
    direction        varchar(20)  NOT NULL,
    depart_time      timestamptz  NOT NULL,
    confirm_at       timestamptz  NOT NULL,
    status           varchar(10)  NOT NULL,
    origin_name      varchar(100) NOT NULL,
    destination_name varchar(100) NOT NULL,
    est_duration_min integer,
    confirmed_at     timestamptz,
    started_at       timestamptz,
    finished_at      timestamptz,
    finish_pending   boolean      NOT NULL DEFAULT false,
    canceled_at      timestamptz,
    -- 확정 배치가 이 회차에서 연속으로 실패한 횟수(Phase 7 목표 4) — 배치 재시작으로 사라지면 안 되는
    -- 값이라 인메모리가 아니라 이 컬럼에 둔다. 확정에 성공하면 0으로 되돌아간다.
    consecutive_failures integer   NOT NULL DEFAULT 0,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uk_run_bus_date_direction_depart UNIQUE (bus_id, service_date, direction, depart_time),
    CONSTRAINT fk_run_academy FOREIGN KEY (academy_id) REFERENCES academy (id) ON DELETE RESTRICT,
    CONSTRAINT fk_run_bus FOREIGN KEY (bus_id) REFERENCES bus (id) ON DELETE RESTRICT,
    CONSTRAINT fk_run_schedule FOREIGN KEY (schedule_id) REFERENCES schedule (id) ON DELETE SET NULL,
    CONSTRAINT ck_run_direction CHECK (direction IN ('to_academy', 'from_academy')),
    CONSTRAINT ck_run_status CHECK (status IN ('idle', 'confirmed', 'moving', 'finished')),
    CONSTRAINT ck_run_confirm_at CHECK (confirm_at = depart_time - interval '30 minutes')
);

-- 강제 경유 지점. 학생 주소로 표현되지 않는 경유 요구를 담으며 탑승자 없이 경유만 필요한 경우가 대상.
CREATE TABLE waypoint (
    id         bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id     bigint       NOT NULL,
    label      varchar(100) NOT NULL,
    address    varchar(255),
    lat        numeric(9,6) NOT NULL,
    lng        numeric(9,6) NOT NULL,
    note       varchar(200),
    applied    boolean      NOT NULL DEFAULT false,
    created_by bigint       NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now(),
    removed_at timestamptz,
    CONSTRAINT fk_waypoint_run FOREIGN KEY (run_id) REFERENCES run (id) ON DELETE CASCADE,
    CONSTRAINT ck_waypoint_lat CHECK (lat BETWEEN -90 AND 90),
    CONSTRAINT ck_waypoint_lng CHECK (lng BETWEEN -180 AND 180)
);

-- ①구간 강제 추가(RTE-06, API_SPEC §5.7) 대기소. 아직 idle 인 회차엔 run_rider 행이 없어 그
-- 확정 배치(RunConfirmationService.confirmOne)가 이 표를 읽어 그날 명단에 합친다 — change_request 를
-- 재사용하지 않는 이유는 그 표가 "이미 명단에 있는 학생의 정차지 이동"만 다뤄 "명단에 없는 학생을
-- 새로 올리는" 이 동작과 의미가 다르기 때문이다(Ruling 197).
CREATE TABLE run_forced_addition (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id     bigint      NOT NULL,
    student_id bigint      NOT NULL,
    stop_id    bigint      NOT NULL,
    added_by   bigint      NOT NULL,
    added_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_run_forced_addition_run_student UNIQUE (run_id, student_id),
    CONSTRAINT fk_run_forced_addition_run FOREIGN KEY (run_id) REFERENCES run (id) ON DELETE CASCADE,
    CONSTRAINT fk_run_forced_addition_student FOREIGN KEY (student_id) REFERENCES student (id) ON DELETE RESTRICT,
    CONSTRAINT fk_run_forced_addition_stop FOREIGN KEY (stop_id) REFERENCES stop (id) ON DELETE RESTRICT
);

-- 회차별 확정 노선. 회차와 버전 목록을 잇는 자리이자 "지금 유효한 버전"의 단일 지시자.
-- current_version_id 의 FK 는 route_version 과 순환이라 두 테이블을 만든 뒤 ALTER 로 붙인다.
CREATE TABLE confirmed_route (
    run_id             bigint      PRIMARY KEY,
    current_version_id bigint,
    confirmed_at       timestamptz NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_confirmed_route_run FOREIGN KEY (run_id) REFERENCES run (id) ON DELETE CASCADE
);

-- 확정 노선 배포 버전. 승인·경유 지정이 재최적화 후 재배포하므로 배포 단위를 식별할 레코드가 필요하다.
CREATE TABLE route_version (
    id                 bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    confirmed_route_id bigint       NOT NULL,
    version_no         integer      NOT NULL,
    source             varchar(20)  NOT NULL,
    est_duration_min   integer,
    est_distance_km    numeric(6,2),
    published_at       timestamptz,
    input_fingerprint  varchar(64)  NOT NULL,
    engine_name        varchar(30)  NOT NULL,
    policy_snapshot    jsonb        NOT NULL,
    fallback_used      boolean      NOT NULL DEFAULT false,
    created_by         bigint,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uk_route_version_confirmed_route_version_no UNIQUE (confirmed_route_id, version_no),
    CONSTRAINT fk_route_version_confirmed_route FOREIGN KEY (confirmed_route_id)
        REFERENCES confirmed_route (run_id) ON DELETE CASCADE,
    CONSTRAINT ck_route_version_source CHECK (source IN ('confirm_batch', 'approval', 'waypoint', 'transfer'))
);

-- 순환 FK. 확정 노선과 첫 배포 버전이 같은 트랜잭션에서 생기므로 즉시 검사면 어느 쪽을 먼저 넣어도 상대 행이 부재하다.
ALTER TABLE confirmed_route
    ADD CONSTRAINT fk_confirmed_route_current_version FOREIGN KEY (current_version_id)
        REFERENCES route_version (id) ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED;

-- 회차 노선의 정차 항목. 순번·변경 구분·도착 시각은 버전마다 달라져 승하차지 마스터에 보관할 수 없다.
CREATE TABLE run_stop (
    id               bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    route_version_id bigint       NOT NULL,
    stop_id          bigint,
    waypoint_id      bigint,
    seq              integer      NOT NULL,
    change           varchar(10),
    skip_notice      varchar(200),
    arrived_at       timestamptz,
    eta              timestamptz,
    CONSTRAINT uk_run_stop_version_seq UNIQUE (route_version_id, seq),
    CONSTRAINT fk_run_stop_route_version FOREIGN KEY (route_version_id) REFERENCES route_version (id) ON DELETE CASCADE,
    CONSTRAINT fk_run_stop_stop FOREIGN KEY (stop_id) REFERENCES stop (id) ON DELETE RESTRICT,
    CONSTRAINT fk_run_stop_waypoint FOREIGN KEY (waypoint_id) REFERENCES waypoint (id) ON DELETE RESTRICT,
    CONSTRAINT ck_run_stop_target_exclusive CHECK ((stop_id IS NOT NULL) <> (waypoint_id IS NOT NULL)),
    CONSTRAINT ck_run_stop_change CHECK (change IN ('added', 'skipped'))
);

-- 회차별 탑승자. 탑승 상태 5종이 학부모 푸시·관계자 현황·알림 로그에 동시 반영되는 값의 저장처.
CREATE TABLE run_rider (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id      bigint      NOT NULL,
    student_id  bigint      NOT NULL,
    stop_id     bigint      NOT NULL,
    status      varchar(10) NOT NULL,
    change      varchar(10),
    note        text,
    seat_no     integer,
    boarded_at  timestamptz,
    alighted_at timestamptz,
    changed_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_run_rider_run_student UNIQUE (run_id, student_id),
    CONSTRAINT fk_run_rider_run FOREIGN KEY (run_id) REFERENCES run (id) ON DELETE CASCADE,
    CONSTRAINT fk_run_rider_student FOREIGN KEY (student_id) REFERENCES student (id) ON DELETE RESTRICT,
    CONSTRAINT fk_run_rider_stop FOREIGN KEY (stop_id) REFERENCES stop (id) ON DELETE RESTRICT,
    CONSTRAINT ck_run_rider_status CHECK (status IN ('waiting', 'boarded', 'alighted', 'absent', 'no_show')),
    CONSTRAINT ck_run_rider_change CHECK (change IN ('added', 'removed'))
);

-- 회차별 매니저 배치. 확인 응답을 버전과 함께 기록하지 않으면 재배포 후에도 확인 완료로 남는 오판이 생긴다.
CREATE TABLE assignment (
    id                     bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id                 bigint      NOT NULL,
    manager_id             bigint      NOT NULL,
    role                   varchar(10) NOT NULL,
    assigned_at            timestamptz NOT NULL,
    assigned_by            bigint,
    acked_route_version_id bigint,
    acked_at               timestamptz,
    CONSTRAINT uk_assignment_run_role UNIQUE (run_id, role),
    CONSTRAINT fk_assignment_run FOREIGN KEY (run_id) REFERENCES run (id) ON DELETE CASCADE,
    CONSTRAINT fk_assignment_manager FOREIGN KEY (manager_id) REFERENCES manager (id) ON DELETE RESTRICT,
    CONSTRAINT fk_assignment_acked_route_version FOREIGN KEY (acked_route_version_id)
        REFERENCES route_version (id) ON DELETE SET NULL,
    CONSTRAINT ck_assignment_role CHECK (role IN ('driver', 'escort'))
);


-- =====================================================================================
-- ④ 요청 · 예외 · 알림 · 이력 (12)
-- =====================================================================================

-- 회차별 탑승 의사. 확정 배치가 읽는 입력 두 축 중 하나이며 ②구간 한도 카운터를 함께 들고 있다.
CREATE TABLE boarding_intent (
    id                bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id            bigint      NOT NULL,
    student_id        bigint      NOT NULL,
    riding            boolean     NOT NULL DEFAULT true,
    change_used_count integer     NOT NULL DEFAULT 0,
    applied_segment   smallint,
    changed_at        timestamptz,
    changed_by        bigint,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_boarding_intent_run_student UNIQUE (run_id, student_id),
    CONSTRAINT fk_boarding_intent_run FOREIGN KEY (run_id) REFERENCES run (id) ON DELETE CASCADE,
    CONSTRAINT fk_boarding_intent_student FOREIGN KEY (student_id) REFERENCES student (id) ON DELETE RESTRICT,
    CONSTRAINT ck_boarding_intent_change_used_count CHECK (change_used_count BETWEEN 0 AND 1)
);

-- 변경 요청·승인 대기. ②구간 승인 큐의 실체이자 책임 소재 기록이며 토글과 일일 변경을 source 로 가른다.
CREATE TABLE change_request (
    id                       bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    academy_id               bigint       NOT NULL,
    run_id                   bigint       NOT NULL,
    student_id               bigint       NOT NULL,
    source                   varchar(20)  NOT NULL,
    type                     varchar(10)  NOT NULL,
    status                   varchar(15)  NOT NULL,
    window_segment           smallint     NOT NULL,
    new_address              varchar(255),
    new_lat                  numeric(9,6),
    new_lng                  numeric(9,6),
    new_stop_id              bigint,
    reason                   varchar(200),
    requested_by             bigint       NOT NULL,
    requested_at             timestamptz  NOT NULL,
    deadline_at              timestamptz,
    decided_by               bigint,
    decided_at               timestamptz,
    reject_reason            varchar(200),
    stop_removed             boolean,
    applied_route_version_id bigint,
    CONSTRAINT fk_change_request_academy FOREIGN KEY (academy_id) REFERENCES academy (id) ON DELETE RESTRICT,
    CONSTRAINT fk_change_request_run FOREIGN KEY (run_id) REFERENCES run (id) ON DELETE CASCADE,
    CONSTRAINT fk_change_request_student FOREIGN KEY (student_id) REFERENCES student (id) ON DELETE RESTRICT,
    CONSTRAINT fk_change_request_new_stop FOREIGN KEY (new_stop_id) REFERENCES stop (id) ON DELETE SET NULL,
    CONSTRAINT fk_change_request_applied_route_version FOREIGN KEY (applied_route_version_id)
        REFERENCES route_version (id) ON DELETE SET NULL,
    CONSTRAINT ck_change_request_source CHECK (source IN ('intent', 'change_request')),
    CONSTRAINT ck_change_request_type CHECK (type IN ('relocate', 'cancel')),
    CONSTRAINT ck_change_request_status CHECK (status IN ('pending', 'approved', 'rejected', 'auto_rejected')),
    CONSTRAINT ck_change_request_reject_reason CHECK (status <> 'rejected' OR reject_reason IS NOT NULL),
    CONSTRAINT ck_change_request_new_address CHECK (type <> 'relocate' OR new_address IS NOT NULL)
);

-- 승하차 상태 변경 이력. 되돌리기가 이력 보존 전제이며 오프라인 큐의 멱등키 보관처이기도 하다.
-- run_rider·account 는 논리적 부모이나 FK 미설정 (ERD §4.2 — 대량 적재 + 부모와 다른 보존 주기).
CREATE TABLE rider_status_history (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_rider_id bigint      NOT NULL,
    from_status  varchar(10),
    to_status    varchar(10) NOT NULL,
    is_revert    boolean     NOT NULL DEFAULT false,
    reason       varchar(200),
    verify_method varchar(10),
    client_key   uuid,
    occurred_at  timestamptz,
    changed_at   timestamptz NOT NULL,
    actor_type   varchar(10) NOT NULL,
    changed_by   bigint,
    CONSTRAINT uk_rider_status_history_client_key UNIQUE (client_key),
    CONSTRAINT ck_rider_status_history_verify_method CHECK (verify_method IN ('photo', 'manual')),
    CONSTRAINT ck_rider_status_history_actor_type CHECK (actor_type IN ('escort', 'system'))
);

-- 미승차 에스컬레이션 케이스. 대기 만료를 컬럼으로 고정해 학원 설정이 바뀌어도 발생 당시 기준을 재현한다.
CREATE TABLE no_show_case (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_rider_id bigint      NOT NULL,
    started_at   timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL,
    resolved_at  timestamptz,
    decision     varchar(10),
    escalated_at timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_no_show_case_run_rider UNIQUE (run_rider_id),
    CONSTRAINT fk_no_show_case_run_rider FOREIGN KEY (run_rider_id) REFERENCES run_rider (id) ON DELETE CASCADE,
    CONSTRAINT ck_no_show_case_decision CHECK (decision IN ('depart', 'retry'))
);

-- 미승차 연락 시도. 시도가 복수 회 발생하고 각 결과가 카운트다운 중단 판정에 쓰인다.
CREATE TABLE no_show_contact (
    id              bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    no_show_case_id bigint      NOT NULL,
    attempt_type    varchar(10) NOT NULL,
    result          varchar(10) NOT NULL,
    decision        varchar(10),
    attempted_at    timestamptz NOT NULL,
    attempted_by    bigint      NOT NULL,
    CONSTRAINT fk_no_show_contact_case FOREIGN KEY (no_show_case_id) REFERENCES no_show_case (id) ON DELETE CASCADE,
    CONSTRAINT ck_no_show_contact_attempt_type CHECK (attempt_type IN ('call', 'message')),
    CONSTRAINT ck_no_show_contact_result CHECK (result IN ('answered', 'no_answer')),
    CONSTRAINT ck_no_show_contact_decision CHECK (decision IN ('depart', 'retry'))
);

-- 비상 알림. 위치·탑승자 수·발신 시각을 스냅샷으로 고정해 부모 없이도 자립한다.
-- run·account·academy 는 논리적 부모이나 FK 미설정 (ERD §4.2).
CREATE TABLE emergency_alert (
    id             bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    academy_id     bigint       NOT NULL,
    run_id         bigint       NOT NULL,
    bus_no         varchar(20)  NOT NULL,
    raised_by      bigint       NOT NULL,
    raised_by_role varchar(20)  NOT NULL,
    type           varchar(20)  NOT NULL,
    memo           text,
    lat            numeric(9,6),
    lng            numeric(9,6),
    rider_count    integer      NOT NULL,
    occurred_at    timestamptz  NOT NULL,
    received_at    timestamptz  NOT NULL DEFAULT now(),
    client_key     uuid         NOT NULL,
    acked_by       bigint,
    acked_at       timestamptz,
    canceled_at    timestamptz,
    CONSTRAINT uk_emergency_alert_client_key UNIQUE (client_key),
    CONSTRAINT ck_emergency_alert_type CHECK (type IN ('accident', 'vehicle_fault', 'student_emergency', 'etc')),
    CONSTRAINT ck_emergency_alert_memo CHECK (type <> 'etc' OR memo IS NOT NULL)
);

-- 예외 보고. 보호자 부재·현장 상황을 관계자에게 통지하고 사후 확인 가능한 형태로 남긴다.
-- run·run_rider·academy 는 논리적 부모이나 FK 미설정 (ERD §4.2).
CREATE TABLE exception_report (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    academy_id   bigint      NOT NULL,
    run_id       bigint      NOT NULL,
    run_rider_id bigint,
    type         varchar(20) NOT NULL,
    memo         text        NOT NULL,
    reported_by  bigint      NOT NULL,
    reported_at  timestamptz NOT NULL,
    CONSTRAINT ck_exception_report_type CHECK (type IN ('guardian_absent', 'road_block', 'vehicle_issue', 'etc')),
    CONSTRAINT ck_exception_report_run_rider CHECK (type <> 'guardian_absent' OR run_rider_id IS NOT NULL)
);

-- 운행 중 버스 위치. 송신 주기가 5~10초라 한 회차에 수백 행이 쌓이는 최대 적재 테이블.
-- run 은 논리적 부모이나 FK 미설정 (ERD §4.2 — 파티션 단위 DROP 으로 정리해야 한다).
CREATE TABLE run_position (
    id          bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id      bigint       NOT NULL,
    lat         numeric(9,6) NOT NULL,
    lng         numeric(9,6) NOT NULL,
    recorded_at timestamptz  NOT NULL,
    received_at timestamptz  NOT NULL,
    speed       numeric(5,2),
    heading     numeric(5,2),
    CONSTRAINT ck_run_position_lat CHECK (lat BETWEEN -90 AND 90),
    CONSTRAINT ck_run_position_lng CHECK (lng BETWEEN -180 AND 180)
);

-- 알림 로그. 발송 사실의 근거이자 트랜잭셔널 아웃박스 — 상태 변경과 같은 트랜잭션에서 pending 행을 남긴다.
-- account·student·run·academy 는 논리적 부모이나 FK 미설정 (ERD §4.2 — 보존 14일, 이름 스냅샷으로 자립).
CREATE TABLE notification_log (
    id                   bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    academy_id           bigint       NOT NULL,
    recipient_account_id bigint       NOT NULL,
    recipient_name       varchar(50)  NOT NULL,
    recipient_role       varchar(20)  NOT NULL,
    student_id           bigint,
    student_name         varchar(50),
    run_id               bigint,
    bus_no               varchar(20),
    type                 varchar(30)  NOT NULL,
    title                varchar(200) NOT NULL,
    body                 text         NOT NULL,
    popup                boolean      NOT NULL DEFAULT false,
    push_state           varchar(10)  NOT NULL DEFAULT 'pending',
    push_attempts        integer      NOT NULL DEFAULT 0,
    last_attempt_at      timestamptz,
    fail_reason          varchar(200),
    dedup_key            varchar(120) NOT NULL,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    sent_at              timestamptz,
    read_at              timestamptz,
    acked                boolean      NOT NULL DEFAULT false,
    acked_at             timestamptz,
    CONSTRAINT uk_notification_log_dedup_key UNIQUE (dedup_key),
    CONSTRAINT ck_notification_log_type CHECK (type IN (
        'boarding', 'alighting', 'no_show', 'absent', 'arrive', 'delay',
        'run_started', 'run_ended', 'signup_decided', 'change_decided',
        'approval_requested', 'intent_changed', 'link_requested', 'route_changed',
        'assignment_changed', 'no_show_escalated', 'emergency', 'emergency_canceled')),
    CONSTRAINT ck_notification_log_push_state CHECK (push_state IN ('pending', 'sent', 'failed', 'skipped'))
);

-- 푸시 수신 단말. 서버가 발송 대상 단말을 특정하는 자리이며 토큰 갱신은 행 대체로 처리한다.
CREATE TABLE device_token (
    id          bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id  bigint       NOT NULL,
    device_id   varchar(100) NOT NULL,
    token       text         NOT NULL,
    platform    varchar(10)  NOT NULL,
    app_version varchar(20),
    revoked_at  timestamptz,
    last_used_at timestamptz,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uk_device_token_account_device UNIQUE (account_id, device_id),
    CONSTRAINT fk_device_token_account FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE CASCADE,
    CONSTRAINT ck_device_token_platform CHECK (platform IN ('android', 'ios', 'web'))
);

-- 알림 설정. on/off 대상이 3종으로 고정이고 학부모·학생 계정에만 행이 생겨 별도 테이블이다.
CREATE TABLE notification_setting (
    account_id bigint      PRIMARY KEY,
    arrive     boolean     NOT NULL DEFAULT true,
    boarding   boolean     NOT NULL DEFAULT true,
    no_show    boolean     NOT NULL DEFAULT true,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_notification_setting_account FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE CASCADE
);

-- 감사·접속 이력. 개인정보 조회·수정 이력과 로그인·차단 이력을 category 로 한 테이블에 담는다.
-- account·academy 는 논리적 부모이나 FK 미설정 (ERD §4.2 — 감사 대상 삭제에 연동되면 기록의 목적이 소멸).
CREATE TABLE audit_log (
    id               bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    academy_id       bigint,
    actor_account_id bigint,
    actor_login_id   varchar(50),
    category         varchar(20) NOT NULL,
    action           varchar(20) NOT NULL,
    target_type      varchar(50),
    target_id        bigint,
    ip               inet,
    block_event      boolean     NOT NULL DEFAULT false,
    detail           jsonb,
    occurred_at      timestamptz NOT NULL,
    CONSTRAINT ck_audit_log_category CHECK (category IN ('data_access', 'login')),
    CONSTRAINT ck_audit_log_action CHECK (action IN (
        'read', 'update', 'delete', 'login_success', 'login_fail', 'block', 'unblock'))
);


-- =====================================================================================
-- 조건부 UNIQUE (ERD §5.1) — 계정 연결은 가입 승인 시점이라 그 전에는 account_id 가 NULL 이다.
-- 셋을 다르게 선언하면 "왜 하나만 다른가" 를 매번 되짚어야 하므로 조건을 통일한다.
-- =====================================================================================

CREATE UNIQUE INDEX uk_student_account_id ON student (account_id) WHERE account_id IS NOT NULL;
CREATE UNIQUE INDEX uk_guardian_account_id ON guardian (account_id) WHERE account_id IS NOT NULL;
CREATE UNIQUE INDEX uk_manager_account_id ON manager (account_id) WHERE account_id IS NOT NULL;

-- 학원당 관계자 정원 1명(C-01 · ACAD-05). 표 제약이 아니라 인덱스인 이유는 조건부 UNIQUE 를 표
-- 제약으로 쓸 수 없기 때문이고, 조건이 필요한 이유는 정원이 "행 1개" 가 아니라 "재직자 1명" 이기
-- 때문이다 — 조건 없이 걸면 퇴사(status='inactive', ACAD-06) 뒤 그 학원은 새 관계자를 영원히
-- 승인할 수 없다(Ruling 139). uk_academy_staff_account 는 account_id 가 NOT NULL 이라 조건 없이 둔다.
CREATE UNIQUE INDEX uk_academy_staff_academy_active ON academy_staff (academy_id) WHERE status = 'active';


-- =====================================================================================
-- 인덱스 (ERD §5.3) — UNIQUE 제약이 겸하는 것(run_stop(route_version_id, seq) ·
-- weekly_address(student_id, weekday, direction))은 중복 생성하지 않는다.
-- =====================================================================================

-- 확정 배치가 30초마다 "실행 시각이 지난 회차" 를 조회한다. 부재하면 전체 회차 전수 스캔.
CREATE INDEX ix_run_status_confirm_at ON run (status, confirm_at);
CREATE INDEX ix_run_academy_date_depart ON run (academy_id, service_date, depart_time);
CREATE INDEX ix_run_bus_date ON run (bus_id, service_date);
CREATE INDEX ix_run_moving ON run (status) WHERE status = 'moving';

CREATE INDEX ix_run_rider_run_status ON run_rider (run_id, status);
CREATE INDEX ix_run_rider_student_run ON run_rider (student_id, run_id);
CREATE INDEX ix_run_stop_stop ON run_stop (stop_id);

CREATE INDEX ix_change_request_academy_status ON change_request (academy_id, status);
CREATE INDEX ix_change_request_run_status ON change_request (run_id, status);
-- 자동 거절 폴링이 30초마다 마감 도래분을 전역 조회한다 — run_id 선행 인덱스로는 지원 불가.
CREATE INDEX ix_change_request_status_deadline ON change_request (status, deadline_at);
CREATE INDEX ix_change_request_student_requested ON change_request (student_id, requested_at DESC);

-- 미승차 에스컬레이션 폴링. 부분 인덱스라 종결된 케이스는 색인 대상 밖이다.
CREATE INDEX ix_no_show_case_expires ON no_show_case (expires_at) WHERE escalated_at IS NULL;

CREATE INDEX ix_emergency_alert_academy_acked_received
    ON emergency_alert (academy_id, acked_at, received_at DESC);

-- 발송 시 계정의 유효 토큰 전량 조회. 부분 인덱스라 해지분은 색인 대상 밖이다.
CREATE INDEX ix_device_token_account_active ON device_token (account_id) WHERE revoked_at IS NULL;

-- 아웃박스 워커가 미발송분을 폴링한다. 부분 인덱스라 발송 완료분은 색인 대상 밖이다.
CREATE INDEX ix_notification_log_pending ON notification_log (push_state, created_at) WHERE push_state = 'pending';
CREATE INDEX ix_notification_log_recipient_created ON notification_log (recipient_account_id, created_at DESC);
CREATE INDEX ix_notification_log_academy_sent ON notification_log (academy_id, sent_at DESC);
CREATE INDEX ix_notification_log_academy_unacked ON notification_log (academy_id, acked) WHERE acked = false;

CREATE INDEX ix_run_position_run_recorded ON run_position (run_id, recorded_at DESC);

CREATE INDEX ix_assignment_manager_run ON assignment (manager_id, run_id);
CREATE INDEX ix_assignment_run ON assignment (run_id);

CREATE INDEX ix_student_academy_name ON student (academy_id, name) WHERE deleted_at IS NULL;
-- 주소 검증 후 승하차지 매칭 — 좌표 근접 탐색.
CREATE INDEX ix_stop_academy_coord ON stop (academy_id, lat, lng);

CREATE INDEX ix_guardian_student_guardian ON guardian_student (guardian_id);
CREATE INDEX ix_guardian_student_student ON guardian_student (student_id);

CREATE INDEX ix_boarding_intent_run ON boarding_intent (run_id);
CREATE INDEX ix_signup_request_academy_status_requested ON signup_request (academy_id, status, requested_at);

CREATE INDEX ix_audit_log_academy_occurred ON audit_log (academy_id, occurred_at DESC);
CREATE INDEX ix_audit_log_actor_occurred ON audit_log (actor_account_id, occurred_at DESC);

CREATE INDEX ix_rider_status_history_rider_changed ON rider_status_history (run_rider_id, changed_at DESC);

-- 로그아웃·차단 시 유효 토큰 전량 무효화. 부분 인덱스라 무효화된 토큰은 색인 대상 밖이다.
CREATE INDEX ix_refresh_token_account_active ON refresh_token (account_id) WHERE revoked_at IS NULL;
