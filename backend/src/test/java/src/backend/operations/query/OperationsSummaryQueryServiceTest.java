package src.backend.operations.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.attendance.entity.AttendanceException;
import src.backend.attendance.entity.AttendanceType;
import src.backend.attendance.repository.spec.AttendanceExceptionRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.drivesession.entity.DriveSession;
import src.backend.drivesession.entity.DriveSessionStatus;
import src.backend.drivesession.repository.spec.DriveSessionRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.location.dto.BusLocationPing;
import src.backend.location.dto.LocationOrigin;
import src.backend.location.repository.spec.BusLocationRepository;
import src.backend.notification.domain.NotificationLog;
import src.backend.notification.domain.NotificationType;
import src.backend.notification.repository.spec.NotificationLogRepository;
import src.backend.operations.dto.OperationsSummary;
import src.backend.route.entity.Route;
import src.backend.route.entity.Stop;
import src.backend.routing.domain.RouteDirection;
import src.backend.routing.domain.RoutePlanStatus;
import src.backend.routing.entity.RoutePlan;
import src.backend.routing.repository.spec.RoutePlanRepository;
import src.backend.schedule.entity.ScheduleChangeRequest;
import src.backend.schedule.repository.spec.ScheduleChangeRequestRepository;
import src.backend.sos.entity.SosEvent;
import src.backend.sos.repository.spec.SosEventRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.spec.StudentRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.user.entity.Role;
import src.backend.user.entity.User;

/**
 * 운영 요약(BG-22) 단위 테스트. 특히 팀 지시대로 세 가지를 우선 고정한다:
 * 보험 만료/임박이 실제로 갈리는지, absent 가 승인된 결석만 세는지, 선탑자 미배정이
 * 기사 미배정과 구분되는지. 나머지 항목(오늘 현황·대기·배차 이상·알림·이상 징후)도
 * 각 최소 1개씩 덮는다.
 */
class OperationsSummaryQueryServiceTest {

    private static final Long TENANT_ID = 1L;

    private final BusRepository busRepository = mock(BusRepository.class);
    private final StudentRepository studentRepository = mock(StudentRepository.class);
    private final DriveSessionRepository driveSessionRepository = mock(DriveSessionRepository.class);
    private final SosEventRepository sosEventRepository = mock(SosEventRepository.class);
    private final AttendanceExceptionRepository attendanceExceptionRepository = mock(AttendanceExceptionRepository.class);
    private final ScheduleChangeRequestRepository scheduleChangeRequestRepository = mock(ScheduleChangeRequestRepository.class);
    private final RoutePlanRepository routePlanRepository = mock(RoutePlanRepository.class);
    private final NotificationLogRepository notificationLogRepository = mock(NotificationLogRepository.class);
    private final BusLocationRepository busLocationRepository = mock(BusLocationRepository.class);

    private final OperationsSummaryQueryService service = new OperationsSummaryQueryService(
            busRepository, studentRepository, driveSessionRepository, sosEventRepository,
            attendanceExceptionRepository, scheduleChangeRequestRepository, routePlanRepository,
            notificationLogRepository, busLocationRepository);

    // ── compliance: 만료 vs 임박 ──

    @Test
    void compliance_expiredAndExpiringSoon_areClassifiedSeparately() {
        Bus expired = bus(1L, null, LocalDate.now().minusDays(6));       // 실측 시나리오와 같은 이미 만료
        Bus expiringSoon = bus(2L, null, LocalDate.now().plusDays(10));  // 30일 이내
        Bus farAway = bus(3L, null, LocalDate.now().plusDays(90));       // 임박 아님
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(expired, expiringSoon, farAway));

        OperationsSummary summary = service.getSummary(admin(), TENANT_ID);

        assertThat(summary.compliance().insuranceExpired()).extracting(OperationsSummary.Compliance.InsuranceBus::busId)
                .containsExactly(1L);
        assertThat(summary.compliance().insuranceExpiring()).extracting(OperationsSummary.Compliance.InsuranceBus::busId)
                .containsExactly(2L);
    }

    /**
     * 만료 당일(daysRemaining == 0) 경계 — 보험은 만료일 당일까지 유효하므로 expired 가 아니라
     * expiring 이어야 한다(그날 운행은 적법, 다음 날부터 무보험). {@code isBefore(today)}를
     * {@code isEqual} 이하로 바꾸면 이 테스트가 깨진다.
     */
    @Test
    void compliance_expiryIsToday_classifiedAsExpiringNotExpired() {
        Bus expiresToday = bus(1L, null, LocalDate.now());
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(expiresToday));

        OperationsSummary summary = service.getSummary(admin(), TENANT_ID);

        assertThat(summary.compliance().insuranceExpired()).isEmpty();
        assertThat(summary.compliance().insuranceExpiring())
                .extracting(OperationsSummary.Compliance.InsuranceBus::busId).containsExactly(1L);
    }

    /**
     * 임박 경계 안쪽(daysRemaining == 30) — 여전히 expiring 이어야 한다. {@code <= 30}을
     * {@code < 30}으로 바꾸면 이 테스트가 깨진다.
     */
    @Test
    void compliance_daysRemaining30_stillClassifiedAsExpiring() {
        Bus atBoundary = bus(1L, null, LocalDate.now().plusDays(30));
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(atBoundary));

        OperationsSummary summary = service.getSummary(admin(), TENANT_ID);

        assertThat(summary.compliance().insuranceExpired()).isEmpty();
        assertThat(summary.compliance().insuranceExpiring())
                .extracting(OperationsSummary.Compliance.InsuranceBus::busId).containsExactly(1L);
    }

    /**
     * 임박 경계 밖(daysRemaining == 31) — 어느 목록에도 들어가지 않아야 한다. 이 케이스가 없으면
     * 임박 판정의 상한이 사라져도(모든 미래 날짜를 임박으로 잡아도) 테스트가 통과해버린다.
     */
    @Test
    void compliance_daysRemaining31_excludedFromBothLists() {
        Bus justOutside = bus(1L, null, LocalDate.now().plusDays(31));
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(justOutside));

        OperationsSummary summary = service.getSummary(admin(), TENANT_ID);

        assertThat(summary.compliance().insuranceExpired()).isEmpty();
        assertThat(summary.compliance().insuranceExpiring()).isEmpty();
    }

    // ── attendance: absent 는 당일 승인된 결석만 ──

    @Test
    void attendance_absent_countsOnlyTodayApprovedExceptions() {
        Bus bus = bus(1L, null, null);
        Student assigned1 = student(1L, bus);
        Student assigned2 = student(2L, bus);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        given(studentRepository.findByTenantIdAndActiveTrue(TENANT_ID)).willReturn(List.of(assigned1, assigned2));

        AttendanceException approvedToday = attendanceException(1L, LocalDate.now());
        approvedToday.approve(9L);
        AttendanceException pendingToday = attendanceException(2L, LocalDate.now()); // 미승인 — absent 아님
        AttendanceException approvedYesterday = attendanceException(2L, LocalDate.now().minusDays(1));
        approvedYesterday.approve(9L); // 승인이어도 오늘이 아니면 absent 아님
        given(attendanceExceptionRepository.findByTenantIdOrderByTargetDateDesc(TENANT_ID))
                .willReturn(List.of(approvedToday, pendingToday, approvedYesterday));

        OperationsSummary summary = service.getSummary(admin(), TENANT_ID);

        assertThat(summary.attendance().absent()).isEqualTo(1);
        assertThat(summary.attendance().expected()).isEqualTo(1); // 배정 2명 - absent 1명
        assertThat(summary.pending().attendancePending()).isEqualTo(1); // 날짜 무관, PENDING 전부
    }

    /**
     * 버스 미배정 학생의 결석 신고는 absent 에도, expected 계산에도 영향을 주면 안 된다 — 배정이
     * 없으면 애초에 어느 회차에도 속하지 않아 "탑승 예정에서 뺄 대상"이 아니기 때문이다.
     */
    @Test
    void attendance_unassignedStudentAbsence_excludedFromAbsentAndExpected() {
        Bus bus = bus(1L, null, null);
        Student assigned = student(1L, bus);
        Student unassigned = student(2L, null); // 버스 미배정
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        given(studentRepository.findByTenantIdAndActiveTrue(TENANT_ID)).willReturn(List.of(assigned, unassigned));

        AttendanceException unassignedApprovedToday = attendanceException(2L, LocalDate.now());
        unassignedApprovedToday.approve(9L);
        given(attendanceExceptionRepository.findByTenantIdOrderByTargetDateDesc(TENANT_ID))
                .willReturn(List.of(unassignedApprovedToday));

        OperationsSummary summary = service.getSummary(admin(), TENANT_ID);

        assertThat(summary.attendance().absent()).isEqualTo(0);
        assertThat(summary.attendance().expected()).isEqualTo(1); // 배정 1명, 결석 반영 대상 없음
    }

    // ── dispatch.busesWithoutCrew: 기사 vs 선탑자 구분 ──

    @Test
    void busesWithoutCrew_distinguishesDriverFromAttendant() {
        User driver = user(10L);
        User attendant = user(20L);
        Bus missingAttendantOnly = busWithCrew(1L, driver, null);
        Bus missingDriverOnly = busWithCrew(2L, null, attendant);
        Bus missingBoth = busWithCrew(3L, null, null);
        Bus fullyStaffed = busWithCrew(4L, driver, attendant);
        given(busRepository.findByTenantId(TENANT_ID))
                .willReturn(List.of(missingAttendantOnly, missingDriverOnly, missingBoth, fullyStaffed));

        OperationsSummary summary = service.getSummary(admin(), TENANT_ID);

        List<OperationsSummary.Dispatch.BusWithoutCrew> flagged = summary.dispatch().busesWithoutCrew();
        assertThat(flagged).hasSize(3);
        assertThat(flagged).filteredOn(f -> f.busId().equals(1L))
                .allMatch(f -> !f.missingDriver() && f.missingAttendant());
        assertThat(flagged).filteredOn(f -> f.busId().equals(2L))
                .allMatch(f -> f.missingDriver() && !f.missingAttendant());
        assertThat(flagged).filteredOn(f -> f.busId().equals(3L))
                .allMatch(f -> f.missingDriver() && f.missingAttendant());
        assertThat(flagged).noneMatch(f -> f.busId().equals(4L));
    }

    // ── dispatch.overCapacityBuses ──

    @Test
    void dispatch_overCapacity_flaggedWhenOnboardExceedsAssignCapacity() {
        Route route = route(2); // 정원 2명
        Bus bus = bus(1L, route, null);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        given(studentRepository.findByAssignedBusIdInAndActiveTrue(any()))
                .willReturn(List.of(student(1L, bus), student(2L, bus), student(3L, bus)));

        OperationsSummary summary = service.getSummary(admin(), TENANT_ID);

        assertThat(summary.dispatch().overCapacityBuses()).hasSize(1);
        assertThat(summary.dispatch().overCapacityBuses().get(0).onboard()).isEqualTo(3);
        assertThat(summary.dispatch().overCapacityBuses().get(0).assignCapacity()).isEqualTo(2);
    }

    // ── dispatch.studentsWithoutCoords ──

    @Test
    void dispatch_studentsWithoutCoords_flagsMissingPickupOrDropoff() {
        Bus bus = bus(1L, null, null);
        Student noCoordsAtAll = student(1L, bus); // pickup·dropoff 전부 미설정
        Student hasStopButNoDropoff = student(2L, bus);
        hasStopButNoDropoff.assignStop(Stop.builder().name("정문").seq(1).lat(37.0).lng(127.0).build());
        Student fullySet = student(3L, bus);
        fullySet.updatePickup("등원지", 37.1, 127.1);
        fullySet.updateDropoff("하원지", 37.2, 127.2);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        given(studentRepository.findByTenantIdAndActiveTrue(TENANT_ID))
                .willReturn(List.of(noCoordsAtAll, hasStopButNoDropoff, fullySet));

        OperationsSummary summary = service.getSummary(admin(), TENANT_ID);

        List<OperationsSummary.Dispatch.StudentWithoutCoords> flagged = summary.dispatch().studentsWithoutCoords();
        assertThat(flagged).extracting(OperationsSummary.Dispatch.StudentWithoutCoords::studentId)
                .containsExactlyInAnyOrder(1L, 2L);
        assertThat(flagged).filteredOn(f -> f.studentId().equals(1L))
                .allMatch(f -> f.missingPickup() && f.missingDropoff());
        assertThat(flagged).filteredOn(f -> f.studentId().equals(2L))
                .allMatch(f -> !f.missingPickup() && f.missingDropoff());
    }

    // ── dispatch.unpublishedPlans: 최신 version 만 판정 ──

    @Test
    void dispatch_unpublishedPlans_onlyLatestVersionMatters() {
        Bus bus = bus(1L, null, null);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        RoutePlan v1Published = routePlan(1L, bus.getId(), RouteDirection.PICKUP, RoutePlanStatus.PUBLISHED, 1);
        RoutePlan v2Recalculated = routePlan(2L, bus.getId(), RouteDirection.PICKUP, RoutePlanStatus.RECOMMENDED, 2);
        given(routePlanRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID))
                .willReturn(List.of(v2Recalculated, v1Published));

        OperationsSummary summary = service.getSummary(admin(), TENANT_ID);

        assertThat(summary.dispatch().unpublishedPlans()).hasSize(1);
        assertThat(summary.dispatch().unpublishedPlans().get(0).routePlanId()).isEqualTo(2L);
        assertThat(summary.dispatch().unpublishedPlans().get(0).version()).isEqualTo(2);
    }

    @Test
    void dispatch_unpublishedPlans_excludedWhenLatestVersionIsPublished() {
        Bus bus = bus(1L, null, null);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        RoutePlan v1Draft = routePlan(1L, bus.getId(), RouteDirection.PICKUP, RoutePlanStatus.DRAFT, 1);
        RoutePlan v2Published = routePlan(2L, bus.getId(), RouteDirection.PICKUP, RoutePlanStatus.PUBLISHED, 2);
        given(routePlanRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID))
                .willReturn(List.of(v2Published, v1Draft));

        OperationsSummary summary = service.getSummary(admin(), TENANT_ID);

        assertThat(summary.dispatch().unpublishedPlans()).isEmpty();
    }

    // ── pending ──

    @Test
    void pending_countsOnlyOpenSosAndPendingRequests() {
        SosEvent open = sosEvent(1L);
        SosEvent acknowledged = sosEvent(2L);
        acknowledged.acknowledge(9L);
        given(sosEventRepository.findByTenantIdOrderByOccurredAtDesc(TENANT_ID)).willReturn(List.of(open, acknowledged));

        ScheduleChangeRequest pending = scheduleChangeRequest(1L);
        ScheduleChangeRequest approved = scheduleChangeRequest(2L);
        approved.approve(9L);
        given(scheduleChangeRequestRepository.findByTenantIdOrderByRequestedDateDesc(TENANT_ID))
                .willReturn(List.of(pending, approved));

        OperationsSummary summary = service.getSummary(admin(), TENANT_ID);

        assertThat(summary.pending().sosOpen()).isEqualTo(1);
        assertThat(summary.pending().scheduleChangePending()).isEqualTo(1);
    }

    // ── today: 방향별 breakdown ──

    @Test
    void today_classifiesBusesByRunningCompletedNotStarted() {
        Bus running = bus(1L, null, null);
        Bus completed = bus(2L, null, null);
        Bus notStarted = bus(3L, null, null);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(running, completed, notStarted));

        DriveSession inProgress = session(1L, 1L, RouteDirection.PICKUP, DriveSessionStatus.IN_PROGRESS,
                LocalDateTime.now().minusMinutes(10), null);
        DriveSession done = session(2L, 2L, RouteDirection.DROPOFF, DriveSessionStatus.COMPLETED,
                LocalDateTime.now().minusHours(1), null);
        given(driveSessionRepository.findByTenantIdOrderByStartedAtDesc(TENANT_ID)).willReturn(List.of(inProgress, done));

        OperationsSummary summary = service.getSummary(admin(), TENANT_ID);

        assertThat(summary.today().busesTotal()).isEqualTo(3);
        assertThat(summary.today().running()).isEqualTo(1);
        assertThat(summary.today().completed()).isEqualTo(1);
        assertThat(summary.today().notStarted()).isEqualTo(1);
        assertThat(summary.today().byDirection().get(RouteDirection.PICKUP).running()).isEqualTo(1);
        assertThat(summary.today().byDirection().get(RouteDirection.DROPOFF).completed()).isEqualTo(1);
    }

    // ── notifications: 당일분만 ──

    @Test
    void notifications_today_excludesOlderEntries() {
        NotificationLog today = notificationLog(1L, 1L, LocalDateTime.now());
        NotificationLog yesterday = notificationLog(2L, 2L, LocalDateTime.now().minusDays(1));
        given(notificationLogRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID))
                .willReturn(List.of(today, yesterday));

        OperationsSummary summary = service.getSummary(admin(), TENANT_ID);

        assertThat(summary.notifications().today()).extracting(n -> n.id()).containsExactly(1L);
    }

    // ── anomalies ──

    @Test
    void anomalies_staleLocation_flaggedWhenNoRecentPing() {
        Bus bus = bus(1L, null, null);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        DriveSession inProgress = session(1L, 1L, RouteDirection.PICKUP, DriveSessionStatus.IN_PROGRESS,
                LocalDateTime.now().minusMinutes(20), null);
        given(driveSessionRepository.findByTenantIdOrderByStartedAtDesc(TENANT_ID)).willReturn(List.of(inProgress));
        given(busLocationRepository.findLatest(1L)).willReturn(Optional.empty());

        OperationsSummary summary = service.getSummary(admin(), TENANT_ID);

        assertThat(summary.anomalies().staleLocationBuses()).extracting(OperationsSummary.Anomalies.StaleLocationBus::busId)
                .containsExactly(1L);
    }

    @Test
    void anomalies_freshLocation_notFlaggedStale() {
        Bus bus = bus(1L, null, null);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        DriveSession inProgress = session(1L, 1L, RouteDirection.PICKUP, DriveSessionStatus.IN_PROGRESS,
                LocalDateTime.now().minusMinutes(5), null);
        given(driveSessionRepository.findByTenantIdOrderByStartedAtDesc(TENANT_ID)).willReturn(List.of(inProgress));
        given(busLocationRepository.findLatest(1L)).willReturn(Optional.of(
                new BusLocationPing(1L, TENANT_ID, 37.5, 127.0, LocalDateTime.now(), LocationOrigin.GPS)));

        OperationsSummary summary = service.getSummary(admin(), TENANT_ID);

        assertThat(summary.anomalies().staleLocationBuses()).isEmpty();
    }

    @Test
    void anomalies_longRunningSession_flaggedPastThreshold() {
        Bus bus = bus(1L, null, null);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        DriveSession tooLong = session(1L, 1L, RouteDirection.PICKUP, DriveSessionStatus.IN_PROGRESS,
                LocalDateTime.now().minusHours(4), null); // 임계값(3시간) 초과
        given(driveSessionRepository.findByTenantIdOrderByStartedAtDesc(TENANT_ID)).willReturn(List.of(tooLong));

        OperationsSummary summary = service.getSummary(admin(), TENANT_ID);

        assertThat(summary.anomalies().longRunningSessions()).extracting(
                OperationsSummary.Anomalies.LongRunningSession::sessionId).containsExactly(1L);
    }

    @Test
    void anomalies_normalSession_notFlaggedLongRunning() {
        Bus bus = bus(1L, null, null);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        DriveSession normal = session(1L, 1L, RouteDirection.PICKUP, DriveSessionStatus.IN_PROGRESS,
                LocalDateTime.now().minusMinutes(30), null);
        given(driveSessionRepository.findByTenantIdOrderByStartedAtDesc(TENANT_ID)).willReturn(List.of(normal));

        OperationsSummary summary = service.getSummary(admin(), TENANT_ID);

        assertThat(summary.anomalies().longRunningSessions()).isEmpty();
    }

    /** ⚠ 추정치 — 계획 총 소요시간(totalDurationS) 대비 경과 시간 비교. */
    @Test
    void anomalies_delayedBus_estimatedFromPlanDuration() {
        Bus bus = bus(1L, null, null);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        DriveSession delayed = session(1L, 1L, RouteDirection.PICKUP, DriveSessionStatus.IN_PROGRESS,
                LocalDateTime.now().minusHours(2), 100L); // 2시간(7200초) 경과
        given(driveSessionRepository.findByTenantIdOrderByStartedAtDesc(TENANT_ID)).willReturn(List.of(delayed));
        RoutePlan plan = routePlan(100L, 1L, RouteDirection.PICKUP, RoutePlanStatus.PUBLISHED, 1);
        ReflectionTestUtils.setField(plan, "totalDurationS", 1800.0); // 계획은 30분(1800초)
        given(routePlanRepository.findAllById(List.of(100L))).willReturn(List.of(plan));

        OperationsSummary summary = service.getSummary(admin(), TENANT_ID);

        assertThat(summary.anomalies().delayedBuses()).hasSize(1);
        assertThat(summary.anomalies().delayedBuses().get(0).delaySecondsEstimate()).isEqualTo(7200L - 1800L);
    }

    @Test
    void anomalies_withinPlanDuration_notFlaggedDelayed() {
        Bus bus = bus(1L, null, null);
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));
        DriveSession onTime = session(1L, 1L, RouteDirection.PICKUP, DriveSessionStatus.IN_PROGRESS,
                LocalDateTime.now().minusMinutes(10), 100L);
        given(driveSessionRepository.findByTenantIdOrderByStartedAtDesc(TENANT_ID)).willReturn(List.of(onTime));
        RoutePlan plan = routePlan(100L, 1L, RouteDirection.PICKUP, RoutePlanStatus.PUBLISHED, 1);
        ReflectionTestUtils.setField(plan, "totalDurationS", 1800.0);
        given(routePlanRepository.findAllById(List.of(100L))).willReturn(List.of(plan));

        OperationsSummary summary = service.getSummary(admin(), TENANT_ID);

        assertThat(summary.anomalies().delayedBuses()).isEmpty();
    }

    // ── TenantGuard ──

    @Test
    void getSummary_otherTenantAdmin_throwsForbidden() {
        AuthUser otherAdmin = new AuthUser(100L, "admin@school.com",
                List.of(new AuthUser.Membership(999L, Role.ACADEMY_ADMIN)));

        assertThatThrownBy(() -> service.getSummary(otherAdmin, TENANT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ── helpers ──

    private AuthUser admin() {
        return new AuthUser(100L, "admin@school.com", List.of(new AuthUser.Membership(TENANT_ID, Role.ACADEMY_ADMIN)));
    }

    private Bus bus(Long id, Route route, LocalDate insuranceExpiry) {
        Bus bus = Bus.builder().tenant(tenant()).name(id + "호차").seatCapacity(25)
                .route(route).insuranceExpiry(insuranceExpiry).build();
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

    private Route route(int assignCapacity) {
        Route route = Route.builder().tenant(tenant()).name("A노선").assignCapacity(assignCapacity).build();
        ReflectionTestUtils.setField(route, "id", 10L);
        return route;
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

    private DriveSession session(Long id, Long busId, RouteDirection direction, DriveSessionStatus status,
                                 LocalDateTime startedAt, Long routePlanId) {
        DriveSession session = DriveSession.builder()
                .tenantId(TENANT_ID).busId(busId).driverId(1L).direction(direction)
                .serviceDate(LocalDate.now()).routePlanId(routePlanId).build();
        ReflectionTestUtils.setField(session, "id", id);
        ReflectionTestUtils.setField(session, "status", status);
        ReflectionTestUtils.setField(session, "startedAt", startedAt);
        return session;
    }

    private RoutePlan routePlan(Long id, Long busId, RouteDirection direction, RoutePlanStatus status, int version) {
        RoutePlan plan = RoutePlan.builder()
                .tenantId(TENANT_ID).busId(busId).direction(direction).status(status).version(version)
                .serviceDate(LocalDate.now()).polyline("[]").totalDistanceM(1000).totalDurationS(600).build();
        ReflectionTestUtils.setField(plan, "id", id);
        return plan;
    }

    private AttendanceException attendanceException(Long studentId, LocalDate targetDate) {
        return AttendanceException.builder()
                .tenantId(TENANT_ID).studentId(studentId).type(AttendanceType.ABSENCE)
                .targetDate(targetDate).reason("사유").build();
    }

    private ScheduleChangeRequest scheduleChangeRequest(Long studentId) {
        return ScheduleChangeRequest.builder()
                .tenantId(TENANT_ID).studentId(studentId).requestedDate(LocalDate.now())
                .requestedTime(LocalTime.of(8, 0)).reason("사유").build();
    }

    private SosEvent sosEvent(Long studentId) {
        return SosEvent.builder()
                .tenantId(TENANT_ID).studentId(studentId).lat(37.0).lng(127.0)
                .occurredAt(LocalDateTime.now()).build();
    }

    private NotificationLog notificationLog(Long id, Long studentId, LocalDateTime createdAt) {
        NotificationLog log = NotificationLog.builder()
                .tenantId(TENANT_ID).studentId(studentId).type(NotificationType.BOARD_DONE)
                .dedupKey("key-" + studentId + "-" + createdAt).message("메시지").build();
        ReflectionTestUtils.setField(log, "id", id);
        ReflectionTestUtils.setField(log, "createdAt", createdAt);
        return log;
    }
}
