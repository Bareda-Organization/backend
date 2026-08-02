package src.backend.global.security.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import src.backend.user.entity.Role;

/**
 * 역할 → 권한 부여표의 특성화 테스트.
 *
 * 이 파일이 지키는 것은 셋이다.
 * 1. 각 역할이 "정확히" 어떤 권한에 도달하는지 — 부여표에 한 줄이 추가·삭제되면 여기가 깨진다.
 *    권한이 조용히 늘거나 주는 것을 막는 유일한 장치다.
 * 2. 우변에 ROLE_ 이 오지 않는다는 규칙(역할→역할 간선 금지) — 근거는 {@link RolePermissions} javadoc.
 * 3. 아무 역할에도 부여되지 않은 권한이 없는지 — 있으면 그 엔드포인트는 아무도 못 쓰게 된다.
 */
class RolePermissionsTest {

    private static final RoleHierarchy HIERARCHY = RoleHierarchyImpl.fromHierarchy(RolePermissions.HIERARCHY);

    @Test
    void 학생은_본인_조회와_좌표보고와_SOS_발신만_할_수_있다() {
        assertReaches(Role.STUDENT,
                Permissions.SELF_READ,
                Permissions.SELF_LOCATION_REPORT,
                Permissions.SOS_TRIGGER);
    }

    @Test
    void 학부모는_자녀_조회와_요청_제출만_할_수_있다() {
        assertReaches(Role.PARENT,
                Permissions.GUARDIAN_CHILDREN_READ,
                Permissions.GUARDIAN_REQUEST_SUBMIT);
    }

    @Test
    void 운전기사는_담당버스_조회와_운행만_할_수_있다() {
        // 승하차 기록(ride:record)이 없는 것이 핵심 — 기사는 승하차를 기록하지 않는다
        assertReaches(Role.DRIVER,
                Permissions.CREW_ASSIGNED_READ,
                Permissions.DRIVE_OPERATE);
    }

    @Test
    void 선탑자는_담당버스_조회와_승하차_기록_정정만_할_수_있다() {
        // 운행(drive:operate)이 없는 것이 핵심 — 선탑자는 운행 세션을 시작·종료하지 못한다
        assertReaches(Role.ATTENDANT,
                Permissions.CREW_ASSIGNED_READ,
                Permissions.RIDE_RECORD,
                Permissions.RIDE_CORRECT);
    }

    @Test
    void 학원관리자는_운영_전반과_승하차_정정을_할_수_있다() {
        // tenant:manage(학원 생성·목록·좌표)와 ride:record(승하차 기록)가 없는 것이 핵심
        assertReaches(Role.ACADEMY_ADMIN,
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
                Permissions.RIDE_CORRECT);
    }

    @Test
    void 플랫폼관리자는_학원관리자의_11개에_학원관리를_더한다() {
        // 상속이 아니라 명시 반복이므로, 여기 목록이 학원 관리자와 자동으로 같아지지 않는다
        assertReaches(Role.PLATFORM_ADMIN,
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
    }

    @Test
    void 어느_역할도_다른_역할로_확장되지_않는다() {
        // 역할→역할 간선이 생기면 AuthUser.hasRole() 과 애너테이션의 답이 갈리고,
        // 선탑자 전용 ride:record 가 관리자에게 조용히 열린다 — RolePermissions javadoc 참조.
        for (Role role : Role.values()) {
            Set<String> otherRoles = reachableFrom(role).stream()
                    .filter(authority -> authority.startsWith("ROLE_"))
                    .filter(authority -> !authority.equals("ROLE_" + role.name()))
                    .collect(Collectors.toSet());

            assertThat(otherRoles)
                    .as("%s 가 다른 역할로 확장되면 안 된다", role)
                    .isEmpty();
        }
    }

    @Test
    void 부여표의_모든_줄은_역할에서_권한으로_간다() {
        List<String> lines = RolePermissions.HIERARCHY.lines()
                .filter(line -> !line.isBlank())
                .toList();

        assertThat(lines).hasSize(33);
        for (String line : lines) {
            String[] sides = line.split(" > ");
            assertThat(sides).as("'%s' 는 '좌변 > 우변' 형태여야 한다", line).hasSize(2);
            assertThat(sides[0]).as("좌변은 역할이어야 한다").startsWith("ROLE_");
            assertThat(sides[1])
                    .as("'%s' — 우변에 ROLE_ 을 쓰면 안 된다(역할→역할 간선 금지)", line)
                    .doesNotStartWith("ROLE_");
            assertThat(sides[1]).as("권한은 <자원>:<행위> 형식이어야 한다").contains(":");
        }
    }

    @Test
    void 아무_역할에도_부여되지_않은_권한은_없다() {
        Set<String> granted = Arrays.stream(Role.values())
                .flatMap(role -> reachableFrom(role).stream())
                .collect(Collectors.toSet());

        for (Field field : Permissions.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            String permission = readConstant(field);
            assertThat(granted)
                    .as("Permissions.%s(%s) 가 어느 역할에도 부여되지 않았다", field.getName(), permission)
                    .contains(permission);
        }
    }

    /** 역할이 자기 자신의 ROLE_* 와 주어진 권한들에만 도달하는지 확인한다(초과·누락 모두 실패). */
    private static void assertReaches(Role role, String... expectedPermissions) {
        Set<String> expected = new LinkedHashSet<>(List.of(expectedPermissions));
        expected.add("ROLE_" + role.name());

        assertThat(reachableFrom(role))
                .as("%s 의 도달 권한", role)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    private static Set<String> reachableFrom(Role role) {
        return HIERARCHY
                .getReachableGrantedAuthorities(List.of(new SimpleGrantedAuthority("ROLE_" + role.name())))
                .stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    private static String readConstant(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Permissions 상수는 public 이어야 한다: " + field.getName(), e);
        }
    }
}
