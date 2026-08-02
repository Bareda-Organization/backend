package src.backend.bus.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
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
import src.backend.routing.domain.RouteDirection;
import src.backend.routing.domain.RoutePlanStatus;
import src.backend.routing.entity.RoutePlan;
import src.backend.routing.repository.spec.RoutePlanRepository;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.student.repository.spec.StudentRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.user.entity.Role;
import src.backend.user.entity.User;

/**
 * 버스 조회 단위 테스트 — overCapacity(배정 정원 초과) 판정·TenantGuard 크로스테넌트 차단을
 * 우선 검증한다(G5 2차). BE-6 에서 관제 상세(선탑자·보호자·당일 계획)와 목록 N+1 제거를 추가로 덮는다.
 */
class BusQueryServiceTest {

    private final BusRepository busRepository = mock(BusRepository.class);
    private final StudentRepository studentRepository = mock(StudentRepository.class);
    private final StudentGuardianRepository studentGuardianRepository = mock(StudentGuardianRepository.class);
    private final RoutePlanRepository routePlanRepository = mock(RoutePlanRepository.class);

    private final BusQueryService service =
            new BusQueryService(busRepository, studentRepository, studentGuardianRepository, routePlanRepository);

    private static final Long TENANT_ID = 1L;
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 2);

    @Test
    void listBuses_onboardExceedsAssignCapacity_marksOverCapacity() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        Route route = route(10L, TENANT_ID, 2); // 정원 2명
        Bus bus = bus(1L, TENANT_ID, route);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        given(studentRepository.findByAssignedBusIdIn(List.of(1L))).willReturn(List.of(
                student(1L, bus), student(2L, bus), student(3L, bus))); // 3명 배정 — 정원 초과

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
        given(studentRepository.findByAssignedBusIdIn(List.of(1L))).willReturn(List.of(student(1L, bus)));

        List<BusResponse> responses = service.listBuses(admin, TENANT_ID);

        assertThat(responses.get(0).overCapacity()).isFalse();
    }

    @Test
    void listBuses_noRouteAssigned_overCapacityAlwaysFalse() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        Bus bus = bus(1L, TENANT_ID, null); // 노선 미배정
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        given(studentRepository.findByAssignedBusIdIn(List.of(1L)))
                .willReturn(List.of(student(1L, bus), student(2L, bus)));

        List<BusResponse> responses = service.listBuses(admin, TENANT_ID);

        assertThat(responses.get(0).assignCapacity()).isNull();
        assertThat(responses.get(0).overCapacity()).isFalse();
    }

    /** 버스가 늘어도 학생 조회는 1회여야 한다 — 보호자까지 얹기 전에 목록 N+1 을 끊는다. */
    @Test
    void listBuses_queriesStudentsOnce() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        Bus first = bus(1L, TENANT_ID, null);
        Bus second = bus(2L, TENANT_ID, null);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(first, second));
        given(studentRepository.findByAssignedBusIdIn(List.of(1L, 2L)))
                .willReturn(List.of(student(1L, first), student(2L, first), student(3L, second)));

        List<BusResponse> responses = service.listBuses(admin, TENANT_ID);

        assertThat(responses).extracting(BusResponse::onboard).containsExactly(2, 1);
        verify(studentRepository, times(1)).findByAssignedBusIdIn(any());
        verify(studentRepository, never()).findByAssignedBusId(anyLong());
    }

    @Test
    void getBus_otherTenantAdmin_throwsForbidden() {
        AuthUser otherAdmin = authUser(999L, Role.ACADEMY_ADMIN);
        Bus bus = bus(1L, TENANT_ID, null);
        given(busRepository.findById(1L)).willReturn(Optional.of(bus));

        assertThatThrownBy(() -> service.getBus(otherAdmin, 1L, LocalDate.now()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void getBus_notFound_throwsNotFound() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        given(busRepository.findById(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBus(admin, 1L, LocalDate.now()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void getBus_accessible_returnsRoster() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        Bus bus = bus(1L, TENANT_ID, null);
        given(busRepository.findById(1L)).willReturn(Optional.of(bus));
        given(studentRepository.findByAssignedBusId(1L)).willReturn(List.of(student(1L, bus)));

        BusDetailResponse response = service.getBus(admin, 1L, SERVICE_DATE);

        assertThat(response.roster()).hasSize(1);
        assertThat(response.bus().onboard()).isEqualTo(1);
        assertThat(response.serviceDate()).isEqualTo(SERVICE_DATE);
        assertThat(response.plans()).isEmpty();
    }

    /** 요구 6·7 — 관제 화면이 선탑자와 보호자 연락처를 한 응답에서 받는다. */
    @Test
    void getBus_includesAttendantAndGuardians() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        User attendant = user(30L, "최선탑", "010-3333-3333");
        Bus bus = busWithCrew(1L, TENANT_ID, user(20L, "박기사", "010-2222-2222"), attendant);
        Student student = student(5L, bus);
        given(busRepository.findById(1L)).willReturn(Optional.of(bus));
        given(studentRepository.findByAssignedBusId(1L)).willReturn(List.of(student));
        given(studentGuardianRepository.findWithGuardianByStudentIdIn(List.of(5L)))
                .willReturn(List.of(guardian(student, user(40L, "김보호", "010-4444-4444"), "모")));

        BusDetailResponse response = service.getBus(admin, 1L, SERVICE_DATE);

        assertThat(response.bus().attendantId()).isEqualTo(30L);
        assertThat(response.bus().attendantName()).isEqualTo("최선탑");
        assertThat(response.bus().attendantPhone()).isEqualTo("010-3333-3333");
        assertThat(response.bus().driverPhone()).isEqualTo("010-2222-2222");
        assertThat(response.roster()).hasSize(1);
        BusDetailResponse.RosterEntry entry = response.roster().get(0);
        assertThat(entry.studentId()).isEqualTo(5L);
        assertThat(entry.guardians()).hasSize(1);
        assertThat(entry.guardians().get(0).name()).isEqualTo("김보호");
        assertThat(entry.guardians().get(0).phone()).isEqualTo("010-4444-4444");
        assertThat(entry.guardians().get(0).relation()).isEqualTo("모");
    }

    /** 요구 7 — 버스를 누르면 지도에 강조할 당일 배포 노선(정차 순서 + polyline)이 함께 온다. */
    @Test
    void getBus_publishedPlanExists_returnsStopsAndPolyline() {
        AuthUser admin = authUser(TENANT_ID, Role.ACADEMY_ADMIN);
        Bus bus = bus(1L, TENANT_ID, null);
        given(busRepository.findById(1L)).willReturn(Optional.of(bus));
        given(studentRepository.findByAssignedBusId(1L)).willReturn(List.of());
        given(routePlanRepository.findByBusIdAndServiceDateAndStatusOrderByDirectionAsc(
                eq(1L), eq(SERVICE_DATE), eq(RoutePlanStatus.PUBLISHED)))
                .willReturn(List.of(publishedPlan(70L)));

        BusDetailResponse response = service.getBus(admin, 1L, SERVICE_DATE);

        assertThat(response.plans()).hasSize(1);
        BusDetailResponse.RoutePlanView plan = response.plans().get(0);
        assertThat(plan.routePlanId()).isEqualTo(70L);
        assertThat(plan.direction()).isEqualTo(RouteDirection.PICKUP);
        assertThat(plan.polyline()).isEqualTo("encoded");
        assertThat(plan.stops()).hasSize(2);
        assertThat(plan.stops().get(0).seq()).isEqualTo(1);
        assertThat(plan.stops().get(0).studentId()).isEqualTo(5L);
        assertThat(plan.stops().get(1).etaSeconds()).isEqualTo(600L);
    }

    // ── getMyBus: 기사가 자기 busId 를 알아내는 경로(C0) ──

    @Test
    void getMyBus_driverHasBus_returnsItWithOnboardCount() {
        AuthUser driver = authUser(TENANT_ID, Role.DRIVER);
        Bus bus = busWithDriver(1L, TENANT_ID, user(driver.userId()));
        given(busRepository.findByDriverIdOrderByIdAsc(driver.userId())).willReturn(List.of(bus));
        given(studentRepository.findByAssignedBusId(1L)).willReturn(List.of(student(1L, bus), student(2L, bus)));

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

    private Bus busWithCrew(Long id, Long tenantId, User driver, User attendant) {
        Bus bus = Bus.builder().tenant(tenant(tenantId)).name("3호차").seatCapacity(25)
                .driver(driver).attendant(attendant).build();
        ReflectionTestUtils.setField(bus, "id", id);
        return bus;
    }

    private RoutePlan publishedPlan(Long id) {
        RoutePlan plan = RoutePlan.builder()
                .tenantId(TENANT_ID).busId(1L).direction(RouteDirection.PICKUP)
                .status(RoutePlanStatus.PUBLISHED).version(2).serviceDate(SERVICE_DATE)
                .polyline("encoded").totalDistanceM(1200).totalDurationS(900)
                .build();
        ReflectionTestUtils.setField(plan, "id", id);
        plan.addStop(5L, 37.1, 127.1, 300L);
        plan.addStop(6L, 37.2, 127.2, 600L);
        return plan;
    }

    private User user(Long id) {
        return user(id, "박기사", null);
    }

    private User user(Long id, String name, String phone) {
        User user = User.builder().email("user" + id + "@school.com").name(name).password("x").phone(phone).build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Route route(Long id, Long tenantId, int assignCapacity) {
        Route route = Route.builder().tenant(tenant(tenantId)).name("A노선").assignCapacity(assignCapacity).build();
        ReflectionTestUtils.setField(route, "id", id);
        return route;
    }

    private Student student(Long id, Bus assignedBus) {
        Student student = Student.builder().tenant(tenant(TENANT_ID)).name("학생" + id)
                .assignedBus(assignedBus).build();
        ReflectionTestUtils.setField(student, "id", id);
        return student;
    }

    private StudentGuardian guardian(Student student, User guardian, String relation) {
        return StudentGuardian.builder().student(student).guardian(guardian).relation(relation).build();
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
