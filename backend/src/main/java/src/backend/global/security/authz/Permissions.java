package src.backend.global.security.authz;

/**
 * 인가 권한(permission) 문자열 상수 20종.
 *
 * <p>컨트롤러의 인가는 "누구인가"(역할)가 아니라 "무엇을 할 수 있는가"(권한)로 표현한다.
 * 역할 이름을 여기에 넣지 않는 이유가 핵심이다 — {@code admin:all} 같은 이름을 쓰면
 * 역할 문자열을 컨트롤러에서 이 파일로 옮겼을 뿐 "새 역할이 관리자 기능의 *일부만* 받는다"가
 * 여전히 불가능해서, 간접층을 만든 이유의 절반이 사라진다.
 * 이름은 {@code <자원>:<행위>} 형식(콜론 구분, 소문자)으로만 짓는다.
 *
 * <p>여기 값들은 두 곳에서 함께 쓰인다 — 메타 애너테이션의 {@code @PreAuthorize} 표현식과
 * {@link RolePermissions} 의 부여표다. 둘 다 이 상수를 참조하므로 permission 이름을 잘못 적으면
 * 런타임 403 이 아니라 <b>컴파일 에러</b>가 난다. 양쪽에 문자열을 따로 적으면 이 이점이 사라지니
 * 리터럴을 직접 쓰지 말고 반드시 이 상수를 참조한다.
 *
 * <p>애너테이션 값은 컴파일 타임 상수식만 허용하므로 모든 필드는 {@code static final String}
 * 리터럴이어야 한다(메서드 호출이나 연산 결과를 넣으면 애너테이션에서 못 쓴다).
 */
public final class Permissions {

    private Permissions() {
    }

    // ── 학원 관리자 묶음 (현재 hasAnyRole('ACADEMY_ADMIN','PLATFORM_ADMIN') 31곳) ──
    // 자원 기준으로 갈라 두면 "학생만 관리하는 학원 직원" 같은 역할을 부여표 한 줄로 만들 수 있다.

    /** 학생 등록·수정·퇴원·배정·보호자 연결 — StudentController 전체. */
    public static final String STUDENT_MANAGE = "student:manage";

    /** 구성원(계정·역할) 등록·수정·삭제·비밀번호 초기화 — MemberController 전체. */
    public static final String MEMBER_MANAGE = "member:manage";

    /** 버스 등록·조회·배차 변경 — BusController 의 관리자 기본값. */
    public static final String BUS_MANAGE = "bus:manage";

    /** 노선·정류장 생성과 조회. */
    public static final String ROUTE_MANAGE = "route:manage";

    /** 노선 계획 생성·자동배정·시뮬레이션·승인·배포. */
    public static final String ROUTE_PLAN_MANAGE = "routeplan:manage";

    /** 결석·휴원 신고의 승인·반려와 학원 단위 이력 조회. */
    public static final String ATTENDANCE_MANAGE = "attendance:manage";

    /** 시간 변경·하차 위치 변경 요청의 승인·반려와 학원 단위 이력 조회. */
    public static final String SCHEDULE_MANAGE = "schedule:manage";

    /** SOS 확인·종료와 학원 단위 이력 조회. */
    public static final String SOS_MANAGE = "sos:manage";

    /** 학원 상세 조회(플랫폼 관리자의 학원 생성·수정과 구분되는 읽기 전용). */
    public static final String TENANT_READ = "tenant:read";

    /**
     * 학원 단위 관제 읽기 — 학생·버스 위치, 알림, 승하차 이력, 운행 이력.
     * 도메인 4개에 걸쳐 있지만 "관제 화면에서 보는 것" 한 덩어리라 하나로 묶었다.
     */
    public static final String OPERATIONS_MONITOR = "operations:monitor";

    // ── 플랫폼 전용 (현재 hasRole('PLATFORM_ADMIN') 3곳) ──

    /** 학원 생성·전체 목록 조회·depot 좌표 설정. 플랫폼 관리자 전용. */
    public static final String TENANT_MANAGE = "tenant:manage";

    // ── 학부모 (현재 hasRole('PARENT') 11곳) ──

    /** 자녀의 위치·승하차·알림·신고·요청 이력 조회. */
    public static final String GUARDIAN_CHILDREN_READ = "guardian:children:read";

    /**
     * 결석 신고·하차 위치 변경·시간 변경 요청 제출.
     * 읽기와 나눈 근거: 요청 제출은 상태를 바꾸는 쓰기이고 승인 워크플로를 발생시킨다.
     * 조회 전용 보호자 계정(예: 조부모)이 생기면 여기서 갈라진다.
     */
    public static final String GUARDIAN_REQUEST_SUBMIT = "guardian:request:submit";

    // ── 학생 (현재 hasRole('STUDENT') 5곳) ──

    /** 본인의 위치·승하차·SOS 이력 조회({@code /me} 3종). */
    public static final String SELF_READ = "self:read";

    /** 본인 좌표 보고. */
    public static final String SELF_LOCATION_REPORT = "self:location:report";

    /**
     * SOS 발신.
     * 따로 뗀 근거: 유일한 긴급 알림 유발 기능이라 향후 선탑자·기사에게 열릴 가능성이 가장 높다.
     */
    public static final String SOS_TRIGGER = "sos:trigger";

    // ── 운행 담당 (현재 hasAnyRole('DRIVER','ATTENDANT') 5곳 / hasRole('DRIVER') 4곳) ──

    /** 담당 버스의 배차·명단·운행 이력·승하차·배포 노선 조회. 기사와 선탑자 공통. */
    public static final String CREW_ASSIGNED_READ = "crew:assigned:read";

    /**
     * 운행 시작·종료, 버스 위치 보고, 담당 버스 학생 위치 조회. <b>운전기사 단독</b>이다.
     * 담당 버스 학생 위치를 {@link #CREW_ASSIGNED_READ} 가 아니라 여기 둔 이유는
     * 현재 허용 역할이 DRIVER 단독이기 때문이다 — 저쪽으로 옮기면 선탑자에게 없던 권한이 생긴다.
     */
    public static final String DRIVE_OPERATE = "drive:operate";

    // ── 승하차 기록 ──

    /** 승하차 기록. 선탑자 단독 — 관리자도 대신 기록하지 못한다. */
    public static final String RIDE_RECORD = "ride:record";

    /** 승하차 기록 정정. 선탑자 + 관리자 2종. */
    public static final String RIDE_CORRECT = "ride:correct";
}
