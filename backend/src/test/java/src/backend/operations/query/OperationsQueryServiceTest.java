package src.backend.operations.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import src.backend.global.error.BusinessException;
import src.backend.global.security.AuthUser;
import src.backend.location.dto.BusLocationPing;
import src.backend.location.dto.LocationOrigin;
import src.backend.location.repository.spec.BusLocationRepository;
import src.backend.operations.domain.BoardingStatus;
import src.backend.operations.dto.BusOperationDetail;
import src.backend.operations.dto.BusOperationSummary;
import src.backend.operations.dto.BusSessionStatus;
import src.backend.rideevent.entity.RideEvent;
import src.backend.rideevent.entity.RideSource;
import src.backend.rideevent.entity.RideType;
import src.backend.rideevent.repository.spec.RideEventRepository;
import src.backend.route.entity.Route;
import src.backend.route.entity.Stop;
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
    private final StudentGuardianRepository studentGuardianRepository = mock(StudentGuardianRepository.class);

    private final OperationsQueryService service = new OperationsQueryService(
            busRepository, studentRepository, rideEventRepository, attendanceExceptionRepository,
            routePlanRepository, busLocationRepository, driveSessionQueryService, studentGuardianRepository);

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

    // ── 버스 1대 상세(getBusOperationDetail, BG-21 §4.2) ──

    @Test
    void getBusOperationDetail_throwsNotFound_whenBusMissing() {
        given(busRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBusOperationDetail(admin(), 99L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void getBusOperationDetail_marksApprovedAbsence_asAbsent_notNoShow() {
        Bus bus = bus(1L, null);
        Student absentStudent = student(1L, bus);
        given(busRepository.findById(1L)).willReturn(Optional.of(bus));
        given(studentRepository.findByAssignedBusIdAndActiveTrue(1L)).willReturn(List.of(absentStudent));
        given(rideEventRepository.findByStudentIdInAndOccurredAtBetweenOrderByOccurredAtAsc(any(), any(), any()))
                .willReturn(List.of());
        AttendanceException approvedToday = attendanceException(1L, LocalDate.now());
        approvedToday.approve(9L);
        given(attendanceExceptionRepository.findByTenantIdOrderByTargetDateDesc(TENANT_ID))
                .willReturn(List.of(approvedToday));
        given(routePlanRepository.findByTenantIdAndBusIdOrderByCreatedAtDesc(TENANT_ID, 1L)).willReturn(List.of());
        // 세션은 20분 전 시작 — 결석 승인이 없으면 미승차 임계값(10분)을 넘겨 NO_SHOW 로 잡힐 상황.
        DriveSessionResponse inProgress = driveSession(1L, DriveSessionStatus.IN_PROGRESS,
                LocalDateTime.now().minusMinutes(20));
        given(driveSessionQueryService.getTenantHistory(admin(), TENANT_ID, DriveSessionStatus.IN_PROGRESS))
                .willReturn(List.of(inProgress));
        given(driveSessionQueryService.getTenantHistory(admin(), TENANT_ID, DriveSessionStatus.COMPLETED))
                .willReturn(List.of());
        given(studentGuardianRepository.findWithGuardianByStudentIdIn(any())).willReturn(List.of());

        BusOperationDetail detail = service.getBusOperationDetail(admin(), 1L);

        assertThat(detail.students()).hasSize(1);
        assertThat(detail.students().get(0).status()).isEqualTo(BoardingStatus.ABSENT);
    }

    @Test
    void getBusOperationDetail_allWaiting_whenSessionNotStarted() {
        Bus bus = bus(1L, null);
        Student s1 = student(1L, bus);
        Student s2 = student(2L, bus);
        given(busRepository.findById(1L)).willReturn(Optional.of(bus));
        given(studentRepository.findByAssignedBusIdAndActiveTrue(1L)).willReturn(List.of(s1, s2));
        given(rideEventRepository.findByStudentIdInAndOccurredAtBetweenOrderByOccurredAtAsc(any(), any(), any()))
                .willReturn(List.of());
        given(attendanceExceptionRepository.findByTenantIdOrderByTargetDateDesc(TENANT_ID)).willReturn(List.of());
        given(routePlanRepository.findByTenantIdAndBusIdOrderByCreatedAtDesc(TENANT_ID, 1L)).willReturn(List.of());
        given(driveSessionQueryService.getTenantHistory(any(), any(), any())).willReturn(List.of());
        given(studentGuardianRepository.findWithGuardianByStudentIdIn(any())).willReturn(List.of());

        BusOperationDetail detail = service.getBusOperationDetail(admin(), 1L);

        assertThat(detail.session().status()).isEqualTo(BusSessionStatus.NOT_STARTED);
        assertThat(detail.students()).extracting(BusOperationDetail.StudentStatus::status)
                .containsOnly(BoardingStatus.WAITING);
    }

    @Test
    void getBusOperationDetail_routePlanNull_whenNoPublishedPlanToday() {
        Bus bus = bus(1L, null);
        given(busRepository.findById(1L)).willReturn(Optional.of(bus));
        given(studentRepository.findByAssignedBusIdAndActiveTrue(1L)).willReturn(List.of());
        given(rideEventRepository.findByStudentIdInAndOccurredAtBetweenOrderByOccurredAtAsc(any(), any(), any()))
                .willReturn(List.of());
        given(attendanceExceptionRepository.findByTenantIdOrderByTargetDateDesc(TENANT_ID)).willReturn(List.of());
        given(routePlanRepository.findByTenantIdAndBusIdOrderByCreatedAtDesc(TENANT_ID, 1L)).willReturn(List.of());
        given(driveSessionQueryService.getTenantHistory(any(), any(), any())).willReturn(List.of());

        BusOperationDetail detail = service.getBusOperationDetail(admin(), 1L);

        // 계획이 없어도 200 으로 응답한다 — 버스·학생 명단은 계획과 무관하게 유효한 정보다.
        assertThat(detail.routePlan()).isNull();
        assertThat(detail.students()).isEmpty();
    }

    @Test
    void getBusOperationDetail_includesStopNameAndSeq_fromPublishedPlan() {
        Bus bus = bus(1L, null);
        Route route = Route.builder().tenant(tenant()).name("1노선").assignCapacity(20).build();
        Stop stopEntity = Stop.builder().route(route).name("행복아파트").seq(1).lat(37.1).lng(127.1).build();
        Student student = Student.builder().tenant(tenant()).name("학생1").assignedBus(bus)
                .boardingStop(stopEntity).build();
        ReflectionTestUtils.setField(student, "id", 1L);

        given(busRepository.findById(1L)).willReturn(Optional.of(bus));
        given(studentRepository.findByAssignedBusIdAndActiveTrue(1L)).willReturn(List.of(student));
        given(rideEventRepository.findByStudentIdInAndOccurredAtBetweenOrderByOccurredAtAsc(any(), any(), any()))
                .willReturn(List.of());
        given(attendanceExceptionRepository.findByTenantIdOrderByTargetDateDesc(TENANT_ID)).willReturn(List.of());
        RoutePlan plan = routePlan(10L, 1L, RouteDirection.PICKUP, RoutePlanStatus.PUBLISHED, 1);
        plan.addStop(1L, 37.1, 127.1, 300L);
        given(routePlanRepository.findByTenantIdAndBusIdOrderByCreatedAtDesc(TENANT_ID, 1L)).willReturn(List.of(plan));
        given(driveSessionQueryService.getTenantHistory(any(), any(), any())).willReturn(List.of());
        given(studentGuardianRepository.findWithGuardianByStudentIdIn(any())).willReturn(List.of());

        BusOperationDetail detail = service.getBusOperationDetail(admin(), 1L);

        assertThat(detail.routePlan()).isNotNull();
        assertThat(detail.routePlan().stops()).hasSize(1);
        assertThat(detail.routePlan().stops().get(0).name()).isEqualTo("행복아파트");
        assertThat(detail.routePlan().stops().get(0).seq()).isEqualTo(1);
        assertThat(detail.students().get(0).stopName()).isEqualTo("행복아파트");
        assertThat(detail.students().get(0).stopSeq()).isEqualTo(1);
    }

    @Test
    void getBusOperationDetail_includesGuardianContacts() {
        Bus bus = bus(1L, null);
        Student student = student(1L, bus);
        User guardianUser = User.builder()
                .email("mom@school.com").name("김엄마").password("x").phone("010-1234-5678").build();
        ReflectionTestUtils.setField(guardianUser, "id", 50L);
        StudentGuardian link = StudentGuardian.builder().student(student).guardian(guardianUser).relation("모").build();

        given(busRepository.findById(1L)).willReturn(Optional.of(bus));
        given(studentRepository.findByAssignedBusIdAndActiveTrue(1L)).willReturn(List.of(student));
        given(rideEventRepository.findByStudentIdInAndOccurredAtBetweenOrderByOccurredAtAsc(any(), any(), any()))
                .willReturn(List.of());
        given(attendanceExceptionRepository.findByTenantIdOrderByTargetDateDesc(TENANT_ID)).willReturn(List.of());
        given(routePlanRepository.findByTenantIdAndBusIdOrderByCreatedAtDesc(TENANT_ID, 1L)).willReturn(List.of());
        given(driveSessionQueryService.getTenantHistory(any(), any(), any())).willReturn(List.of());
        given(studentGuardianRepository.findWithGuardianByStudentIdIn(List.of(1L))).willReturn(List.of(link));

        BusOperationDetail detail = service.getBusOperationDetail(admin(), 1L);

        assertThat(detail.students().get(0).guardians()).hasSize(1);
        BusOperationDetail.GuardianView guardian = detail.students().get(0).guardians().get(0);
        assertThat(guardian.name()).isEqualTo("김엄마");
        assertThat(guardian.phone()).isEqualTo("010-1234-5678");
        assertThat(guardian.relation()).isEqualTo("모");
    }

    @Test
    void getBusOperationDetail_stopNameNull_whenPlanHasNoStopForStudent() {
        Bus bus = bus(1L, null);
        Route route = Route.builder().tenant(tenant()).name("1노선").assignCapacity(20).build();
        Stop stopEntity = Stop.builder().route(route).name("행복아파트").seq(1).lat(37.1).lng(127.1).build();
        Student student = Student.builder().tenant(tenant()).name("학생1").assignedBus(bus)
                .boardingStop(stopEntity).build();
        ReflectionTestUtils.setField(student, "id", 1L);

        given(busRepository.findById(1L)).willReturn(Optional.of(bus));
        given(studentRepository.findByAssignedBusIdAndActiveTrue(1L)).willReturn(List.of(student));
        given(rideEventRepository.findByStudentIdInAndOccurredAtBetweenOrderByOccurredAtAsc(any(), any(), any()))
                .willReturn(List.of());
        given(attendanceExceptionRepository.findByTenantIdOrderByTargetDateDesc(TENANT_ID)).willReturn(List.of());
        // 계획은 없지만 세션은 진행 중 — 방향(PICKUP)은 세션에서 확정되므로 정차 이름을 구할 근거는 생긴다.
        given(routePlanRepository.findByTenantIdAndBusIdOrderByCreatedAtDesc(TENANT_ID, 1L)).willReturn(List.of());
        given(driveSessionQueryService.getTenantHistory(any(), any(), any()))
                .willReturn(List.of(driveSession(1L, DriveSessionStatus.IN_PROGRESS, LocalDateTime.now().minusMinutes(5))));
        given(studentGuardianRepository.findWithGuardianByStudentIdIn(any())).willReturn(List.of());

        BusOperationDetail detail = service.getBusOperationDetail(admin(), 1L);

        // 계획에 이 학생의 정차가 없으면 순서와 이름을 함께 비운다. 이름만 채우면 화면에
        // "정차 순서는 빈칸인데 이름은 있는" 행이 생겨 DTO 계약(둘 다 null)과 어긋난다.
        assertThat(detail.students().get(0).stopSeq()).isNull();
        assertThat(detail.students().get(0).stopName()).isNull();
    }

    @Test
    void getBusOperationDetail_countsEventsRecordedOnPreviousBus_afterReassignment() {
        Bus currentBus = bus(2L, null);
        Student student = student(1L, currentBus);
        // 오전에 1호차로 기록된 승차 — 낮에 2호차로 재배정돼 명단은 2호차에 있다.
        RideEvent boardedOnOldBus = rideEvent(1L, 1L, RideType.BOARD);

        given(busRepository.findById(2L)).willReturn(Optional.of(currentBus));
        given(studentRepository.findByAssignedBusIdAndActiveTrue(2L)).willReturn(List.of(student));
        given(rideEventRepository.findByStudentIdInAndOccurredAtBetweenOrderByOccurredAtAsc(
                List.of(1L), LocalDate.now().atStartOfDay(), LocalDate.now().plusDays(1).atStartOfDay()))
                .willReturn(List.of(boardedOnOldBus));
        given(attendanceExceptionRepository.findByTenantIdOrderByTargetDateDesc(TENANT_ID)).willReturn(List.of());
        given(routePlanRepository.findByTenantIdAndBusIdOrderByCreatedAtDesc(TENANT_ID, 2L)).willReturn(List.of());
        given(driveSessionQueryService.getTenantHistory(any(), any(), any())).willReturn(List.of());
        given(studentGuardianRepository.findWithGuardianByStudentIdIn(any())).willReturn(List.of());

        BusOperationDetail detail = service.getBusOperationDetail(admin(), 2L);

        // 목록 API 는 학원 전체를 studentId 로 접어 이 기록을 BOARDED 로 반영한다. 상세가 busId 로
        // 거르면 같은 학생이 목록에선 BOARDED, 상세에선 WAITING 으로 갈린다.
        assertThat(detail.students().get(0).status()).isEqualTo(BoardingStatus.BOARDED);
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
