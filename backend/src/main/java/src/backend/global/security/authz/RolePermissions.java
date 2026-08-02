package src.backend.global.security.authz;

import src.backend.user.entity.Role;

/**
 * 역할 → 권한(permission) 부여표. <b>이 시스템에서 "누가 무엇을 할 수 있는가"를 정하는 유일한 파일이다.</b>
 *
 * <p>{@link #HIERARCHY} 는 스프링 시큐리티의
 * {@code RoleHierarchyImpl.fromHierarchy} 가 읽는 형식이다. 이름은 "계층"이지만 실제로는
 * 임의 문자열 간의 도달성 맵이라, {@code ROLE_ACADEMY_ADMIN > student:manage} 처럼
 * 역할에서 권한으로 가는 다대다 부여표로 그대로 쓸 수 있다.
 * 이 맵은 {@code SecurityConfig} 의 {@code roleHierarchy()} 빈으로 등록되며,
 * {@code @PreAuthorize("hasAuthority('student:manage')")} 를 평가할 때
 * 요청 주체의 {@code ROLE_*} 권한을 여기 적힌 권한들로 확장해 준다.
 *
 * <p>덕분에 {@code AuthUser}·{@code JwtTokenProvider}·JWT 토큰 포맷은 한 줄도 바뀌지 않는다 —
 * 토큰에는 여전히 {@code ROLE_*} 만 들어 있고, 권한 확장은 인가 평가 시점에만 일어난다.
 *
 * <h2>규칙: 우변에 {@code ROLE_} 을 쓰지 않는다 (역할 → 역할 간선 금지)</h2>
 *
 * <p>모든 줄은 반드시 {@code ROLE_<역할> > <자원>:<행위>} 형태여야 한다.
 * {@code ROLE_PLATFORM_ADMIN > ROLE_ACADEMY_ADMIN} 같은 줄은 문법상 가능하고 사실 관계로도 맞지만
 * <b>절대 넣지 않는다.</b> 이유가 둘이다.
 *
 * <ol>
 *   <li><b>서비스 계층과 답이 갈린다.</b> {@code AuthUser.hasRole()} 은 멤버십을 직접 훑을 뿐
 *       이 부여표를 모른다. 역할→역할 간선을 넣으면 "이 사람은 ACADEMY_ADMIN 인가"에
 *       애너테이션은 예, 서비스 계층은 아니오 라고 답한다. 실제로 답이 갈리는 지점이
 *       {@code RideEventCommandService}·{@code StompAuthChannelInterceptor}·{@code TenantQueryService}
 *       세 곳 있다.</li>
 *   <li><b>조용히 권한이 열린다.</b> {@code ROLE_A > ROLE_B} 문법을 한 줄이라도 허용하면
 *       다음 사람이 "관리자가 선탑자를 관리하니까"라며
 *       {@code ROLE_ACADEMY_ADMIN > ROLE_ATTENDANT} 를 추가할 여지가 열린다. 그 순간
 *       선탑자 전용인 {@link Permissions#RIDE_RECORD}(승하차 기록)가 관리자에게 열린다.
 *       한 줄 추가에 컴파일 에러도 테스트 실패도 없이 권한이 늘어나는 것이 이 형식의 위험이다.</li>
 * </ol>
 *
 * <p>그래서 {@code PLATFORM_ADMIN} 은 {@code ACADEMY_ADMIN} 의 11개를 상속하지 않고
 * <b>전부 명시 반복</b>한다. 아끼는 게 11줄뿐인 데 비해, 상속시켰을 때 잃는 것(위 두 가지)이 크다.
 * 이 규칙은 {@code RolePermissionsTest} 가 고정한다 — 우변에 {@code ROLE_} 이 오면 테스트가 깨진다.
 *
 * <h2>새 역할을 추가할 때</h2>
 *
 * <p>{@link Role} 에 상수를 넣고 여기에 부여 줄을 추가하면 끝이다(컨트롤러 14개는 손대지 않는다).
 * 다만 WebSocket 인가({@code StompAuthChannelInterceptor})는 {@code @PreAuthorize} 를 타지 않으므로
 * <b>그 한 파일은 여전히 따로 봐야 한다.</b>
 */
public final class RolePermissions {

    private RolePermissions() {
    }

    /**
     * 역할별 부여 줄 33개. 현재 컨트롤러 61곳의 역할 인가와 1:1 로 대응한다
     * (변경 시 {@code RolePermissionsTest} 의 기대 집합도 함께 고쳐야 한다).
     */
    public static final String HIERARCHY =
            // 학생 — 본인 것만 보고, 본인 좌표를 보고하고, SOS 를 누른다
            grant(Role.STUDENT,
                    Permissions.SELF_READ,
                    Permissions.SELF_LOCATION_REPORT,
                    Permissions.SOS_TRIGGER)

            // 학부모 — 자녀 것을 보고, 각종 변경 요청을 낸다
            + grant(Role.PARENT,
                    Permissions.GUARDIAN_CHILDREN_READ,
                    Permissions.GUARDIAN_REQUEST_SUBMIT)

            // 운전기사 — 담당 버스를 보고, 운행하고 위치를 보고한다(승하차는 기록하지 않는다)
            + grant(Role.DRIVER,
                    Permissions.CREW_ASSIGNED_READ,
                    Permissions.DRIVE_OPERATE)

            // 선탑자 — 담당 버스를 보고, 승하차를 기록·정정한다(운행은 하지 않는다)
            + grant(Role.ATTENDANT,
                    Permissions.CREW_ASSIGNED_READ,
                    Permissions.RIDE_RECORD,
                    Permissions.RIDE_CORRECT)

            // 학원 관리자 — 자기 학원의 운영 전반(어느 학원인지는 서비스 계층의 TenantGuard 가 따로 검사한다)
            + grant(Role.ACADEMY_ADMIN,
                    Permissions.STUDENT_MANAGE,
                    Permissions.MEMBER_MANAGE,
                    Permissions.BUS_MANAGE,
                    Permissions.ROUTE_MANAGE,
                    Permissions.ROUTE_PLAN_MANAGE,
                    Permissions.ATTENDANCE_MANAGE,
                    Permissions.SCHEDULE_MANAGE,
                    Permissions.SOS_MANAGE,
                    Permissions.TENANT_READ,
                    Permissions.OPERATIONS_MONITOR,
                    Permissions.RIDE_CORRECT)

            // 플랫폼 관리자 — 위 11개를 그대로 반복(상속 금지 근거는 클래스 javadoc) + 학원 자체 관리
            + grant(Role.PLATFORM_ADMIN,
                    Permissions.STUDENT_MANAGE,
                    Permissions.MEMBER_MANAGE,
                    Permissions.BUS_MANAGE,
                    Permissions.ROUTE_MANAGE,
                    Permissions.ROUTE_PLAN_MANAGE,
                    Permissions.ATTENDANCE_MANAGE,
                    Permissions.SCHEDULE_MANAGE,
                    Permissions.SOS_MANAGE,
                    Permissions.TENANT_READ,
                    Permissions.OPERATIONS_MONITOR,
                    Permissions.RIDE_CORRECT,
                    Permissions.TENANT_MANAGE);

    /**
     * 역할 하나에 권한 여러 개를 부여하는 줄들을 만든다.
     * 좌변을 {@link Role} 로만 받기 때문에 우변에 역할을 넣는 실수를 구조적으로 어렵게 한다
     * — {@code ROLE_} 접두어는 여기서만 붙는다.
     */
    private static String grant(Role role, String... permissions) {
        StringBuilder lines = new StringBuilder();
        for (String permission : permissions) {
            lines.append("ROLE_").append(role.name()).append(" > ").append(permission).append('\n');
        }
        return lines.toString();
    }
}
