package src.backend.global.common;

/**
 * {@code db/migration-local/V2__seed_data.sql} 이 적재하는 식별자·코드·로그인 아이디의 단일 원천이다.
 * {@code @Schema(example = SeedFixtures.PARENT_A1_LOGIN_ID)} 처럼 Swagger 예시값이 이 상수를 직접
 * 참조하게 해, 시드가 바뀌어도 사람이 손으로 옮겨 적지 않게 한다({@code IMPLEMENTATION_PLAN.md §3.3}).
 *
 * <p>모든 상수는 {@code public static final String} 이다 — bigint PK 도 문자열로 담는다. 애너테이션
 * 값은 컴파일 타임 상수식만 허용하므로, 타입을 섞으면 "애너테이션에 쓸 수 있는 상수"와 "못 쓰는
 * 상수"가 갈린다. bigint 컬럼과 비교할 때는 호출부가 {@code Long.parseLong(...)} 로 변환한다
 * ({@code SeedFixturesContractTest#조회한다} 참고).
 *
 * <p><b>시각 값은 상수 대상 밖이다.</b> 시드의 출발 시각(depart_time 등)은 {@code now()} 기준 상대값이라
 * 기동마다 달라진다 — 상수로 만들면 이 클래스를 참조하는 계약 테스트가 매 기동마다 실패한다. Swagger
 * 의 시각 예시는 형식만 보여주는 값으로 남기고 대조 대상에서 제외한다.
 *
 * <p><b>이 클래스가 보장하는 것은 "겹① 값 실재 대조"뿐이다({@code IMPLEMENTATION_PLAN.md §3.3}
 * 이 규정한 세 겹 중 하나).</b> 상수는 컴파일 시점에 인라인되므로, 런타임에는
 * {@code @Schema(example = SeedFixtures.PARENT_A1_LOGIN_ID)} 와 {@code @Schema(example = "parentA1")}
 * 를 구분할 수 없다 — 즉 "이 상수를 실제로 참조했는가"는 검증 불가능하다. 그래서 겹②
 * ({@code SwaggerExampleSeedContractTest}, 미착수)는 그 대신 "문서에 실린 예시값이 이 상수
 * 사전에 속하는가"를 본다. 겹③(소스 규약, 미착수)은 컨트롤러 소스에서 리터럴 사용 지점을 찾아
 * 겹②를 보강한다. 두 겹 다 Phase 1 시점에는 대상 컨트롤러가 0개라 "검사할 것이 없어 항상
 * 초록불"인 거짓 통과가 되므로 만들지 않았다 — Phase 2 가 실제 컨트롤러를 붙인 뒤에 착수한다.
 */
public final class SeedFixtures {

    private SeedFixtures() {
    }

    // ── 학원 3곳 ──────────────────────────────────────────────────────────
    // A(active, 주 시나리오) · B(active, 격리 검증용 독립 계통) · C(inactive, O-01 로그인 유지 시연)

    /** 학원 A(주 시나리오, {@code active}) 의 bigint PK. */
    public static final String ACADEMY_A_ID = "1";

    /** 학원 A 의 업무 코드. */
    public static final String ACADEMY_A_CODE = "BARAEDA-A";

    /** 학원 B(격리 검증용 독립 계통, {@code active}) 의 bigint PK. */
    public static final String ACADEMY_B_ID = "2";

    /** 학원 B 의 업무 코드. */
    public static final String ACADEMY_B_CODE = "BARAEDA-B";

    /** 학원 C({@code inactive}, O-01 "비활성화 후 로그인 유지" 시연용) 의 bigint PK. */
    public static final String ACADEMY_C_ID = "3";

    /** 학원 C 의 업무 코드. */
    public static final String ACADEMY_C_CODE = "BARAEDA-C";

    // ── 계정 — 역할 6종 × 상태 4종 ────────────────────────────────────────
    // 상수 이름이 role·status·소속 학원까지 말한다 — 계약 테스트가 이름이 약속하는 조합 전부를 검증한다.

    /** 플랫폼 관리자({@code system_admin}, {@code active}, 학원 미소속) 로그인 아이디. */
    public static final String SYSTEM_ADMIN_LOGIN_ID = "sysadmin";

    /** 학원 A 관리자({@code staff}, {@code active}) 로그인 아이디. */
    public static final String STAFF_A_LOGIN_ID = "staffA";

    /** 학원 B 관리자({@code staff}, {@code active}) 로그인 아이디. */
    public static final String STAFF_B_LOGIN_ID = "staffB";

    /** 학원 A 소속, 가입 승인 대기 중({@code staff}, {@code pending}) 로그인 아이디. */
    public static final String STAFF_PENDING_LOGIN_ID = "staffPending";

    /** 학원 C({@code inactive}) 소속인데도 로그인 가능한({@code staff}, {@code active}) 계정 — O-01 시연 재료. */
    public static final String STAFF_C_LOGIN_ID = "staffC";

    /** 학원 A 보호자 1({@code parent}, {@code active}) — 형제(S1·S2)의 보호자. */
    public static final String PARENT_A1_LOGIN_ID = "parentA1";

    /** 학원 A 보호자 2({@code parent}, {@code active}) — 계정 미연결 학생(S3)의 보호자. */
    public static final String PARENT_A2_LOGIN_ID = "parentA2";

    /** 학원 A 보호자 3({@code parent}, {@code active}) — 학생 A4(S4)의 보호자. */
    public static final String PARENT_A3_LOGIN_ID = "parentA3";

    /** 학원 A 소속, 가입 승인 대기 중({@code parent}, {@code pending}) 로그인 아이디. */
    public static final String PARENT_PENDING_LOGIN_ID = "parentPending";

    /** 학원 B 보호자({@code parent}, {@code active}) — 격리 검증용. */
    public static final String PARENT_B1_LOGIN_ID = "parentB1";

    /** 학원 A 학생({@code student}, {@code active}) — 계정이 학생 레코드(S4)에 연결됨. */
    public static final String STUDENT_A4_LOGIN_ID = "studentA4";

    /** 가입이 거절된({@code student}, {@code rejected}) 로그인 아이디. */
    public static final String STUDENT_REJECTED_LOGIN_ID = "studentRejected";

    /** 학원 B 학생({@code student}, {@code active}) — 격리 검증용. */
    public static final String STUDENT_B1_LOGIN_ID = "studentB1";

    /** 학원 A 기사 1({@code driver}, {@code active}). */
    public static final String DRIVER_A1_LOGIN_ID = "driverA1";

    /** 학원 A 기사 2({@code driver}, {@code active}). */
    public static final String DRIVER_A2_LOGIN_ID = "driverA2";

    /** 로그인 실패 누적으로 차단된({@code driver}, {@code blocked}) 로그인 아이디. */
    public static final String DRIVER_BLOCKED_LOGIN_ID = "driverBlocked";

    /** 학원 B 기사({@code driver}, {@code active}) — 격리 검증용. */
    public static final String DRIVER_B1_LOGIN_ID = "driverB1";

    /** 학원 A 동승자 1({@code escort}, {@code active}). */
    public static final String ESCORT_A1_LOGIN_ID = "escortA1";

    /** 학원 A 동승자 2({@code escort}, {@code active}). */
    public static final String ESCORT_A2_LOGIN_ID = "escortA2";

    /** 학원 B 동승자({@code escort}, {@code active}) — 격리 검증용. */
    public static final String ESCORT_B1_LOGIN_ID = "escortB1";

    // ── 학생 · 보호자 ─────────────────────────────────────────────────────

    /** 다자녀 형제 중 첫째 — 계정 미연결, {@link #GUARDIAN_SIBLINGS_ID} 가 보호자다. */
    public static final String STUDENT_SIBLING_1_ID = "1";

    /** 다자녀 형제 중 둘째 — {@link #STUDENT_SIBLING_1_ID} 와 같은 보호자를 공유한다. */
    public static final String STUDENT_SIBLING_2_ID = "2";

    /** 계정이 연결되지 않은 학생({@code account_id IS NULL}) — {@code AUTH-11} 미연결 상태 시연용. */
    public static final String STUDENT_UNLINKED_ID = "3";

    /** {@link #STUDENT_SIBLING_1_ID}·{@link #STUDENT_SIBLING_2_ID} 두 형제를 함께 연결한 보호자. */
    public static final String GUARDIAN_SIBLINGS_ID = "1";

    // ── 차량 ──────────────────────────────────────────────────────────────

    /** 정원 4석(학생석 2석)으로 잔여석이 근접한 학원 A 버스 — 정원 초과 차단 시연용. */
    public static final String BUS_NEAR_FULL_ID = "2";

    // ── 회차 R1~R5 — 3구간 배치 + 상태 4종 ───────────────────────────────
    // R1=①구간(idle, 여유) · R2=②구간(confirmed, 임박) · R3=③구간(moving, 이미 출발) ·
    // R4(finished, 추가 상태) · R5(confirmed, 학원 B 격리 검증용).

    /** ①구간(출발까지 여유), 상태 {@code idle} 인 회차 — 학원 A. */
    public static final String RUN_IDLE_ID = "1";

    /** ②구간(출발 임박), 상태 {@code confirmed} 인 회차 — 학원 A. 변경요청 4종·탑승 상태 일부가 여기 연결된다. */
    public static final String RUN_CONFIRMED_ID = "2";

    /** ③구간(이미 출발), 상태 {@code moving} 인 회차 — 학원 A. */
    public static final String RUN_MOVING_ID = "3";

    /** 상태 {@code finished} 인 회차 — 학원 A. */
    public static final String RUN_FINISHED_ID = "4";

    /** 상태 {@code confirmed} 인 회차 — 학원 B. {@link #RUN_CONFIRMED_ID}(학원 A)와 짝지어 격리 검증에 쓴다. */
    public static final String RUN_CONFIRMED_ACADEMY_B_ID = "5";

    // ── 변경 요청 4종 ─────────────────────────────────────────────────────
    // 넷 다 RUN_CONFIRMED_ID(R2) 소속 — ②구간에서만 변경요청이 성립하기 때문이다(C-04).

    /** 상태 {@code pending} 인 변경요청 — 승하차 취소 의사. */
    public static final String CHANGE_REQUEST_PENDING_ID = "1";

    /** 상태 {@code approved} 인 변경요청 — 승하차지 재배치, {@code route_version} v2 배포의 계기. */
    public static final String CHANGE_REQUEST_APPROVED_ID = "2";

    /** 상태 {@code rejected} 인 변경요청 — 마감 시간 경과. */
    public static final String CHANGE_REQUEST_REJECTED_ID = "3";

    /** 상태 {@code auto_rejected} 인 변경요청 — 마감을 넘겨 시스템이 자동 반려. */
    public static final String CHANGE_REQUEST_AUTO_REJECTED_ID = "4";
}
