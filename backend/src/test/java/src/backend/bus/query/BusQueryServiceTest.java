package src.backend.bus.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.bus.dto.BusDetailResponse;
import src.backend.bus.dto.BusResponse;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.route.entity.Route;
import src.backend.student.entity.Student;
import src.backend.student.repository.spec.StudentRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.user.entity.Role;
import src.backend.user.entity.User;

/**
 * 버스 조회 단위 테스트 — overCapacity(배정 정원 초과) 판정·TenantGuard 크로스테넌트 차단을
 * 우선 검증한다(G5 2차).
 */
class BusQueryServiceTest {

    private final BusRepository busRepository = mock(BusRepository.class);
    private final StudentRepository studentRepository = mock(StudentRepository.class);

    private final BusQueryService service = new BusQueryService(busRepository, studentRepository);

    private static final Long TENANT_ID = 1L;

    @Test
    void listBuses_onboardExceedsAssignCapacity_marksOverCapacity() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        Route route = route(10L, TENANT_ID, 2); // 정원 2명
        Bus bus = bus(1L, TENANT_ID, route);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        given(studentRepository.findByAssignedBusId(1L)).willReturn(List.of(
                student(1L), student(2L), student(3L))); // 3명 배정 — 정원 초과

        List<BusResponse> responses = service.listBuses(admin, TENANT_ID);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).onboard()).isEqualTo(3);
        assertThat(responses.get(0).overCapacity()).isTrue();
    }

    @Test
    void listBuses_withinCapacity_notMarkedOverCapacity() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        Route route = route(10L, TENANT_ID, 25);
        Bus bus = bus(1L, TENANT_ID, route);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        given(studentRepository.findByAssignedBusId(1L)).willReturn(List.of(student(1L)));

        List<BusResponse> responses = service.listBuses(admin, TENANT_ID);

        assertThat(responses.get(0).overCapacity()).isFalse();
    }

    @Test
    void listBuses_noRouteAssigned_overCapacityAlwaysFalse() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        Bus bus = bus(1L, TENANT_ID, null); // 노선 미배정
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        given(studentRepository.findByAssignedBusId(1L)).willReturn(List.of(student(1L), student(2L)));

        List<BusResponse> responses = service.listBuses(admin, TENANT_ID);

        assertThat(responses.get(0).assignCapacity()).isNull();
        assertThat(responses.get(0).overCapacity()).isFalse();
    }

    @Test
    void getBus_otherTenantAdmin_throwsForbidden() {
        AuthUser otherAdmin = authUser(999L, Role.ACADEMY_ADMIN);
        Bus bus = bus(1L, TENANT_ID, null);
        given(busRepository.findById(1L)).willReturn(Optional.of(bus));

        assertThatThrownBy(() -> service.getBus(otherAdmin, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void getBus_notFound_throwsNotFound() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        given(busRepository.findById(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBus(admin, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void getBus_accessible_returnsRoster() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        Bus bus = bus(1L, TENANT_ID, null);
        given(busRepository.findById(1L)).willReturn(Optional.of(bus));
        given(studentRepository.findByAssignedBusId(1L)).willReturn(List.of(student(1L)));

        BusDetailResponse response = service.getBus(admin, 1L);

        assertThat(response.roster()).hasSize(1);
        assertThat(response.bus().onboard()).isEqualTo(1);
    }

    // ── getMyBus: 기사가 자기 busId 를 알아내는 경로(C0) ──

    @Test
    void getMyBus_driverHasBus_returnsItWithOnboardCount() {
        AuthUser driver = authUser(TENANT_ID, Role.DRIVER);
        Bus bus = busWithDriver(1L, TENANT_ID, user(driver.userId()));
        given(busRepository.findByDriverIdOrderByIdAsc(driver.userId())).willReturn(List.of(bus));
        given(studentRepository.findByAssignedBusId(1L)).willReturn(List.of(student(1L), student(2L)));

        BusResponse response = service.getMyBus(driver);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.driverId()).isEqualTo(driver.userId());
        assertThat(response.onboard()).isEqualTo(2);
    }

    @Test
    void getMyBus_noAssignedBus_throwsNotFound() {
        AuthUser driver = authUser(TENANT_ID, Role.DRIVER);
        given(busRepository.findByDriverIdOrderByIdAsc(driver.userId())).willReturn(List.of());

        assertThatThrownBy(() -> service.getMyBus(driver))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void getMyBus_multipleBuses_returnsFirstInsteadOfFailing() {
        AuthUser driver = authUser(TENANT_ID, Role.DRIVER);
        User driverUser = user(driver.userId());
        given(busRepository.findByDriverIdOrderByIdAsc(driver.userId()))
                .willReturn(List.of(busWithDriver(1L, TENANT_ID, driverUser), busWithDriver(2L, TENANT_ID, driverUser)));
        given(studentRepository.findByAssignedBusId(1L)).willReturn(List.of());

        assertThat(service.getMyBus(driver).id()).isEqualTo(1L);
    }

    private Bus bus(Long id, Long tenantId, Route route) {
        Bus bus = Bus.builder().tenant(tenant(tenantId)).name("3호차").seatCapacity(25).route(route).build();
        ReflectionTestUtils.setField(bus, "id", id);
        return bus;
    }

    private Bus busWithDriver(Long id, Long tenantId, User driver) {
        Bus bus = Bus.builder().tenant(tenant(tenantId)).name("3호차").seatCapacity(25).driver(driver).build();
        ReflectionTestUtils.setField(bus, "id", id);
        return bus;
    }

    private User user(Long id) {
        User user = User.builder().email("driver@school.com").name("박기사").password("x").build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Route route(Long id, Long tenantId, int assignCapacity) {
        Route route = Route.builder().tenant(tenant(tenantId)).name("A노선").assignCapacity(assignCapacity).build();
        ReflectionTestUtils.setField(route, "id", id);
        return route;
    }

    private Student student(Long id) {
        Student student = Student.builder().tenant(tenant(TENANT_ID)).name("학생" + id).build();
        ReflectionTestUtils.setField(student, "id", id);
        return student;
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
