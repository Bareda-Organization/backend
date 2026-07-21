package src.backend.bus.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.bus.dto.AssignmentRequest;
import src.backend.bus.dto.BusResponse;
import src.backend.bus.dto.CreateBusRequest;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.route.entity.Route;
import src.backend.route.repository.spec.RouteRepository;
import src.backend.student.repository.spec.StudentRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.tenant.repository.spec.TenantRepository;
import src.backend.user.entity.Role;
import src.backend.user.entity.User;
import src.backend.user.repository.spec.UserRepository;

/**
 * 버스 생성/배차 단위 테스트 — 크로스테넌트 검증(노선이 다른 학원 소속이면 차단)·
 * TenantGuard 접근권한 가드를 우선 검증한다(G5 2차).
 */
class BusCommandServiceTest {

    private final BusRepository busRepository = mock(BusRepository.class);
    private final StudentRepository studentRepository = mock(StudentRepository.class);
    private final RouteRepository routeRepository = mock(RouteRepository.class);
    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);

    private final BusCommandService service = new BusCommandService(
            busRepository, studentRepository, routeRepository, tenantRepository, userRepository);

    private static final Long TENANT_ID = 1L;
    private static final Long OTHER_TENANT_ID = 2L;

    @Test
    void createBus_withRouteAndDriverInSameTenant_savesSuccessfully() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        given(tenantRepository.findById(TENANT_ID)).willReturn(Optional.of(tenant(TENANT_ID)));
        given(routeRepository.findById(10L)).willReturn(Optional.of(route(10L, TENANT_ID, 25)));
        given(userRepository.findById(20L)).willReturn(Optional.of(user(20L, "박기사")));
        given(studentRepository.findByAssignedBusId(any())).willReturn(List.of());
        given(busRepository.save(any(Bus.class))).willAnswer(inv -> {
            Bus bus = inv.getArgument(0);
            ReflectionTestUtils.setField(bus, "id", 1L);
            return bus;
        });
        CreateBusRequest req = new CreateBusRequest(TENANT_ID, "4호차", "서울1", 25, 20L, 10L, null);

        BusResponse response = service.createBus(admin, req);

        assertThat(response.name()).isEqualTo("4호차");
        assertThat(response.routeId()).isEqualTo(10L);
        assertThat(response.driverId()).isEqualTo(20L);
    }

    @Test
    void createBus_routeInOtherTenant_throwsInvalidInput() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        given(tenantRepository.findById(TENANT_ID)).willReturn(Optional.of(tenant(TENANT_ID)));
        given(routeRepository.findById(10L)).willReturn(Optional.of(route(10L, OTHER_TENANT_ID, 25))); // 다른 학원 노선
        CreateBusRequest req = new CreateBusRequest(TENANT_ID, "4호차", null, 25, null, 10L, null);

        assertThatThrownBy(() -> service.createBus(admin, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void assign_onAccessibleBus_updatesDriverAndRoute() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        Bus bus = bus(1L, TENANT_ID, null, null);
        given(busRepository.findById(1L)).willReturn(Optional.of(bus));
        given(userRepository.findById(20L)).willReturn(Optional.of(user(20L, "박기사")));
        given(routeRepository.findById(10L)).willReturn(Optional.of(route(10L, TENANT_ID, 25)));
        given(studentRepository.findByAssignedBusId(1L)).willReturn(List.of());

        BusResponse response = service.assign(admin, 1L, new AssignmentRequest(20L, 10L));

        assertThat(response.driverId()).isEqualTo(20L);
        assertThat(response.routeId()).isEqualTo(10L);
    }

    @Test
    void assign_busInOtherTenant_throwsForbidden() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        Bus bus = bus(1L, OTHER_TENANT_ID, null, null); // 다른 학원 버스
        given(busRepository.findById(1L)).willReturn(Optional.of(bus));

        assertThatThrownBy(() -> service.assign(admin, 1L, new AssignmentRequest(20L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void createBus_platformAdminWithoutTenantId_throwsInvalidInput() {
        AuthUser platformAdmin = authUser(null, Role.PLATFORM_ADMIN);
        CreateBusRequest req = new CreateBusRequest(null, "4호차", null, 25, null, null, null);

        assertThatThrownBy(() -> service.createBus(platformAdmin, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    private Bus bus(Long id, Long tenantId, User driver, Route route) {
        Bus bus = Bus.builder().tenant(tenant(tenantId)).name("3호차").seatCapacity(25)
                .driver(driver).route(route).build();
        ReflectionTestUtils.setField(bus, "id", id);
        return bus;
    }

    private Route route(Long id, Long tenantId, int assignCapacity) {
        Route route = Route.builder().tenant(tenant(tenantId)).name("A노선").assignCapacity(assignCapacity).build();
        ReflectionTestUtils.setField(route, "id", id);
        return route;
    }

    private User user(Long id, String name) {
        User user = User.builder().email(name + "@school.com").name(name).password("x").build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
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
