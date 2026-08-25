package src.backend.global.security.authz;

import src.backend.global.common.enums.Role;

/**
 * 역할 → 권한(permission) 부여표. 이 시스템에서 "누가 무엇을 할 수 있는가"를 정하는 유일한 파일이다.
 *
 * <p>{@link #HIERARCHY} 는 스프링 시큐리티 {@code RoleHierarchyImpl.fromHierarchy} 가 읽는
 * 형식이다. 이름은 "계층"이지만 실제로는 임의 문자열 간의 도달성 맵이라,
 * {@code ROLE_STAFF > STUDENT_WRITE} 처럼 역할에서 권한으로 가는 다대다 부여표로 그대로 쓸 수
 * 있다 — 이 필드를 읽어 {@code roleHierarchy()} 빈으로 등록하면 {@code @PreAuthorize
 * ("hasAuthority('STUDENT_WRITE')")} 평가 시 요청 주체의 {@code ROLE_*} 권한이 여기 적힌 권한들로
 * 확장된다.
 *
 * <h2>규칙 — 우변에 {@code ROLE_} 을 쓰지 않는다(역할 → 역할 간선 금지)</h2>
 *
 * <p>모든 줄은 {@code ROLE_<역할> > <권한>} 형태여야 한다. {@code ROLE_STAFF > ROLE_ESCORT} 같은
 * 줄은 문법상 가능해도 절대 넣지 않는다 — 넣으면 "이 사람은 관계자인가"에 애너테이션 계층은 예,
 * {@code AuthUser} 를 보는 서비스 계층은 아니오라고 답해 두 계층이 갈리고, 그 문법을 한 줄이라도
 * 허용하면 다음 사람이 역할 간 상속을 추가하는 순간 하위 역할 전용 권한이 상위 역할에게 조용히
 * 열린다(FEATURE_SPEC §6.2, reference.md §3). {@code RolePermissionsTest} 가 이를 고정한다.
 *
 * <h2>배선 — {@code SecurityConfig.roleHierarchy()}</h2>
 *
 * <p>{@code SecurityConfig.roleHierarchy()} 가 이 필드를 읽어 빈으로 등록한다(Phase 2 Task 3,
 * Ruling 76). 그 배선이 없으면 컨트롤러의 {@code hasAuthority(...)} 평가가 전부 거부로 떨어져
 * <b>모든 인가 애너테이션이 403 을 낸다</b> — 부여표가 비어 있는 것과 배선이 빠진 것은 증상이 같다.
 * 이 필드를 옮기거나 이름을 바꾸면 그 한 곳을 함께 고친다(자세한 경위는 p2-task-1-report.md).
 */
public final class RolePermissions {

    private RolePermissions() {
    }

    /** 역할별 부여 줄 57개(FEATURE_SPEC §6.2 권한 31종을 보유 역할 수만큼 반복한 합계). */
    public static final String HIERARCHY =
            // 학부모 — 자녀 기본 정보를 보고, 탑승 의사·변경을 신청하고, 알림·단말을 설정한다
            grant(Role.PARENT,
                    Permissions.STUDENT_READ_BASIC,
                    Permissions.ROUTE_READ,
                    Permissions.INTENT_WRITE,
                    Permissions.CHANGE_REQUEST_WRITE,
                    Permissions.NOTIFICATION_SETTING_WRITE,
                    Permissions.DEVICE_REGISTER)

            // 학생 — 본인 기본 정보를 보고, 알림·단말을 설정한다(변경 신청은 학부모 전용)
            + grant(Role.STUDENT,
                    Permissions.STUDENT_READ_BASIC,
                    Permissions.ROUTE_READ,
                    Permissions.NOTIFICATION_SETTING_WRITE,
                    Permissions.DEVICE_REGISTER)

            // 운전기사 — 명단을 보고 운행을 시작·종료하며 비상·예외를 보고한다(승하차는 기록하지 않는다, C-06)
            + grant(Role.DRIVER,
                    Permissions.STUDENT_READ_BASIC,
                    Permissions.STUDENT_READ_PHOTO,
                    Permissions.ROSTER_READ,
                    Permissions.RUN_START,
                    Permissions.RUN_ARRIVE,
                    Permissions.EMERGENCY_RAISE,
                    Permissions.EXCEPTION_REPORT,
                    Permissions.ROUTE_READ,
                    Permissions.DEVICE_REGISTER)

            // 동승자 — 명단을 보고 승하차를 기록·정정하며 지연·비상·예외를 보고한다(운행 시작·종료는 하지 않는다)
            + grant(Role.ESCORT,
                    Permissions.STUDENT_READ_BASIC,
                    Permissions.STUDENT_READ_PHOTO,
                    Permissions.ROSTER_READ,
                    Permissions.BOARDING_WRITE,
                    Permissions.BOARDING_REVERT,
                    Permissions.DELAY_NOTIFY,
                    Permissions.EMERGENCY_RAISE,
                    Permissions.EXCEPTION_REPORT,
                    Permissions.ROUTE_READ,
                    Permissions.DEVICE_REGISTER)

            // 학원 관계자 — 자기 학원의 학생·인력·노선·가입·알림 운영 전반(어느 학원인지는 T5 학원 격리가 별도 검사)
            + grant(Role.STAFF,
                    Permissions.STUDENT_READ_BASIC,
                    Permissions.STUDENT_READ_SENSITIVE,
                    Permissions.STUDENT_READ_PHOTO,
                    Permissions.STUDENT_WRITE,
                    Permissions.ROSTER_READ,
                    Permissions.EMERGENCY_ACK,
                    Permissions.ROUTE_READ,
                    Permissions.ROUTE_MANAGE,
                    Permissions.CHANGE_APPROVE,
                    Permissions.SIGNUP_APPROVE,
                    Permissions.MANAGER_MANAGE,
                    Permissions.BUS_MANAGE,
                    Permissions.SCHEDULE_MANAGE,
                    Permissions.MONITOR_ACADEMY,
                    Permissions.NOTIFICATION_LOG_READ,
                    Permissions.DEVICE_REGISTER)

            // 메인 관리자 — 전 학원 관제·감사와 플랫폼 운영(학원 CRUD·관계자 승인·차단 해제). 학원 관계자의 11개를
            // 상속하지 않고 필요한 것만 별도 명시한다(상속 금지 근거는 위 클래스 Javadoc — ROLE_STAFF 를 상속시키면
            // 우변에 ROLE_ 이 오는 역할→역할 간선이 되어 이 클래스가 막는 바로 그 형태가 된다).
            + grant(Role.SYSTEM_ADMIN,
                    Permissions.STUDENT_READ_BASIC,
                    Permissions.STUDENT_READ_SENSITIVE,
                    Permissions.STUDENT_READ_PHOTO,
                    Permissions.ROSTER_READ,
                    Permissions.EMERGENCY_ACK,
                    Permissions.ROUTE_READ,
                    Permissions.MONITOR_ALL,
                    Permissions.DEVICE_REGISTER,
                    Permissions.ACADEMY_MANAGE,
                    Permissions.STAFF_APPROVE,
                    Permissions.ACCOUNT_UNBLOCK,
                    Permissions.AUDIT_READ);

    /**
     * 역할 하나에 권한 여러 개를 부여하는 줄들을 만든다. 좌변을 {@link Role} 로만 받기 때문에
     * 우변에 역할을 넣는 실수를 구조적으로 어렵게 한다 — {@code ROLE_} 접두어는 여기서만 붙는다.
     */
    private static String grant(Role role, String... permissions) {
        StringBuilder lines = new StringBuilder();
        for (String permission : permissions) {
            lines.append("ROLE_").append(role.name()).append(" > ").append(permission).append('\n');
        }
        return lines.toString();
    }
}
