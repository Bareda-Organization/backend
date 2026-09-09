package src.backend.global.security.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import src.backend.global.common.enums.Role;

/**
 * {@link RolePermissions#HIERARCHY} 를 FEATURE_SPEC §6.2 권한 카탈로그와 대조하는 회귀 테스트.
 *
 * <p>대조 대상으로 브리프가 지정한 §6.1(역할×기능 매트릭스)이 아니라 §6.2(권한 카탈로그)를 쓴다 —
 * §6.1 은 "실시간 버스 위치"·"학원 설정(미승차 대기)" 같은 프로세용 행이 있어 권한 상수 1개에
 * 1:1 로 대응하지 않는 행이 최소 3개 있다. §6.2 는 권한·보유역할이 표 형태로 이미 구조화돼 있어
 * 애매함 없이 코드로 옮길 수 있다(p2-task-1-report.md 의 판단 근거 참고).
 *
 * <p>기대값({@link #expectedPermissionsOf(Role)})은 {@code RolePermissions.HIERARCHY} 를 베껴
 * 쓴 것이 아니라 §6.2 표(={@link Permissions} 각 상수의 Javadoc 에 옮겨 적은 보유 역할)를
 * 독립적으로 다시 옮긴 것이다 — 구현을 그대로 베끼면 구현이 잘못돼도 시험이 항상 통과해
 * 아무것도 검증하지 않는다.
 */
class RolePermissionsTest {

    @Test
    void 부여표_형식은_ROLE_밑줄_초과_권한_한줄_형태다() {
        for (String line : linesOf(RolePermissions.HIERARCHY)) {
            assertThat(line)
                    .as("각 줄은 'ROLE_<역할> > <권한>' 형태여야 한다: " + line)
                    .matches("^ROLE_[A-Z_]+ > [A-Z_]+$");
        }
    }

    /**
     * 가장 중요한 규칙 — 부여표 우변에 {@code ROLE_} 이 오면 안 된다(역할→역할 간선 금지).
     * reference.md §3 이 명시한, 이 태스크에서 가장 무거운 제약을 직접 고정한다.
     */
    @Test
    void 부여표_우변에_ROLE_접두어가_없다() {
        List<String> rightHandSidesStartingWithRole = linesOf(RolePermissions.HIERARCHY).stream()
                .map(line -> line.split(" > ")[1])
                .filter(rhs -> rhs.startsWith("ROLE_"))
                .toList();

        assertThat(rightHandSidesStartingWithRole)
                .as("역할→역할 간선은 금지다 — 우변은 항상 Permissions 상수여야 한다")
                .isEmpty();
    }

    @Test
    void 역할별_권한_집합이_FEATURE_SPEC_6_2_카탈로그와_정확히_일치한다() {
        RoleHierarchy hierarchy = RoleHierarchyImpl.fromHierarchy(RolePermissions.HIERARCHY);

        for (Role role : Role.values()) {
            Set<String> actual = reachablePermissionsOf(hierarchy, role);
            Set<String> expected = expectedPermissionsOf(role);

            assertThat(actual)
                    .as("역할 " + role + " 의 실제 권한 집합이 FEATURE_SPEC §6.2 기대 집합과 달라야 한다")
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    /**
     * "매트릭스에 없는 권한을 부여표에 추가하면 실패한다" — 과다 부여(over-grant) 방향을 별도로 증명한다.
     * 과소 부여는 위 정확히-일치 테스트가 이미 잡지만(모자란 쪽), 조율자가 "권한이 넘치는 쪽이 모자란
     * 쪽보다 위험하다"고 명시했으므로 과다 부여 방향은 실제로 RED 가 되는 시나리오를 재현해 보인다.
     *
     * <p>운영 부여표를 건드리지 않는다 — 문자열을 복제해 카탈로그에 없는 조합
     * (학부모에게 {@code STUDENT_WRITE}, 관계자 전용 권한)을 한 줄 얹은 별도 계층을 만들고,
     * 그 계층에서는 "정확히 일치" 단언이 실제로 깨진다는 것을 {@code assertThatThrownBy} 로 확인한다.
     */
    @Test
    void 매트릭스에_없는_권한을_부여표에_추가하면_실패한다() {
        String polluted = RolePermissions.HIERARCHY + "ROLE_PARENT > " + Permissions.STUDENT_WRITE + "\n";
        RoleHierarchy pollutedHierarchy = RoleHierarchyImpl.fromHierarchy(polluted);

        Set<String> actualWithOverGrant = reachablePermissionsOf(pollutedHierarchy, Role.PARENT);
        Set<String> expected = expectedPermissionsOf(Role.PARENT);

        assertThatThrownBy(() ->
                assertThat(actualWithOverGrant).containsExactlyInAnyOrderElementsOf(expected))
                .as("카탈로그에 없는 STUDENT_WRITE 를 PARENT 에게 얹으면 정확히-일치 단언이 깨져야 한다")
                .isInstanceOf(AssertionError.class);
    }

    /** {@link Permissions} 의 상수 중 어느 역할에도 부여되지 않은 고아 권한이 없는지 확인한다. */
    @Test
    void Permissions_상수_전부가_부여표에서_최소_한_역할에게_부여된다() throws IllegalAccessException {
        RoleHierarchy hierarchy = RoleHierarchyImpl.fromHierarchy(RolePermissions.HIERARCHY);
        Set<String> grantedAnywhere = new java.util.HashSet<>();
        for (Role role : Role.values()) {
            grantedAnywhere.addAll(reachablePermissionsOf(hierarchy, role));
        }

        List<String> orphans = new ArrayList<>();
        for (Field field : Permissions.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                String permission = (String) field.get(null);
                if (!grantedAnywhere.contains(permission)) {
                    orphans.add(permission);
                }
            }
        }

        assertThat(orphans).as("어느 역할에게도 부여되지 않은 권한 상수 — 카탈로그에서 빠졌거나 부여표 누락").isEmpty();
    }

    private Set<String> reachablePermissionsOf(RoleHierarchy hierarchy, Role role) {
        String roleAuthority = "ROLE_" + role.name();
        Collection<? extends GrantedAuthority> reachable =
                hierarchy.getReachableGrantedAuthorities(List.of(new SimpleGrantedAuthority(roleAuthority)));
        return reachable.stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> !authority.equals(roleAuthority))
                .collect(Collectors.toSet());
    }

    private List<String> linesOf(String hierarchy) {
        return Arrays.stream(hierarchy.split("\n"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
    }

    /**
     * FEATURE_SPEC §6.2 권한 카탈로그(= {@link Permissions} 각 상수 Javadoc 의 "보유역할")를
     * 독립적으로 옮긴 기대값. {@link RolePermissions} 구현을 참조하지 않는다.
     */
    private Set<String> expectedPermissionsOf(Role role) {
        Map<Role, Set<String>> catalog = new EnumMap<>(Role.class);
        catalog.put(Role.PARENT, Set.of(
                Permissions.STUDENT_READ_BASIC, Permissions.ROUTE_READ, Permissions.INTENT_WRITE,
                Permissions.CHANGE_REQUEST_WRITE, Permissions.NOTIFICATION_SETTING_WRITE,
                Permissions.DEVICE_REGISTER));
        catalog.put(Role.STUDENT, Set.of(
                Permissions.STUDENT_READ_BASIC, Permissions.ROUTE_READ,
                Permissions.NOTIFICATION_SETTING_WRITE, Permissions.DEVICE_REGISTER));
        catalog.put(Role.DRIVER, Set.of(
                Permissions.STUDENT_READ_BASIC, Permissions.STUDENT_READ_PHOTO, Permissions.ROSTER_READ,
                Permissions.RUN_START, Permissions.RUN_ARRIVE, Permissions.EMERGENCY_RAISE,
                Permissions.EXCEPTION_REPORT, Permissions.ROUTE_READ, Permissions.DEVICE_REGISTER));
        catalog.put(Role.ESCORT, Set.of(
                Permissions.STUDENT_READ_BASIC, Permissions.STUDENT_READ_PHOTO, Permissions.ROSTER_READ,
                Permissions.BOARDING_WRITE, Permissions.BOARDING_REVERT, Permissions.DELAY_NOTIFY,
                Permissions.EMERGENCY_RAISE, Permissions.EXCEPTION_REPORT, Permissions.ROUTE_READ,
                Permissions.DEVICE_REGISTER));
        catalog.put(Role.STAFF, Set.of(
                Permissions.STUDENT_READ_BASIC, Permissions.STUDENT_READ_SENSITIVE, Permissions.STUDENT_READ_PHOTO,
                Permissions.STUDENT_WRITE, Permissions.ROSTER_READ, Permissions.EMERGENCY_ACK,
                Permissions.ROUTE_READ, Permissions.ROUTE_MANAGE, Permissions.CHANGE_APPROVE,
                Permissions.SIGNUP_APPROVE, Permissions.MANAGER_MANAGE, Permissions.BUS_MANAGE,
                Permissions.SCHEDULE_MANAGE, Permissions.MONITOR_ACADEMY, Permissions.NOTIFICATION_LOG_READ,
                Permissions.EXCEPTION_REPORT_READ, Permissions.DEVICE_REGISTER));
        catalog.put(Role.SYSTEM_ADMIN, Set.of(
                Permissions.STUDENT_READ_BASIC, Permissions.STUDENT_READ_SENSITIVE, Permissions.STUDENT_READ_PHOTO,
                Permissions.ROSTER_READ, Permissions.EMERGENCY_ACK, Permissions.ROUTE_READ,
                Permissions.MONITOR_ALL, Permissions.DEVICE_REGISTER, Permissions.ACADEMY_MANAGE,
                Permissions.STAFF_APPROVE, Permissions.ACCOUNT_UNBLOCK, Permissions.AUDIT_READ,
                Permissions.RUN_FORCE_CONFIRM));
        return catalog.get(role);
    }
}
