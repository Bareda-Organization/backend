package src.backend.global.security.authz;

/**
 * 인가 권한(permission) 문자열 상수 — FEATURE_SPEC §6.2 권한 카탈로그를 그대로 옮긴다.
 *
 * <p>상수는 29종이다. §6.2 표에서 {@code MANAGER_MANAGE}·{@code BUS_MANAGE}·{@code SCHEDULE_MANAGE} 처럼 한
 * 표 행에 권한 이름이 여럿인 행이 있어 표의 행 수는 더 적다. 이 클래스(와 엔드포인트 → 권한 표)가 기준이다.
 *
 * <p>운행 시작 · 도착 처리 · 승하차 · 되돌리기 · 지연 알림 · 예외 보고는 권한 상수가 없다 — 역할과 그 회차 배치를
 * 서비스 계층({@code RunAssignmentAccess} 등)이 확인해 {@code DRIVER_ONLY} · {@code ESCORT_ONLY} · {@code FORBIDDEN} 으로
 * 답한다. 엔드포인트가 쓰지 않는 상수는 두지 않는다({@code EndpointPermissionTableTest} 가 고정한다).
 *
 * <p>이름은 §6.2 표기를 그대로 쓴다 — 표와 코드 사이에 별도 변환 규칙(콜론 표기 등)을 두면
 * 대조가 사람이 손으로 하는 일이 된다. {@link RolePermissions} 의 부여표와 컨트롤러 메타
 * 애너테이션의 {@code @PreAuthorize} 표현식이 이 상수를 함께 참조해, 오탈자는 런타임 403 이 아니라
 * 컴파일 에러가 된다. 애너테이션 값은 컴파일 타임 상수식만 허용하므로 모든 필드는
 * {@code static final String} 리터럴이어야 한다.
 */
public final class Permissions {

    private Permissions() {
    }

    // ── 학생 정보 ──

    /** 학생 이름 · 반 · 탑승 상태(L1). 관계자 · 기사 · 동승자 · 메인관리자 · 학부모(자녀) · 학생(본인). */
    public static final String STUDENT_READ_BASIC = "STUDENT_READ_BASIC";

    /** 사진 · 주소 원문·좌표 · 특이사항 · 연락처 원본(L3). 관계자 · 메인관리자. */
    public static final String STUDENT_READ_SENSITIVE = "STUDENT_READ_SENSITIVE";

    /** 학생 사진 — 육안 확인 전용(L3 이나 매니저 앱 예외, C-06 · M-03). 기사 · 동승자(+ 위 2역할). */
    public static final String STUDENT_READ_PHOTO = "STUDENT_READ_PHOTO";

    /** 학생 등록 · 수정 · 퇴원. 관계자. */
    public static final String STUDENT_WRITE = "STUDENT_WRITE";

    // ── 명단 · 승하차 ──

    /** 회차 명단 조회. 관계자 · 기사 · 동승자 · 메인관리자. */
    public static final String ROSTER_READ = "ROSTER_READ";

    // ── 알림 · 비상 ──

    /** 비상 알림 발신(EXC-04). 기사 · 동승자. */
    public static final String EMERGENCY_RAISE = "EMERGENCY_RAISE";

    /** 비상 알림 조회·확인. 관계자 전용(Ruling 884) — 메인관리자는 열람 전용 콘솔(MONITOR_ALL)을 쓴다. */
    public static final String EMERGENCY_ACK = "EMERGENCY_ACK";

    /** 예외 보고 조회(§5.20). 관계자. */
    public static final String EXCEPTION_REPORT_READ = "EXCEPTION_REPORT_READ";

    // ── 노선 ──

    /** 노선 조회(권한은 전 역할, 조회 범위는 §6.4). 전 역할. */
    public static final String ROUTE_READ = "ROUTE_READ";

    /** 고정 노선 편성 · 강제 추가 · 수동 조정 · 경유 지점 지정. 관계자. */
    public static final String ROUTE_MANAGE = "ROUTE_MANAGE";

    // ── 학부모 요청 ──

    /** 회차별 탑승 여부 토글. 학부모. */
    public static final String INTENT_WRITE = "INTENT_WRITE";

    /** 탑승 위치 변경 신청. 학부모. */
    public static final String CHANGE_REQUEST_WRITE = "CHANGE_REQUEST_WRITE";

    /** ②구간 변경 신청 승인·거절(A-05). 관계자. */
    public static final String CHANGE_APPROVE = "CHANGE_APPROVE";

    // ── 가입 · 인력 · 학원 운영(관계자) ──

    /** 학부모·학생·매니저 가입 승인. 관계자. */
    public static final String SIGNUP_APPROVE = "SIGNUP_APPROVE";

    /** 학부모·학생·매니저 비밀번호 초기화 — 관리자 경유 복구(API_SPEC §5.22 · Ruling 329). 관계자. */
    public static final String ACCOUNT_PASSWORD_RESET = "ACCOUNT_PASSWORD_RESET";

    /** 매니저(기사·동승자) 등록·배치. 관계자. */
    public static final String MANAGER_MANAGE = "MANAGER_MANAGE";

    /** 차량 등록·관리. 관계자. */
    public static final String BUS_MANAGE = "BUS_MANAGE";

    /** 스케줄 관리. 관계자. */
    public static final String SCHEDULE_MANAGE = "SCHEDULE_MANAGE";

    /** 학원 대시보드 · 실시간 현황. 관계자. */
    public static final String MONITOR_ACADEMY = "MONITOR_ACADEMY";

    /** 전 학원 관제(ETA 포함, O-05·06) — 학원 격리 예외. 메인관리자. */
    public static final String MONITOR_ALL = "MONITOR_ALL";

    // ── 알림 설정 · 단말 ──

    /** 알림 수신 설정 on/off. 학부모 · 학생. */
    public static final String NOTIFICATION_SETTING_WRITE = "NOTIFICATION_SETTING_WRITE";

    /** 푸시 수신 단말 등록·해지(NTF-12). 전 역할. */
    public static final String DEVICE_REGISTER = "DEVICE_REGISTER";

    /** 알림 로그 전수 조회. 관계자. */
    public static final String NOTIFICATION_LOG_READ = "NOTIFICATION_LOG_READ";

    // ── 플랫폼 운영(메인관리자) ──

    /** 학원 등록 · 수정 · 비활성화. 메인관리자. */
    public static final String ACADEMY_MANAGE = "ACADEMY_MANAGE";

    /** 관계자 가입 승인. 메인관리자. */
    public static final String STAFF_APPROVE = "STAFF_APPROVE";

    /** 로그인 차단 해제(C-11). 메인관리자. */
    public static final String ACCOUNT_UNBLOCK = "ACCOUNT_UNBLOCK";

    /** 감사 · 접속 이력(SYS-01). 메인관리자. */
    public static final String AUDIT_READ = "AUDIT_READ";

    /** idle 로 정체된 회차의 강제 확정 콘솔 개입(API_SPEC §6.14, §6.2 신설). 메인관리자. */
    public static final String RUN_FORCE_CONFIRM = "RUN_FORCE_CONFIRM";

    /**
     * 운행일이 지난 채 끝나지 않은 이동 중 회차의 강제 종료(API_SPEC §6.17, Ruling 724). 메인관리자.
     * {@link #RUN_FORCE_CONFIRM} 과 따로 둔 이유는 동작이 다르기 때문이다 — 확정은 idle 회차를 앞으로 보내고, 이 동작은
     * 남은 탑승자를 처리하지 않은 채 회차를 닫는다.
     */
    public static final String RUN_FORCE_FINISH = "RUN_FORCE_FINISH";
}
