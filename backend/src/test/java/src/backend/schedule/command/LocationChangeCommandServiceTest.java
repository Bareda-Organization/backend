package src.backend.schedule.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.bus.entity.Bus;
import src.backend.drivesession.repository.spec.DriveSessionRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.route.entity.Stop;
import src.backend.routing.command.RoutingCommandService;
import src.backend.routing.domain.RouteDirection;
import src.backend.routing.domain.RoutePlanStatus;
import src.backend.routing.dto.RoutePlanComparison;
import src.backend.routing.dto.RoutePlanComparison.Delta;
import src.backend.routing.dto.RoutePlanComparison.Snapshot;
import src.backend.routing.dto.RoutePlanComparison.StopView;
import src.backend.routing.entity.RoutePlan;
import src.backend.routing.query.RoutePlanSimulationService;
import src.backend.routing.repository.spec.RoutePlanRepository;
import src.backend.schedule.dto.CreateLocationChangeRequest;
import src.backend.schedule.dto.LocationChangeRequestResponse;
import src.backend.schedule.entity.LocationChangeDecision;
import src.backend.schedule.entity.LocationChangeRequest;
import src.backend.schedule.repository.spec.LocationChangeRequestRepository;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.user.entity.Role;
import src.backend.user.entity.User;

/**
 * 학부모 등하원 위치 변경 자동 판정 단위 테스트(BE-10) — 4개 판정 분기(BLOCKED/APPLIED/REPLANNED/REJECTED)와
 * 정원 초과·보호자 권한을 고정한다. {@link src.backend.schedule.command.ScheduleCommandServiceTest} 와
 * 동일한 패턴(순수 Mockito, Spring 컨텍스트 없음).
 *
 * <p>모든 판정 분기에서 {@code locationChangeRequestRepository.save} 를 함께 검증한다 —
 * 감사 로그 테이블이 없어 반려·차단도 요청 행으로만 남기 때문이다.
 */
class LocationChangeCommandServiceTest {

    private static final Long TENANT_ID = 1L;
    private static final Long BUS_ID = 5L;
    private static final Long STUDENT_ID = 10L;
    private static final Long PARENT_ID = 100L;
    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 8, 2);

    private static final double NEW_LAT = 37.900;
    private static final double NEW_LNG = 127.900;
    private static final double OLD_LAT = 37.400;
    private static final double OLD_LNG = 127.400;

    private final LocationChangeRequestRepository locationChangeRequestRepository =
            mock(LocationChangeRequestRepository.class);
    private final StudentGuardianRepository studentGuardianRepository = mock(StudentGuardianRepository.class);
    private final DriveSessionRepository driveSessionRepository = mock(DriveSessionRepository.class);
    private final RoutePlanRepository routePlanRepository = mock(RoutePlanRepository.class);
    private final RoutePlanSimulationService simulationService = mock(RoutePlanSimulationService.class);
    private final RoutingCommandService routingCommandService = mock(RoutingCommandService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private final LocationChangeCommandService service = new LocationChangeCommandService(
            locationChangeRequestRepository, studentGuardianRepository, driveSessionRepository,
            routePlanRepository, simulationService, routingCommandService, eventPublisher);

    @Test
    void create_driveSessionExists_returnsBlockedAndDoesNotChangeCoordinates() {
        Student student = dropoffStudent(bus(25));
        givenGuardianOf(student);
        givenSaveEchoesWithId();
        given(driveSessionRepository.existsByBusIdAndDirectionAndServiceDate(BUS_ID, RouteDirection.DROPOFF, TARGET_DATE))
                .willReturn(true);

        LocationChangeRequestResponse response = service.create(parent(), request(RouteDirection.DROPOFF));

        assertThat(response.decision()).isEqualTo(LocationChangeDecision.BLOCKED);
        assertThat(student.getDropoffLat()).isEqualTo(OLD_LAT);   // 좌표는 그대로다
        assertThat(student.getDropoffLng()).isEqualTo(OLD_LNG);
        verify(locationChangeRequestRepository).save(any());       // 차단도 감사 대상이다
        verify(simulationService, never()).compare(any(), any(), any(), any());
        verify(routingCommandService, never()).republishForBus(any(), any(), any(), any());
    }

    @Test
    void create_noAssignedBus_returnsAppliedAndUpdatesCoordinates() {
        Student student = dropoffStudent(null);   // 아직 배차 전
        givenGuardianOf(student);
        givenSaveEchoesWithId();

        LocationChangeRequestResponse response = service.create(parent(), request(RouteDirection.DROPOFF));

        assertThat(response.decision()).isEqualTo(LocationChangeDecision.APPLIED);
        assertThat(response.appliedPlanId()).isNull();
        assertThat(student.getDropoffLat()).isEqualTo(NEW_LAT);
        assertThat(student.getDropoffLng()).isEqualTo(NEW_LNG);
        verify(locationChangeRequestRepository).save(any());
        verify(routingCommandService, never()).republishForBus(any(), any(), any(), any());
    }

    @Test
    void create_withinThreshold_returnsReplannedAndRepublishes() {
        Student student = dropoffStudent(bus(25));
        givenGuardianOf(student);
        givenSaveEchoesWithId();
        givenBaselinePlan();
        givenComparison(500.0, 200.0, 2, 25);   // 거리 +500m, 시간 +200s — 둘 다 임계 이내
        given(routingCommandService.republishForBus(BUS_ID, RouteDirection.DROPOFF, TARGET_DATE, PARENT_ID))
                .willReturn(777L);

        LocationChangeRequestResponse response = service.create(parent(), request(RouteDirection.DROPOFF));

        assertThat(response.decision()).isEqualTo(LocationChangeDecision.REPLANNED);
        assertThat(response.appliedPlanId()).isEqualTo(777L);
        assertThat(response.deltaDistanceM()).isEqualTo(500.0);
        assertThat(response.deltaDurationS()).isEqualTo(200.0);
        assertThat(student.getDropoffLat()).isEqualTo(NEW_LAT);
        verify(routingCommandService).republishForBus(BUS_ID, RouteDirection.DROPOFF, TARGET_DATE, PARENT_ID);
        verify(locationChangeRequestRepository).save(any());
    }

    @Test
    void create_exceedsDurationOnly_returnsRejectedAndKeepsCoordinates() {
        Student student = dropoffStudent(bus(25));
        givenGuardianOf(student);
        givenSaveEchoesWithId();
        givenBaselinePlan();
        givenComparison(100.0, 400.0, 2, 25);   // 거리는 여유롭지만 시간이 +400s — AND 조건이라 반려

        LocationChangeRequestResponse response = service.create(parent(), request(RouteDirection.DROPOFF));

        assertThat(response.decision()).isEqualTo(LocationChangeDecision.REJECTED);
        assertThat(student.getDropoffLat()).isEqualTo(OLD_LAT);   // 반려면 좌표를 되돌리는 게 아니라 애초에 건드리지 않는다
        assertThat(response.deltaDurationS()).isEqualTo(400.0);
        verify(locationChangeRequestRepository).save(any());
        verify(routingCommandService, never()).republishForBus(any(), any(), any(), any());
    }

    @Test
    void create_candidateExceedsSeatCapacity_returnsRejected() {
        Student student = dropoffStudent(bus(2));
        givenGuardianOf(student);
        givenSaveEchoesWithId();
        givenBaselinePlan();
        givenComparison(0.0, 0.0, 3, 2);   // 델타는 0인데 baseline 이 이미 정원을 넘겨 초과가 고착된다

        LocationChangeRequestResponse response = service.create(parent(), request(RouteDirection.DROPOFF));

        assertThat(response.decision()).isEqualTo(LocationChangeDecision.REJECTED);
        assertThat(response.reason()).contains("정원");
        assertThat(student.getDropoffLat()).isEqualTo(OLD_LAT);
        verify(locationChangeRequestRepository).save(any());
        verify(routingCommandService, never()).republishForBus(any(), any(), any(), any());
    }

    @Test
    void create_notGuardian_throwsForbidden() {
        given(studentGuardianRepository.findByGuardianId(PARENT_ID)).willReturn(List.of());

        assertThatThrownBy(() -> service.create(parent(), request(RouteDirection.DROPOFF)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(locationChangeRequestRepository, never()).save(any());
    }

    @Test
    void create_pickupDirection_updatesStudentPickupFieldsOnly() {
        Stop sharedStop = stop(37.300, 127.300);
        Student student = pickupStudent(sharedStop);
        givenGuardianOf(student);
        givenSaveEchoesWithId();

        LocationChangeRequestResponse response = service.create(parent(), request(RouteDirection.PICKUP));

        assertThat(response.decision()).isEqualTo(LocationChangeDecision.APPLIED);
        assertThat(student.getPickupLat()).isEqualTo(NEW_LAT);
        assertThat(student.getPickupLng()).isEqualTo(NEW_LNG);
        // ⚠️ Stop 은 여러 학생이 공유하는 행이라 절대 건드리지 않는다(D-K)
        assertThat(sharedStop.getLat()).isEqualTo(37.300);
        assertThat(sharedStop.getLng()).isEqualTo(127.300);
        assertThat(student.getBoardingStop()).isSameAs(sharedStop);
        // 하원 좌표는 이 요청과 무관하다
        assertThat(student.getDropoffLat()).isNull();
    }

    // ── fixtures ──

    private void givenGuardianOf(Student student) {
        User guardian = User.builder().email("parent@school.com").name("이부모").password("x").build();
        StudentGuardian link = StudentGuardian.builder().student(student).guardian(guardian).relation("모").build();
        given(studentGuardianRepository.findByGuardianId(PARENT_ID)).willReturn(List.of(link));
    }

    /** 저장 시 id 를 채워 돌려준다 — 이벤트·응답이 요청 id 를 필요로 한다. */
    private void givenSaveEchoesWithId() {
        given(locationChangeRequestRepository.save(any(LocationChangeRequest.class))).willAnswer(inv -> {
            LocationChangeRequest e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", 1L);
            return e;
        });
    }

    private void givenBaselinePlan() {
        RoutePlan plan = RoutePlan.builder()
                .tenantId(TENANT_ID).busId(BUS_ID).direction(RouteDirection.DROPOFF)
                .status(RoutePlanStatus.PUBLISHED).version(3).serviceDate(TARGET_DATE)
                .polyline("[[37.5,127.0]]").totalDistanceM(4000.0).totalDurationS(240.0).build();
        ReflectionTestUtils.setField(plan, "id", 900L);
        given(routePlanRepository.findTopByBusIdAndDirectionOrderByVersionDesc(BUS_ID, RouteDirection.DROPOFF))
                .willReturn(Optional.of(plan));
    }

    private void givenComparison(double deltaDistanceM, double deltaDurationS, int candidateStops, int seatCapacity) {
        List<StopView> stops = new ArrayList<>();
        for (int i = 1; i <= candidateStops; i++) {
            stops.add(new StopView(i, (long) i, "학생" + i, "하차지" + i, 37.5 + i, 127.0 + i, 60L * i));
        }
        Snapshot baseline = new Snapshot(900L, 3, 4000.0, 240.0, List.of(), "[[37.5,127.0]]");
        Snapshot candidate = new Snapshot(null, null, 4000.0 + deltaDistanceM, 240.0 + deltaDurationS,
                stops, "[[37.5,127.0]]");
        Delta delta = new Delta(deltaDistanceM, deltaDurationS, 0);
        given(simulationService.compare(any(), any(), any(), any()))
                .willReturn(new RoutePlanComparison(baseline, candidate, delta, seatCapacity));
    }

    private CreateLocationChangeRequest request(RouteDirection direction) {
        return new CreateLocationChangeRequest(STUDENT_ID, direction, TARGET_DATE, NEW_LAT, NEW_LNG, "역삼동 주민센터 앞");
    }

    private AuthUser parent() {
        return new AuthUser(PARENT_ID, "parent@school.com",
                List.of(new AuthUser.Membership(TENANT_ID, Role.PARENT)));
    }

    private Tenant tenant() {
        Tenant tenant = Tenant.builder().name("한빛학원").lat(37.500).lng(127.000).build();
        ReflectionTestUtils.setField(tenant, "id", TENANT_ID);
        return tenant;
    }

    private Bus bus(int seatCapacity) {
        Bus bus = Bus.builder().tenant(tenant()).name("3호차").seatCapacity(seatCapacity).build();
        ReflectionTestUtils.setField(bus, "id", BUS_ID);
        return bus;
    }

    private Stop stop(double lat, double lng) {
        return Stop.builder().name("공용 정류장").seq(1).lat(lat).lng(lng).build();
    }

    private Student dropoffStudent(Bus assignedBus) {
        Student student = Student.builder().tenant(tenant()).name("김민준").assignedBus(assignedBus).build();
        ReflectionTestUtils.setField(student, "id", STUDENT_ID);
        student.updateDropoff("기존 하차지", OLD_LAT, OLD_LNG);
        return student;
    }

    private Student pickupStudent(Stop boardingStop) {
        Student student = Student.builder().tenant(tenant()).name("김민준").boardingStop(boardingStop).build();
        ReflectionTestUtils.setField(student, "id", STUDENT_ID);
        return student;
    }
}
