package src.backend.drivesession.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.drivesession.dto.DriveSessionResponse;
import src.backend.drivesession.dto.StartDriveSessionRequest;
import src.backend.drivesession.entity.DriveSession;
import src.backend.drivesession.entity.DriveSessionStatus;
import src.backend.drivesession.event.ApproachEvent;
import src.backend.drivesession.event.NoShowEvent;
import src.backend.drivesession.repository.spec.DriveSessionRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
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
 * 운행 세션 시작/종료(권한가드·중복처리 409) + 차내 잔류 방지(G2)·근접/미승차 판정(G1)을 검증한다(G5).
 * curl E2E로만 검증돼있던 G1/G2 로직에 처음으로 자동 테스트를 추가한다.
 */
class DriveSessionCommandServiceTest {

    private final DriveSessionRepository driveSessionRepository = mock(DriveSessionRepository.class);
    private final BusRepository busRepository = mock(BusRepository.class);
    private final RoutePlanRepository routePlanRepository = mock(RoutePlanRepository.class);
    private final RideEventRepository rideEventRepository = mock(RideEventRepository.class);
    private final StudentRepository studentRepository = mock(StudentRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private final DriveSessionCommandService service = new DriveSessionCommandService(
            driveSessionRepository, busRepository, routePlanRepository, rideEventRepository,
            studentRepository, eventPublisher);

    private static final Long TENANT_ID = 1L;
    private static final Long BUS_ID = 1L;
    private static final Long DRIVER_ID = 500L;
    private static final Long STUDENT_ID = 10L;

    // ── start() ──

    @Test
    void start_noConflict_createsSessionWithoutRoutePlan() {
        AuthUser driver = authUser(DRIVER_ID, TENANT_ID, Role.DRIVER);
        Bus bus = bus(BUS_ID, TENANT_ID, DRIVER_ID);
        given(busRepository.findById(BUS_ID)).willReturn(Optional.of(bus));
        given(driveSessionRepository.findByBusIdAndDirectionAndServiceDateAndStatus(
                eq(BUS_ID), eq(RouteDirection.PICKUP), any(LocalDate.class), eq(DriveSessionStatus.IN_PROGRESS)))
                .willReturn(Optional.empty());
        given(routePlanRepository.findByBusIdAndServiceDateAndStatusOrderByDirectionAsc(
                eq(BUS_ID), any(LocalDate.class), eq(RoutePlanStatus.PUBLISHED)))
                .willReturn(List.of());
        given(driveSessionRepository.save(any(DriveSession.class))).willAnswer(inv -> {
            DriveSession s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", 1L);
            return s;
        });

        DriveSessionResponse response = service.start(driver, new StartDriveSessionRequest(BUS_ID, RouteDirection.PICKUP, null));

        assertThat(response.status()).isEqualTo(DriveSessionStatus.IN_PROGRESS);
        assertThat(response.routePlanId()).isNull();
    }

    @Test
    void start_publishedPlanForDirection_linksRoutePlanId() {
        AuthUser driver = authUser(DRIVER_ID, TENANT_ID, Role.DRIVER);
        Bus bus = bus(BUS_ID, TENANT_ID, DRIVER_ID);
        given(busRepository.findById(BUS_ID)).willReturn(Optional.of(bus));
        given(driveSessionRepository.findByBusIdAndDirectionAndServiceDateAndStatus(
                eq(BUS_ID), eq(RouteDirection.PICKUP), any(LocalDate.class), eq(DriveSessionStatus.IN_PROGRESS)))
                .willReturn(Optional.empty());
        RoutePlan plan = publishedPlan(7L, BUS_ID, RouteDirection.PICKUP);
        given(routePlanRepository.findByBusIdAndServiceDateAndStatusOrderByDirectionAsc(
                eq(BUS_ID), any(LocalDate.class), eq(RoutePlanStatus.PUBLISHED)))
                .willReturn(List.of(plan));
        given(driveSessionRepository.save(any(DriveSession.class))).willAnswer(inv -> {
            DriveSession s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", 1L);
            return s;
        });

        DriveSessionResponse response = service.start(driver, new StartDriveSessionRequest(BUS_ID, RouteDirection.PICKUP, null));

        assertThat(response.routePlanId()).isEqualTo(7L);
    }

    @Test
    void start_notAssignedDriver_throwsForbidden() {
        AuthUser otherDriver = authUser(999L, TENANT_ID, Role.DRIVER);
        Bus bus = bus(BUS_ID, TENANT_ID, DRIVER_ID); // 담당 기사는 500L
        given(busRepository.findById(BUS_ID)).willReturn(Optional.of(bus));

        assertThatThrownBy(() -> service.start(otherDriver, new StartDriveSessionRequest(BUS_ID, RouteDirection.PICKUP, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void start_alreadyInProgress_throwsConflict() {
        AuthUser driver = authUser(DRIVER_ID, TENANT_ID, Role.DRIVER);
        Bus bus = bus(BUS_ID, TENANT_ID, DRIVER_ID);
        given(busRepository.findById(BUS_ID)).willReturn(Optional.of(bus));
        given(driveSessionRepository.findByBusIdAndDirectionAndServiceDateAndStatus(
                eq(BUS_ID), eq(RouteDirection.PICKUP), any(LocalDate.class), eq(DriveSessionStatus.IN_PROGRESS)))
                .willReturn(Optional.of(inProgressSession(1L)));

        assertThatThrownBy(() -> service.start(driver, new StartDriveSessionRequest(BUS_ID, RouteDirection.PICKUP, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ── end() ──

    @Test
    void end_noOnboardStudents_completesSession() {
        AuthUser driver = authUser(DRIVER_ID, TENANT_ID, Role.DRIVER);
        DriveSession session = inProgressSession(1L);
        given(driveSessionRepository.findById(1L)).willReturn(Optional.of(session));
        given(rideEventRepository.findByBusIdAndOccurredAtBetweenOrderByOccurredAtAsc(
                eq(BUS_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(List.of(rideEvent(STUDENT_ID, RideType.BOARD), rideEvent(STUDENT_ID, RideType.ALIGHT)));

        DriveSessionResponse response = service.end(driver, 1L);

        assertThat(response.status()).isEqualTo(DriveSessionStatus.COMPLETED);
    }

    @Test
    void end_onboardStudentRemaining_throwsConflict() {
        AuthUser driver = authUser(DRIVER_ID, TENANT_ID, Role.DRIVER);
        DriveSession session = inProgressSession(1L);
        given(driveSessionRepository.findById(1L)).willReturn(Optional.of(session));
        // 마지막 기록이 BOARD뿐 — 하차 미기록(G2)
        given(rideEventRepository.findByBusIdAndOccurredAtBetweenOrderByOccurredAtAsc(
                eq(BUS_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(List.of(rideEvent(STUDENT_ID, RideType.BOARD)));

        assertThatThrownBy(() -> service.end(driver, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void end_notOwner_throwsForbidden() {
        AuthUser otherDriver = authUser(999L, TENANT_ID, Role.DRIVER);
        DriveSession session = inProgressSession(1L); // 시작한 기사는 DRIVER_ID(500L)
        given(driveSessionRepository.findById(1L)).willReturn(Optional.of(session));

        assertThatThrownBy(() -> service.end(otherDriver, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void end_alreadyCompleted_throwsConflict() {
        AuthUser driver = authUser(DRIVER_ID, TENANT_ID, Role.DRIVER);
        DriveSession session = inProgressSession(1L);
        session.end(); // 이미 한 번 종료됨
        given(driveSessionRepository.findById(1L)).willReturn(Optional.of(session));
        given(rideEventRepository.findByBusIdAndOccurredAtBetweenOrderByOccurredAtAsc(
                eq(BUS_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(List.of());

        assertThatThrownBy(() -> service.end(driver, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ── checkApproachAndNoShow() (G1) ──

    @Test
    void checkApproachAndNoShow_withinApproachWindow_publishesApproachOnly() {
        DriveSession session = sessionStartedMinutesAgo(1L, 0); // 방금 시작
        RoutePlan plan = planWithStop(7L, STUDENT_ID, 4 * 60); // ETA 4분 — APPROACH 구간(5분 이내)
        ReflectionTestUtils.setField(session, "routePlanId", 7L);
        given(driveSessionRepository.findByStatusAndDirection(DriveSessionStatus.IN_PROGRESS, RouteDirection.PICKUP))
                .willReturn(List.of(session));
        given(routePlanRepository.findById(7L)).willReturn(Optional.of(plan));
        given(rideEventRepository.findByBusIdAndOccurredAtBetweenOrderByOccurredAtAsc(
                eq(BUS_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(List.of()); // 아직 승차 기록 없음
        given(studentRepository.findById(STUDENT_ID)).willReturn(Optional.of(student(STUDENT_ID, TENANT_ID)));

        service.checkApproachAndNoShow();

        verify(eventPublisher, times(1)).publishEvent(any(ApproachEvent.class));
        verify(eventPublisher, never()).publishEvent(any(NoShowEvent.class));
    }

    @Test
    void checkApproachAndNoShow_pastNoShowWindow_publishesNoShowOnly() {
        DriveSession session = sessionStartedMinutesAgo(1L, 20); // 20분 전 시작
        RoutePlan plan = planWithStop(7L, STUDENT_ID, 60); // ETA 1분 — 지금은 도착+10분 훨씬 지남
        ReflectionTestUtils.setField(session, "routePlanId", 7L);
        given(driveSessionRepository.findByStatusAndDirection(DriveSessionStatus.IN_PROGRESS, RouteDirection.PICKUP))
                .willReturn(List.of(session));
        given(routePlanRepository.findById(7L)).willReturn(Optional.of(plan));
        given(rideEventRepository.findByBusIdAndOccurredAtBetweenOrderByOccurredAtAsc(
                eq(BUS_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(List.of());
        given(studentRepository.findById(STUDENT_ID)).willReturn(Optional.of(student(STUDENT_ID, TENANT_ID)));

        service.checkApproachAndNoShow();

        verify(eventPublisher, times(1)).publishEvent(any(NoShowEvent.class));
        verify(eventPublisher, never()).publishEvent(any(ApproachEvent.class));
    }

    @Test
    void checkApproachAndNoShow_alreadyBoarded_publishesNothing() {
        DriveSession session = sessionStartedMinutesAgo(1L, 20);
        RoutePlan plan = planWithStop(7L, STUDENT_ID, 60); // NO_SHOW 조건이었을 ETA지만 이미 승차함
        ReflectionTestUtils.setField(session, "routePlanId", 7L);
        given(driveSessionRepository.findByStatusAndDirection(DriveSessionStatus.IN_PROGRESS, RouteDirection.PICKUP))
                .willReturn(List.of(session));
        given(routePlanRepository.findById(7L)).willReturn(Optional.of(plan));
        given(rideEventRepository.findByBusIdAndOccurredAtBetweenOrderByOccurredAtAsc(
                eq(BUS_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(List.of(rideEvent(STUDENT_ID, RideType.BOARD)));

        service.checkApproachAndNoShow();

        verify(eventPublisher, never()).publishEvent(any(ApproachEvent.class));
        verify(eventPublisher, never()).publishEvent(any(NoShowEvent.class));
    }

    @Test
    void checkApproachAndNoShow_sessionWithoutRoutePlan_isSkipped() {
        DriveSession session = sessionStartedMinutesAgo(1L, 20); // routePlanId 없음(수동 운행)
        given(driveSessionRepository.findByStatusAndDirection(DriveSessionStatus.IN_PROGRESS, RouteDirection.PICKUP))
                .willReturn(List.of(session));

        service.checkApproachAndNoShow();

        verify(routePlanRepository, never()).findById(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    // ── fixtures ──

    private Bus bus(Long id, Long tenantId, Long driverId) {
        Tenant tenant = Tenant.builder().name("한빛학원").build();
        ReflectionTestUtils.setField(tenant, "id", tenantId);
        User driver = User.builder().email("driver@school.com").name("박기사").password("x").build();
        ReflectionTestUtils.setField(driver, "id", driverId);
        Bus bus = Bus.builder().tenant(tenant).name("3호차").seatCapacity(25).driver(driver).build();
        ReflectionTestUtils.setField(bus, "id", id);
        return bus;
    }

    private DriveSession inProgressSession(Long id) {
        DriveSession session = DriveSession.builder()
                .tenantId(TENANT_ID).busId(BUS_ID).driverId(DRIVER_ID)
                .direction(RouteDirection.PICKUP).serviceDate(LocalDate.now()).routePlanId(null).build();
        ReflectionTestUtils.setField(session, "id", id);
        return session;
    }

    private DriveSession sessionStartedMinutesAgo(Long id, long minutesAgo) {
        DriveSession session = inProgressSession(id);
        ReflectionTestUtils.setField(session, "startedAt", LocalDateTime.now().minusMinutes(minutesAgo));
        return session;
    }

    private RoutePlan publishedPlan(Long id, Long busId, RouteDirection direction) {
        RoutePlan plan = RoutePlan.builder()
                .tenantId(TENANT_ID).busId(busId).direction(direction).status(RoutePlanStatus.PUBLISHED)
                .version(1).serviceDate(LocalDate.now()).polyline("[]").totalDistanceM(0).totalDurationS(0).build();
        ReflectionTestUtils.setField(plan, "id", id);
        return plan;
    }

    private RoutePlan planWithStop(Long id, Long studentId, long etaSeconds) {
        RoutePlan plan = publishedPlan(id, BUS_ID, RouteDirection.PICKUP);
        plan.addStop(studentId, 37.5, 127.0, etaSeconds);
        return plan;
    }

    private RideEvent rideEvent(Long studentId, RideType type) {
        return RideEvent.builder()
                .tenantId(TENANT_ID).studentId(studentId).busId(BUS_ID).type(type)
                .occurredAt(LocalDateTime.now()).source(RideSource.MANUAL).build();
    }

    private Student student(Long id, Long tenantId) {
        Tenant tenant = Tenant.builder().name("한빛학원").build();
        ReflectionTestUtils.setField(tenant, "id", tenantId);
        Student student = Student.builder().tenant(tenant).name("김민준").build();
        ReflectionTestUtils.setField(student, "id", id);
        return student;
    }

    private AuthUser authUser(Long userId, Long tenantId, Role role) {
        return new AuthUser(userId, "u@school.com", List.of(new AuthUser.Membership(tenantId, role)));
    }
}
