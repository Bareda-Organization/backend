package src.backend.operations.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.attendance.entity.AttendanceException;
import src.backend.attendance.entity.AttendanceType;
import src.backend.attendance.repository.spec.AttendanceExceptionRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.drivesession.dto.DriveSessionResponse;
import src.backend.drivesession.entity.DriveSessionStatus;
import src.backend.drivesession.query.DriveSessionQueryService;
import src.backend.global.security.AuthUser;
import src.backend.location.dto.BusLocationPing;
import src.backend.location.dto.LocationOrigin;
import src.backend.location.repository.spec.BusLocationRepository;
import src.backend.operations.dto.BusOperationSummary;
import src.backend.operations.dto.BusSessionStatus;
import src.backend.rideevent.entity.RideEvent;
import src.backend.rideevent.entity.RideSource;
import src.backend.rideevent.entity.RideType;
import src.backend.rideevent.repository.spec.RideEventRepository;
import src.backend.routing.domain.RouteDirection;
import src.backend.routing.domain.RoutePlanStatus;
import src.backend.routing.entity.RoutePlan;
import src.backend.routing.repository.spec.RoutePlanRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.spec.StudentRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.user.entity.Role;
import src.backend.user.entity.User;

/**
 * 버스별 운행 현황 목록(BG-21) 단위 테스트. 팀 지시대로 우선 고정할 것:
 * (1) 버스 수와 무관하게 저장소 호출 횟수가 늘지 않는지(N+1 회피), (2) 결석(absent)과
 * 미탑승(no_show)이 절대 섞이지 않는지, (3) 선탑자 미배정이 눈에 띄게 표시되는지,
 * (4) 좌표 TTL 만료(stale)가 좌표 부재와 구분되는지, (5) 세션 진행 상태 3단계 판정.
 */
class OperationsQueryServiceTest {

    private static final Long TENANT_ID = 1L;

    private final BusRepository busRepository = mock(BusRepository.class);
    private final StudentRepository studentRepository = mock(StudentRepository.class);
    private final RideEventRepository rideEventRepository = mock(RideEventRepository.class);
    private final AttendanceExceptionRepository attendanceExceptionRepository = mock(AttendanceExceptionRepository.class);
    private final RoutePlanRepository routePlanRepository = mock(RoutePlanRepository.class);
    private final BusLocationRepository busLocationRepository = mock(BusLocationRepository.class);
    private final DriveSessionQueryService driveSessionQueryService = mock(DriveSessionQueryService.class);

    private final OperationsQueryService service = new OperationsQueryService(
            busRepository, studentRepository, rideEventRepository, attendanceExceptionRepository,
            routePlanRepository, busLocationRepository, driveSessionQueryService);

    // ── N+1 회피: 버스 수에 비례해 저장소 호출이 늘지 않는다 ──

    @Test
    void getBusOperations_batchesRepositoryCalls_regardlessOfBusCount() {
        Bus bus1 = bus(1L, null);
        Bus bus2 = bus(2L, null);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus1, bus2));
        given(studentRepository.findByAssignedBusIdInAndActiveTrue(any())).willReturn(List.of(
                student(1L, bus1), student(2L, bus1), student(3L, bus2), student(4L, bus2)));
        given(rideEventRepository.findByTenantIdAndOccurredAtBetweenOrderByOccurredAtAsc(any(), any(), any()))
                .willReturn(List.of());
        given(attendanceExceptionRepository.findByTenantIdOrderByTargetDateDesc(TENANT_ID)).willReturn(List.of());
        given(routePlanRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).willReturn(List.of());
        given(driveSessionQueryService.getTenantHistory(any(), any(), any())).willReturn(List.of());
        given(busLocationRepository.findLatest(any())).willReturn(Optional.empty());

        List<BusOperationSummary> result = service.getBusOperations(admin(), TENANT_ID);

        assertThat(result).hasSize(2);
        // 버스 2대 × 학생 4명이지만 아래 저장소는 학생·버스 단위 반복 없이 정확히 한 번씩만 불린다.
        verify(busRepository, times(1)).findByTenantId(TENANT_ID);
        verify(studentRepository, times(1)).findByAssignedBusIdInAndActiveTrue(any());
        verify(rideEventRepository, times(1))
                .findByTenantIdAndOccurredAtBetweenOrderByOccurredAtAsc(any(), any(), any());
        verify(attendanceExceptionRepository, times(1)).findByTenantIdOrderByTargetDateDesc(TENANT_ID);
        verify(routePlanRepository, times(1)).findByTenantIdOrderByCreatedAtDesc(TENANT_ID);
        // 세션은 상태(IN_PROGRESS/COMPLETED) 당 한 번 — 버스 수와 무관하다.
        verify(driveSessionQueryService, times(2)).getTenantHistory(any(), any(), any());
        verify(studentRepository, never()).findById(any());
        verify(rideEventRepository, never())
                .findByStudentIdAndOccurredAtBetweenOrderByOccurredAtAsc(any(), any(), any());
    }

    // ── absent(승인된 결석) vs no_show(연락 없이 안 옴) ──

    @Test
    void absent_notConflatedWithNoShow_whenExceptionApprovedToday() {
        Bus bus = bus(1L, null);
        Student absentStudent = student(1L, bus);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        given(studentRepository.findByAssignedBusIdInAndActiveTrue(any())).willReturn(List.of(absentStudent));
        given(rideEventRepository.findByTenantIdAndOccurredAtBetweenOrderByOccurredAtAsc(any(), any(), any()))
                .willReturn(List.of());
        AttendanceException approvedToday = attendanceException(1L, LocalDate.now());
        approvedToday.approve(9L);
        given(attendanceExceptionRepository.findByTenantIdOrderByTargetDateDesc(TENANT_ID))
                .willReturn(List.of(approvedToday));
        given(routePlanRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).willReturn(List.of());
        // 세션은 20분 전 시작 — 정차 도달 시각을 세션 시작 시각으로 추정(계획 없음)한다면
        // 미승차 임계값(10분)을 넘겨 결석 처리가 없으면 NO_SHOW 로 잘못 잡힐 상황이다.
        DriveSessionResponse inProgress = driveSession(1L, DriveSessionStatus.IN_PROGRESS,
                LocalDateTime.now().minusMinutes(20));
        given(driveSessionQueryService.getTenantHistory(admin(), TENANT_ID, DriveSessionStatus.IN_PROGRESS))
                .willReturn(List.of(inProgress));
        given(driveSessionQueryService.getTenantHistory(admin(), TENANT_ID, DriveSessionStatus.COMPLETED))
                .willReturn(List.of());
        given(busLocationRepository.findLatest(any())).willReturn(Optional.empty());

        List<BusOperationSummary> result = service.getBusOperations(admin(), TENANT_ID);

        BusOperationSummary.Counts counts = result.get(0).counts();
        assertThat(counts.absent()).isEqualTo(1);
        assertThat(counts.noShow()).isEqualTo(0);
    }

    @Test
    void noShow_flaggedWhenStopReachedPastThreshold_andNoApprovedAbsence() {
        Bus bus = bus(1L, null);
        Student missingStudent = student(1L, bus);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        given(studentRepository.findByAssignedBusIdInAndActiveTrue(any())).willReturn(List.of(missingStudent));
        given(rideEventRepository.findByTenantIdAndOccurredAtBetweenOrderByOccurredAtAsc(any(), any(), any()))
                .willReturn(List.of());
        given(attendanceExceptionRepository.findByTenantIdOrderByTargetDateDesc(TENANT_ID)).willReturn(List.of());
        given(routePlanRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).willReturn(List.of());
        DriveSessionResponse inProgress = driveSession(1L, DriveSessionStatus.IN_PROGRESS,
                LocalDateTime.now().minusMinutes(20));
        given(driveSessionQueryService.getTenantHistory(admin(), TENANT_ID, DriveSessionStatus.IN_PROGRESS))
                .willReturn(List.of(inProgress));
        given(driveSessionQueryService.getTenantHistory(admin(), TENANT_ID, DriveSessionStatus.COMPLETED))
                .willReturn(List.of());
        given(busLocationRepository.findLatest(any())).willReturn(Optional.empty());

        List<BusOperationSummary> result = service.getBusOperations(admin(), TENANT_ID);

        BusOperationSummary.Counts counts = result.get(0).counts();
        assertThat(counts.noShow()).isEqualTo(1);
        assertThat(counts.absent()).isEqualTo(0);
    }

    // ── crew.attendantMissing ──

    @Test
    void crew_attendantMissing_flaggedTrue_whenBusHasNoAttendant() {
        User driver = user(10L);
        Bus bus = busWithCrew(1L, driver, null);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        given(studentRepository.findByAssignedBusIdInAndActiveTrue(any())).willReturn(List.of());
        given(rideEventRepository.findByTenantIdAndOccurredAtBetweenOrderByOccurredAtAsc(any(), any(), any()))
                .willReturn(List.of());
        given(attendanceExceptionRepository.findByTenantIdOrderByTargetDateDesc(TENANT_ID)).willReturn(List.of());
        given(routePlanRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).willReturn(List.of());
        given(driveSessionQueryService.getTenantHistory(any(), any(), any())).willReturn(List.of());
        given(busLocationRepository.findLatest(any())).willReturn(Optional.empty());

        List<BusOperationSummary> result = service.getBusOperations(admin(), TENANT_ID);

        BusOperationSummary.Crew crew = result.get(0).crew();
        assertThat(crew.driverName()).isEqualTo(driver.getName());
        assertThat(crew.attendantName()).isNull();
        assertThat(crew.attendantMissing()).isTrue();
    }

    @Test
    void crew_attendantMissing_false_whenBothAssigned() {
        User driver = user(10L);
        User attendant = user(20L);
        Bus bus = busWithCrew(1L, driver, attendant);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        given(studentRepository.findByAssignedBusIdInAndActiveTrue(any())).willReturn(List.of());
        given(rideEventRepository.findByTenantIdAndOccurredAtBetweenOrderByOccurredAtAsc(any(), any(), any()))
                .willReturn(List.of());
        given(attendanceExceptionRepository.findByTenantIdOrderByTargetDateDesc(TENANT_ID)).willReturn(List.of());
        given(routePlanRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).willReturn(List.of());
        given(driveSessionQueryService.getTenantHistory(any(), any(), any())).willReturn(List.of());
        given(busLocationRepository.findLatest(any())).willReturn(Optional.empty());

        List<BusOperationSummary> result = service.getBusOperations(admin(), TENANT_ID);

        assertThat(result.get(0).crew().attendantMissing()).isFalse();
    }

    // ── location.stale: 좌표 TTL 만료를 부재와 구분 ──

    @Test
    void location_stale_true_whenNoRecentPing() {
        Bus bus = bus(1L, null);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        given(studentRepository.findByAssignedBusIdInAndActiveTrue(any())).willReturn(List.of());
        given(rideEventRepository.findByTenantIdAndOccurredAtBetweenOrderByOccurredAtAsc(any(), any(), any()))
                .willReturn(List.of());
        given(attendanceExceptionRepository.findByTenantIdOrderByTargetDateDesc(TENANT_ID)).willReturn(List.of());
        given(routePlanRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).willReturn(List.of());
        given(driveSessionQueryService.getTenantHistory(any(), any(), any())).willReturn(List.of());
        given(busLocationRepository.findLatest(1L)).willReturn(Optional.empty());

        List<BusOperationSummary> result = service.getBusOperations(admin(), TENANT_ID);

        BusOperationSummary.LocationInfo location = result.get(0).location();
        assertThat(location.stale()).isTrue();
        assertThat(location.lat()).isNull();
        assertThat(location.origin()).isNull();
    }

    @Test
    void location_stale_false_whenFreshPingExists() {
        Bus bus = bus(1L, null);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        given(studentRepository.findByAssignedBusIdInAndActiveTrue(any())).willReturn(List.of());
        given(rideEventRepository.findByTenantIdAndOccurredAtBetweenOrderByOccurredAtAsc(any(), any(), any()))
                .willReturn(List.of());
        given(attendanceExceptionRepository.findByTenantIdOrderByTargetDateDesc(TENANT_ID)).willReturn(List.of());
        given(routePlanRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).willReturn(List.of());
        given(driveSessionQueryService.getTenantHistory(any(), any(), any())).willReturn(List.of());
        given(busLocationRepository.findLatest(1L)).willReturn(Optional.of(
                new BusLocationPing(1L, TENANT_ID, 37.5, 127.0, LocalDateTime.now(), LocationOrigin.GPS)));

        List<BusOperationSummary> result = service.getBusOperations(admin(), TENANT_ID);

        BusOperationSummary.LocationInfo location = result.get(0).location();
        assertThat(location.stale()).isFalse();
        assertThat(location.lat()).isEqualTo(37.5);
        assertThat(location.origin()).isEqualTo(LocationOrigin.GPS);
    }

    // ── sessionStatus 3단계 판정 ──

    @Test
    void sessionStatus_notStarted_whenNoSessionToday() {
        Bus bus = bus(1L, null);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        given(studentRepository.findByAssignedBusIdInAndActiveTrue(any())).willReturn(List.of());
        given(rideEventRepository.findByTenantIdAndOccurredAtBetweenOrderByOccurredAtAsc(any(), any(), any()))
                .willReturn(List.of());
        given(attendanceExceptionRepository.findByTenantIdOrderByTargetDateDesc(TENANT_ID)).willReturn(List.of());
        given(routePlanRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).willReturn(List.of());
        given(driveSessionQueryService.getTenantHistory(any(), any(), any())).willReturn(List.of());
        given(busLocationRepository.findLatest(any())).willReturn(Optional.empty());

        List<BusOperationSummary> result = service.getBusOperations(admin(), TENANT_ID);

        assertThat(result.get(0).sessionStatus()).isEqualTo(BusSessionStatus.NOT_STARTED);
        assertThat(result.get(0).direction()).isNull();
    }

    @Test
    void sessionStatus_notStarted_guessesDirectionFromTodaysPublishedPlan() {
        Bus bus = bus(1L, null);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        given(studentRepository.findByAssignedBusIdInAndActiveTrue(any())).willReturn(List.of());
        given(rideEventRepository.findByTenantIdAndOccurredAtBetweenOrderByOccurredAtAsc(any(), any(), any()))
                .willReturn(List.of());
        given(attendanceExceptionRepository.findByTenantIdOrderByTargetDateDesc(TENANT_ID)).willReturn(List.of());
        RoutePlan pickupPlan = routePlan(1L, bus.getId(), RouteDirection.PICKUP, RoutePlanStatus.PUBLISHED, 1);
        given(routePlanRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).willReturn(List.of(pickupPlan));
        given(driveSessionQueryService.getTenantHistory(any(), any(), any())).willReturn(List.of());
        given(busLocationRepository.findLatest(any())).willReturn(Optional.empty());

        List<BusOperationSummary> result = service.getBusOperations(admin(), TENANT_ID);

        assertThat(result.get(0).sessionStatus()).isEqualTo(BusSessionStatus.NOT_STARTED);
        assertThat(result.get(0).direction()).isEqualTo(RouteDirection.PICKUP);
    }

    @Test
    void sessionStatus_inProgress_whenSessionRunning() {
        Bus bus = bus(1L, null);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        given(studentRepository.findByAssignedBusIdInAndActiveTrue(any())).willReturn(List.of());
        given(rideEventRepository.findByTenantIdAndOccurredAtBetweenOrderByOccurredAtAsc(any(), any(), any()))
                .willReturn(List.of());
        given(attendanceExceptionRepository.findByTenantIdOrderByTargetDateDesc(TENANT_ID)).willReturn(List.of());
        given(routePlanRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).willReturn(List.of());
        DriveSessionResponse inProgress = driveSession(1L, DriveSessionStatus.IN_PROGRESS,
                LocalDateTime.now().minusMinutes(5));
        given(driveSessionQueryService.getTenantHistory(admin(), TENANT_ID, DriveSessionStatus.IN_PROGRESS))
                .willReturn(List.of(inProgress));
        given(driveSessionQueryService.getTenantHistory(admin(), TENANT_ID, DriveSessionStatus.COMPLETED))
                .willReturn(List.of());
        given(busLocationRepository.findLatest(any())).willReturn(Optional.empty());

        List<BusOperationSummary> result = service.getBusOperations(admin(), TENANT_ID);

        assertThat(result.get(0).sessionStatus()).isEqualTo(BusSessionStatus.IN_PROGRESS);
        assertThat(result.get(0).direction()).isEqualTo(RouteDirection.PICKUP);
    }

    @Test
    void sessionStatus_completed_whenTodaysSessionEnded() {
        Bus bus = bus(1L, null);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        given(studentRepository.findByAssignedBusIdInAndActiveTrue(any())).willReturn(List.of());
        given(rideEventRepository.findByTenantIdAndOccurredAtBetweenOrderByOccurredAtAsc(any(), any(), any()))
                .willReturn(List.of());
        given(attendanceExceptionRepository.findByTenantIdOrderByTargetDateDesc(TENANT_ID)).willReturn(List.of());
        given(routePlanRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).willReturn(List.of());
        given(driveSessionQueryService.getTenantHistory(admin(), TENANT_ID, DriveSessionStatus.IN_PROGRESS))
                .willReturn(List.of());
        DriveSessionResponse completed = driveSession(1L, DriveSessionStatus.COMPLETED,
                LocalDateTime.now().minusHours(1));
        given(driveSessionQueryService.getTenantHistory(admin(), TENANT_ID, DriveSessionStatus.COMPLETED))
                .willReturn(List.of(completed));
        given(busLocationRepository.findLatest(any())).willReturn(Optional.empty());

        List<BusOperationSummary> result = service.getBusOperations(admin(), TENANT_ID);

        assertThat(result.get(0).sessionStatus()).isEqualTo(BusSessionStatus.COMPLETED);
    }

    // ── counts.total: 배정 학생 수와 5종 합이 일치 ──

    @Test
    void counts_total_matchesRosterSize_andSumsAllFiveStates() {
        Bus bus = bus(1L, null);
        Student boarded = student(1L, bus);
        Student waiting = student(2L, bus);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        given(studentRepository.findByAssignedBusIdInAndActiveTrue(any())).willReturn(List.of(boarded, waiting));
        given(rideEventRepository.findByTenantIdAndOccurredAtBetweenOrderByOccurredAtAsc(any(), any(), any()))
                .willReturn(List.of(rideEvent(1L, bus.getId(), RideType.BOARD)));
        given(attendanceExceptionRepository.findByTenantIdOrderByTargetDateDesc(TENANT_ID)).willReturn(List.of());
        given(routePlanRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).willReturn(List.of());
        given(driveSessionQueryService.getTenantHistory(any(), any(), any())).willReturn(List.of());
        given(busLocationRepository.findLatest(any())).willReturn(Optional.empty());

        List<BusOperationSummary> result = service.getBusOperations(admin(), TENANT_ID);

        BusOperationSummary.Counts counts = result.get(0).counts();
        assertThat(counts.total()).isEqualTo(2);
        assertThat(counts.boarded()).isEqualTo(1);
        assertThat(counts.waiting()).isEqualTo(1);
        assertThat(counts.total()).isEqualTo(
                counts.boarded() + counts.alighted() + counts.waiting() + counts.absent() + counts.noShow());
    }

    // ── helpers ──

    private AuthUser admin() {
        return new AuthUser(100L, "admin@school.com", List.of(new AuthUser.Membership(TENANT_ID, Role.ACADEMY_ADMIN)));
    }

    private Bus bus(Long id, User attendant) {
        Bus bus = Bus.builder().tenant(tenant()).name(id + "호차").seatCapacity(25).attendant(attendant).build();
        ReflectionTestUtils.setField(bus, "id", id);
        return bus;
    }

    private Bus busWithCrew(Long id, User driver, User attendant) {
        Bus bus = Bus.builder().tenant(tenant()).name(id + "호차").seatCapacity(25)
                .driver(driver).attendant(attendant).build();
        ReflectionTestUtils.setField(bus, "id", id);
        return bus;
    }

    private User user(Long id) {
        User user = User.builder().email("user" + id + "@school.com").name("사용자" + id).password("x").build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Student student(Long id, Bus assignedBus) {
        Student student = Student.builder().tenant(tenant()).name("학생" + id).assignedBus(assignedBus).build();
        ReflectionTestUtils.setField(student, "id", id);
        return student;
    }

    private Tenant tenant() {
        Tenant tenant = Tenant.builder().name("한빛학원").build();
        ReflectionTestUtils.setField(tenant, "id", TENANT_ID);
        return tenant;
    }

    private RideEvent rideEvent(Long studentId, Long busId, RideType type) {
        return RideEvent.builder().tenantId(TENANT_ID).studentId(studentId).busId(busId)
                .type(type).occurredAt(LocalDateTime.now()).source(RideSource.QR).build();
    }

    private AttendanceException attendanceException(Long studentId, LocalDate targetDate) {
        return AttendanceException.builder()
                .tenantId(TENANT_ID).studentId(studentId).type(AttendanceType.ABSENCE)
                .targetDate(targetDate).reason("사유").build();
    }

    private RoutePlan routePlan(Long id, Long busId, RouteDirection direction, RoutePlanStatus status, int version) {
        RoutePlan plan = RoutePlan.builder()
                .tenantId(TENANT_ID).busId(busId).direction(direction).status(status).version(version)
                .serviceDate(LocalDate.now()).polyline("[]").totalDistanceM(1000).totalDurationS(600).build();
        ReflectionTestUtils.setField(plan, "id", id);
        return plan;
    }

    private DriveSessionResponse driveSession(Long busId, DriveSessionStatus status, LocalDateTime startedAt) {
        return new DriveSessionResponse(busId, TENANT_ID, busId, 1L, RouteDirection.PICKUP,
                LocalDate.now(), null, status, startedAt, status == DriveSessionStatus.COMPLETED ? LocalDateTime.now() : null);
    }
}
