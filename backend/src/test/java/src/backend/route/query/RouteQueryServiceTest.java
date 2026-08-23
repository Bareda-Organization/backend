package src.backend.route.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.route.dto.RouteResponse;
import src.backend.route.entity.Route;
import src.backend.route.entity.Stop;
import src.backend.route.repository.spec.RouteRepository;
import src.backend.route.repository.spec.StopRepository;
import src.backend.student.repository.spec.StudentRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.user.entity.Role;

/**
 * 노선 조회 단위 테스트 — 이 노선을 운행하는 버스들에 배정된 학생 수를 count 한 번으로 집계해
 * 정원 초과를 판정하는 로직(assignedCount 집계)을 우선 검증한다(G5 2차, RouteQueryService 고유 로직).
 */
class RouteQueryServiceTest {

    private final RouteRepository routeRepository = mock(RouteRepository.class);
    private final StopRepository stopRepository = mock(StopRepository.class);
    private final StudentRepository studentRepository = mock(StudentRepository.class);

    private final RouteQueryService service = new RouteQueryService(
            routeRepository, stopRepository, studentRepository);

    private static final Long TENANT_ID = 1L;

    @Test
    void listRoutes_countsAssignedStudentsWithSingleQuery() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        Route route = route(10L, TENANT_ID, 3); // 정원 3명
        given(routeRepository.findByTenantId(TENANT_ID)).willReturn(List.of(route));
        given(studentRepository.countByAssignedBus_Route_IdAndActiveTrue(10L)).willReturn(3L);

        List<RouteResponse> responses = service.listRoutes(admin, TENANT_ID);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).assignedCount()).isEqualTo(3);
        assertThat(responses.get(0).overCapacity()).isFalse(); // 정원 3, 정확히 3명
        verify(studentRepository).countByAssignedBus_Route_IdAndActiveTrue(10L); // count 한 번으로 집계
    }

    @Test
    void listRoutes_exceedsCapacity_marksOverCapacity() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        Route route = route(10L, TENANT_ID, 2); // 정원 2명
        given(routeRepository.findByTenantId(TENANT_ID)).willReturn(List.of(route));
        given(studentRepository.countByAssignedBus_Route_IdAndActiveTrue(10L)).willReturn(3L);

        List<RouteResponse> responses = service.listRoutes(admin, TENANT_ID);

        assertThat(responses.get(0).assignedCount()).isEqualTo(3);
        assertThat(responses.get(0).overCapacity()).isTrue();
    }

    @Test
    void getStops_다른학원_노선이면_FORBIDDEN() {
        Tenant other = tenantWithId(2L);
        Route route = routeOf(10L, other);
        given(routeRepository.findById(10L)).willReturn(Optional.of(route));

        AuthUser outsider = authUserOfTenant(1L);   // 소속은 1번 학원

        assertThatThrownBy(() -> service.getStops(outsider, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    void getStops_같은학원_노선이면_정류장을_돌려준다() {
        Tenant mine = tenantWithId(1L);
        Route route = routeOf(10L, mine);
        given(routeRepository.findById(10L)).willReturn(Optional.of(route));
        given(stopRepository.findByRouteIdOrderBySeqAsc(10L)).willReturn(List.of(stopOf(100L, "정문")));

        AuthUser member = authUserOfTenant(1L);

        assertThat(service.getStops(member, 10L)).hasSize(1);
    }

    @Test
    void getStops_플랫폼관리자는_다른학원_노선도_볼_수_있다() {
        Route route = routeOf(10L, tenantWithId(2L));
        given(routeRepository.findById(10L)).willReturn(Optional.of(route));
        given(stopRepository.findByRouteIdOrderBySeqAsc(10L)).willReturn(List.of(stopOf(100L, "정문")));

        assertThat(service.getStops(platformAdmin(), 10L)).hasSize(1);
    }

    @Test
    void getStops_없는_노선이면_NOT_FOUND() {
        given(routeRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStops(authUserOfTenant(1L), 99L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
    }

    private Route route(Long id, Long tenantId, int assignCapacity) {
        Route route = Route.builder().tenant(tenant(tenantId)).name("A노선").assignCapacity(assignCapacity).build();
        ReflectionTestUtils.setField(route, "id", id);
        return route;
    }

    private Tenant tenant(Long id) {
        Tenant tenant = Tenant.builder().name("한빛학원").build();
        ReflectionTestUtils.setField(tenant, "id", id);
        return tenant;
    }

    private AuthUser authUser(Long tenantId, Role role) {
        return new AuthUser(100L, "admin@school.com", List.of(new AuthUser.Membership(tenantId, role)));
    }

    private Tenant tenantWithId(Long id) {
        return tenant(id);
    }

    private Route routeOf(Long id, Tenant tenant) {
        Route route = Route.builder().tenant(tenant).name("A노선").assignCapacity(10).build();
        ReflectionTestUtils.setField(route, "id", id);
        return route;
    }

    private Stop stopOf(Long id, String name) {
        Stop stop = Stop.builder().route(routeOf(10L, tenantWithId(1L))).name(name).seq(1).lat(37.5).lng(127.0).build();
        ReflectionTestUtils.setField(stop, "id", id);
        return stop;
    }

    private AuthUser authUserOfTenant(Long tenantId) {
        return authUser(tenantId, Role.PARENT);
    }

    private AuthUser platformAdmin() {
        return new AuthUser(999L, "platform@admin.com", List.of(new AuthUser.Membership(null, Role.PLATFORM_ADMIN)));
    }
}
