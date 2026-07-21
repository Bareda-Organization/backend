package src.backend.route.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.route.dto.CreateRouteRequest;
import src.backend.route.dto.CreateStopRequest;
import src.backend.route.dto.RouteResponse;
import src.backend.route.dto.StopResponse;
import src.backend.route.entity.Route;
import src.backend.route.entity.Stop;
import src.backend.route.repository.spec.RouteRepository;
import src.backend.route.repository.spec.StopRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.tenant.repository.spec.TenantRepository;
import src.backend.user.entity.Role;

/**
 * 노선 생성/정류장 추가 단위 테스트 — TenantGuard 크로스테넌트 차단을 우선 검증한다(G5 2차).
 */
class RouteCommandServiceTest {

    private final RouteRepository routeRepository = mock(RouteRepository.class);
    private final StopRepository stopRepository = mock(StopRepository.class);
    private final TenantRepository tenantRepository = mock(TenantRepository.class);

    private final RouteCommandService service = new RouteCommandService(
            routeRepository, stopRepository, tenantRepository);

    private static final Long TENANT_ID = 1L;

    @Test
    void createRoute_savesWithZeroAssignedCount() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        given(tenantRepository.findById(TENANT_ID)).willReturn(Optional.of(tenant(TENANT_ID)));
        given(routeRepository.save(any(Route.class))).willAnswer(inv -> {
            Route route = inv.getArgument(0);
            ReflectionTestUtils.setField(route, "id", 1L);
            return route;
        });

        RouteResponse response = service.createRoute(admin, new CreateRouteRequest(TENANT_ID, "등원 A노선", 25));

        assertThat(response.name()).isEqualTo("등원 A노선");
        assertThat(response.assignedCount()).isEqualTo(0);
        assertThat(response.overCapacity()).isFalse();
    }

    @Test
    void addStop_onAccessibleRoute_savesStop() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        Route route = route(10L, TENANT_ID, 25);
        given(routeRepository.findById(10L)).willReturn(Optional.of(route));
        given(stopRepository.save(any(Stop.class))).willAnswer(inv -> {
            Stop stop = inv.getArgument(0);
            ReflectionTestUtils.setField(stop, "id", 1L);
            return stop;
        });

        StopResponse response = service.addStop(admin, 10L, new CreateStopRequest("정류장 C", 4, 37.509, 127.032));

        assertThat(response.name()).isEqualTo("정류장 C");
        assertThat(response.seq()).isEqualTo(4);
    }

    @Test
    void addStop_routeInOtherTenant_throwsForbidden() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        Route route = route(10L, 999L, 25); // 다른 학원 노선
        given(routeRepository.findById(10L)).willReturn(Optional.of(route));

        assertThatThrownBy(() -> service.addStop(admin, 10L, new CreateStopRequest("정류장 C", 4, 37.509, 127.032)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void addStop_routeNotFound_throwsNotFound() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        given(routeRepository.findById(10L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.addStop(admin, 10L, new CreateStopRequest("정류장 C", 4, 37.509, 127.032)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
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
}
