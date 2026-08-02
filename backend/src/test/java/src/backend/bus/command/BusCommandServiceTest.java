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
import src.backend.user.entity.UserTenantRole;
import src.backend.user.repository.spec.UserRepository;
import src.backend.user.repository.spec.UserTenantRoleRepository;

/**
 * 버스 생성/배차 단위 테스트 — 크로스테넌트 검증(노선이 다른 학원 소속이면 차단)·
 * TenantGuard 접근권한 가드·담당자 역할 검증(기사/선탑자)을 검증한다(G5 2차, BE-7).
 */
class BusCommandServiceTest {

    private final BusRepository busRepository = mock(BusRepository.class);
    private final StudentRepository studentRepository = mock(StudentRepository.class);
    private final RouteRepository routeRepository = mock(RouteRepository.class);
    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserTenantRoleRepository userTenantRoleRepository = mock(UserTenantRoleRepository.class);

    private final BusCommandService service = new BusCommandService(
            busRepository, studentRepository, routeRepository, tenantRepository,
            userRepository, userTenantRoleRepository);

    private static final Long TENANT_ID = 1L;
    private static final Long OTHER_TENANT_ID = 2L;

    @Test
    void createBus_withRouteAndDriverInSameTenant_savesSuccessfully() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        given(tenantRepository.findById(TENANT_ID)).willReturn(Optional.of(tenant(TENANT_ID)));
        given(routeRepository.findById(10L)).willReturn(Optional.of(route(10L, TENANT_ID, 25)));
        givenMember(20L, "박기사", TENANT_ID, Role.DRIVER);
        given(studentRepository.findByAssignedBusIdAndActiveTrue(any())).willReturn(List.of());
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
        givenMember(20L, "박기사", TENANT_ID, Role.DRIVER);
        given(routeRepository.findById(10L)).willReturn(Optional.of(route(10L, TENANT_ID, 25)));
        given(studentRepository.findByAssignedBusIdAndActiveTrue(1L)).willReturn(List.of());

        BusResponse response = service.assign(admin, 1L, new AssignmentRequest(20L, null, 10L));

        assertThat(response.driverId()).isEqualTo(20L);
        assertThat(response.routeId()).isEqualTo(10L);
    }

    @Test
    void assign_busInOtherTenant_throwsForbidden() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        Bus bus = bus(1L, OTHER_TENANT_ID, null, null); // 다른 학원 버스
        given(busRepository.findById(1L)).willReturn(Optional.of(bus));

        assertThatThrownBy(() -> service.assign(admin, 1L, new AssignmentRequest(20L, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void assign_withAttendantId_assignsAttendant() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        Bus bus = bus(1L, TENANT_ID, null, null);
        given(busRepository.findById(1L)).willReturn(Optional.of(bus));
        givenMember(30L, "이선탑", TENANT_ID, Role.ATTENDANT);
        given(studentRepository.findByAssignedBusIdAndActiveTrue(1L)).willReturn(List.of());

        service.assign(admin, 1L, new AssignmentRequest(null, 30L, null));

        assertThat(bus.getAttendant()).isNotNull();
        assertThat(bus.getAttendant().getId()).isEqualTo(30L);
    }

    @Test
    void assign_driverIdIsParentRole_throwsInvalidInput() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        given(busRepository.findById(1L)).willReturn(Optional.of(bus(1L, TENANT_ID, null, null)));
        givenMember(40L, "학부모", TENANT_ID, Role.PARENT); // 같은 학원이지만 역할이 기사가 아니다

        assertThatThrownBy(() -> service.assign(admin, 1L, new AssignmentRequest(40L, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void assign_driverFromOtherTenant_throwsInvalidInput() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        given(busRepository.findById(1L)).willReturn(Optional.of(bus(1L, TENANT_ID, null, null)));
        givenMember(50L, "타학원기사", OTHER_TENANT_ID, Role.DRIVER); // 역할은 기사지만 다른 학원 소속

        assertThatThrownBy(() -> service.assign(admin, 1L, new AssignmentRequest(50L, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void assign_attendantIdIsDriverRole_throwsInvalidInput() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        given(busRepository.findById(1L)).willReturn(Optional.of(bus(1L, TENANT_ID, null, null)));
        givenMember(60L, "박기사", TENANT_ID, Role.DRIVER); // 기사를 선탑자 자리에 넣을 수 없다

        assertThatThrownBy(() -> service.assign(admin, 1L, new AssignmentRequest(null, 60L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void createBus_driverIdIsParentRole_throwsInvalidInput() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        given(tenantRepository.findById(TENANT_ID)).willReturn(Optional.of(tenant(TENANT_ID)));
        givenMember(40L, "학부모", TENANT_ID, Role.PARENT);
        CreateBusRequest req = new CreateBusRequest(TENANT_ID, "4호차", null, 25, 40L, null, null);

        assertThatThrownBy(() -> service.createBus(admin, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
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

    // ── 중복 배정 금지 I-6 (BE-14) ──

    /** 한 기사가 두 대에 동시에 배정되면 "어느 차에 탔는가"를 데이터가 답하지 못한다. */
    @Test
    void assign_driverAlreadyOnAnotherBus_throwsConflict() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        given(busRepository.findById(1L)).willReturn(Optional.of(bus(1L, TENANT_ID, null, null)));
        given(busRepository.findByDriverIdAndTenantId(20L, TENANT_ID))
                .willReturn(List.of(bus(2L, TENANT_ID, null, null)));   // 이미 다른 차의 기사

        assertThatThrownBy(() -> service.assign(admin, 1L, new AssignmentRequest(20L, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void assign_attendantAlreadyOnAnotherBus_throwsConflict() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        given(busRepository.findById(1L)).willReturn(Optional.of(bus(1L, TENANT_ID, null, null)));
        given(busRepository.findByAttendantIdAndTenantId(30L, TENANT_ID))
                .willReturn(List.of(bus(2L, TENANT_ID, null, null)));

        assertThatThrownBy(() -> service.assign(admin, 1L, new AssignmentRequest(null, 30L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    /**
     * 화면이 전체 폼을 그대로 PATCH 하므로 노선만 바꾸는 요청도 같은 기사 id 를 다시 보낸다.
     * 자기 버스로의 재배정까지 막으면 이 흔한 요청이 409 로 죽는다.
     */
    @Test
    void assign_sameBusReassign_passes() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        Bus bus = bus(1L, TENANT_ID, null, null);
        given(busRepository.findById(1L)).willReturn(Optional.of(bus));
        given(busRepository.findByDriverIdAndTenantId(20L, TENANT_ID)).willReturn(List.of(bus));   // 자기 자신뿐
        givenMember(20L, "박기사", TENANT_ID, Role.DRIVER);
        given(routeRepository.findById(10L)).willReturn(Optional.of(route(10L, TENANT_ID, 25)));
        given(studentRepository.findByAssignedBusIdAndActiveTrue(1L)).willReturn(List.of());

        BusResponse response = service.assign(admin, 1L, new AssignmentRequest(20L, null, 10L));

        assertThat(response.driverId()).isEqualTo(20L);
        assertThat(response.routeId()).isEqualTo(10L);
    }

    /** 생성 시점에도 같은 규칙이다 — 버스를 만들면서 이미 다른 차의 기사를 꽂을 수 있다. */
    @Test
    void createBus_driverAlreadyOnAnotherBus_throwsConflict() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        given(tenantRepository.findById(TENANT_ID)).willReturn(Optional.of(tenant(TENANT_ID)));
        given(busRepository.findByDriverIdAndTenantId(20L, TENANT_ID))
                .willReturn(List.of(bus(2L, TENANT_ID, null, null)));
        CreateBusRequest req = new CreateBusRequest(TENANT_ID, "4호차", null, 25, 20L, null, null);

        assertThatThrownBy(() -> service.createBus(admin, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
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

    /** userId 사용자가 tenantId 학원에서 role 을 가진 것으로 스텁한다(존재 + 역할·소속 두 조회를 함께 준비). */
    private void givenMember(Long userId, String name, Long tenantId, Role role) {
        User member = user(userId, name);
        given(userRepository.findById(userId)).willReturn(Optional.of(member));
        given(userTenantRoleRepository.findByUserId(userId))
                .willReturn(List.of(new UserTenantRole(member, tenant(tenantId), role)));
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
