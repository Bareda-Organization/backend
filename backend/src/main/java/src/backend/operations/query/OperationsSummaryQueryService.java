package src.backend.operations.query;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.attendance.entity.AttendanceException;
import src.backend.attendance.repository.spec.AttendanceExceptionRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.drivesession.entity.DriveSession;
import src.backend.drivesession.entity.DriveSessionStatus;
import src.backend.drivesession.repository.spec.DriveSessionRepository;
import src.backend.global.common.ApprovalStatus;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.location.dto.BusLocationPing;
import src.backend.location.repository.spec.BusLocationRepository;
import src.backend.notification.domain.NotificationLog;
import src.backend.notification.dto.NotificationResponse;
import src.backend.notification.repository.spec.NotificationLogRepository;
import src.backend.operations.dto.OperationsSummary;
import src.backend.route.entity.Route;
import src.backend.routing.domain.RouteDirection;
import src.backend.routing.domain.RoutePlanStatus;
import src.backend.routing.entity.RoutePlan;
import src.backend.routing.repository.spec.RoutePlanRepository;
import src.backend.schedule.entity.ScheduleChangeRequest;
import src.backend.schedule.repository.spec.ScheduleChangeRequestRepository;
import src.backend.sos.entity.SosEvent;
import src.backend.sos.entity.SosStatus;
import src.backend.sos.repository.spec.SosEventRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.spec.StudentRepository;

/**
 * 관리자 첫 화면 지표 카드(BG-22) — "오늘 뭐가 돌고 있고, 뭘 처리해야 하고, 뭐가 이상한가"를
 * 한 번의 호출로 모은다. 항목별 근거와 한계는 {@link OperationsSummary} 및 설계 §5·§5.1 참고.
 *
 * <p>단일 구현이라 spec/impl 로 나누지 않는다(reference.md §핵심요약 — 단순 CRUD/조회는 분리하지 않음).
 * 조회 전용이라 {@code command} 패키지에 두지 않는다.
 *
 * <p><b>N+1 회피</b>: 각 도메인을 테넌트 단위로 한 번씩만 조회하고 메모리에서 조합한다. 기존
 * 저장소 인터페이스에 새 쿼리 메서드를 추가하지 않는(다른 에이전트가 같은 파일을 동시에 다루는)
 * 제약 때문에, 세션·결석신고·일정변경·노선계획 일부는 테넌트 전체 이력을 한 번에 읽어 메모리에서
 * 날짜로 좁힌다 — 테이블마다 쿼리 1회는 유지되므로 N+1 은 아니지만, 이력이 누적될수록 조회량이
 * 늘어나는 절충이다(설계 §7 이 이미 인정한 "페이지네이션 미도입"과 같은 종류의 절충).
 */
@Service
public class OperationsSummaryQueryService {

    /**
     * 세션 시작 후 이 값을 넘기면 "종료 처리를 잊은 세션"으로 의심한다. 설계 §5 표가 "임계값 결정
     * 필요"로 남긴 항목이라 이 구현이 정한 값이다 — 통상 1회 운행(수십 분)의 여러 배로 잡아 정상
     * 운행 중인 세션까지 걸리지 않게 했다. 운영 데이터가 쌓이면 조정 대상.
     */
    private static final Duration LONG_RUNNING_THRESHOLD = Duration.ofHours(3);

    /** 보험 만료 임박 판정 기준 — 만료 전 이 일수 이내면 "갱신 준비 대상"으로 분류한다. */
    private static final int INSURANCE_EXPIRING_SOON_DAYS = 30;

    private final BusRepository busRepository;
    private final StudentRepository studentRepository;
    private final DriveSessionRepository driveSessionRepository;
    private final SosEventRepository sosEventRepository;
    private final AttendanceExceptionRepository attendanceExceptionRepository;
    private final ScheduleChangeRequestRepository scheduleChangeRequestRepository;
    private final RoutePlanRepository routePlanRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final BusLocationRepository busLocationRepository;

    public OperationsSummaryQueryService(BusRepository busRepository,
                                         StudentRepository studentRepository,
                                         DriveSessionRepository driveSessionRepository,
                                         SosEventRepository sosEventRepository,
                                         AttendanceExceptionRepository attendanceExceptionRepository,
                                         ScheduleChangeRequestRepository scheduleChangeRequestRepository,
                                         RoutePlanRepository routePlanRepository,
                                         NotificationLogRepository notificationLogRepository,
                                         BusLocationRepository busLocationRepository) {
        this.busRepository = busRepository;
        this.studentRepository = studentRepository;
        this.driveSessionRepository = driveSessionRepository;
        this.sosEventRepository = sosEventRepository;
        this.attendanceExceptionRepository = attendanceExceptionRepository;
        this.scheduleChangeRequestRepository = scheduleChangeRequestRepository;
        this.routePlanRepository = routePlanRepository;
        this.notificationLogRepository = notificationLogRepository;
        this.busLocationRepository = busLocationRepository;
    }

    @Transactional(readOnly = true)
    public OperationsSummary getSummary(AuthUser admin, Long tenantId) {
        Long tenant = TenantGuard.resolveTenantId(admin, tenantId);
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        List<Bus> buses = busRepository.findByTenantId(tenant);
        Map<Long, Bus> busById = buses.stream().collect(Collectors.toMap(Bus::getId, b -> b));

        List<DriveSession> todaySessions = driveSessionRepository.findByTenantIdOrderByStartedAtDesc(tenant).stream()
                .filter(s -> s.getServiceDate().equals(today))
                .toList();

        List<Student> activeStudents = studentRepository.findByTenantIdAndActiveTrue(tenant);

        return new OperationsSummary(
                buildToday(buses, todaySessions),
                buildPending(tenant),
                buildDispatch(tenant, buses, busById, activeStudents, today),
                buildCompliance(buses, today),
                buildNotifications(tenant, today),
                buildAttendance(tenant, activeStudents, today),
                buildAnomalies(busById, todaySessions, now));
    }

    // ---- today ----

    private OperationsSummary.Today buildToday(List<Bus> buses, List<DriveSession> todaySessions) {
        Map<Long, List<DriveSession>> sessionsByBus = todaySessions.stream()
                .collect(Collectors.groupingBy(DriveSession::getBusId));

        int running = 0;
        int completed = 0;
        int notStarted = 0;
        Map<RouteDirection, int[]> perDirection = new EnumMap<>(RouteDirection.class); // [running, completed, notStarted]
        for (RouteDirection direction : RouteDirection.values()) {
            perDirection.put(direction, new int[3]);
        }

        for (Bus bus : buses) {
            List<DriveSession> busSessions = sessionsByBus.getOrDefault(bus.getId(), List.of());
            switch (statusOf(busSessions)) {
                case RUNNING -> running++;
                case COMPLETED -> completed++;
                case NOT_STARTED -> notStarted++;
            }
            for (RouteDirection direction : RouteDirection.values()) {
                List<DriveSession> directionSessions = busSessions.stream()
                        .filter(s -> s.getDirection() == direction).toList();
                int[] counts = perDirection.get(direction);
                switch (statusOf(directionSessions)) {
                    case RUNNING -> counts[0]++;
                    case COMPLETED -> counts[1]++;
                    case NOT_STARTED -> counts[2]++;
                }
            }
        }

        Map<RouteDirection, OperationsSummary.Today.DirectionCounts> byDirection = new EnumMap<>(RouteDirection.class);
        perDirection.forEach((direction, counts) ->
                byDirection.put(direction, new OperationsSummary.Today.DirectionCounts(counts[0], counts[1], counts[2])));

        return new OperationsSummary.Today(buses.size(), running, completed, notStarted, byDirection);
    }

    private enum SimpleStatus { RUNNING, COMPLETED, NOT_STARTED }

    /** 세션 목록 하나(버스 전체 또는 방향 하나)를 세 상태 중 하나로 접는다 — 진행 중이 있으면 무조건 RUNNING. */
    private SimpleStatus statusOf(List<DriveSession> sessions) {
        if (sessions.stream().anyMatch(s -> s.getStatus() == DriveSessionStatus.IN_PROGRESS)) {
            return SimpleStatus.RUNNING;
        }
        if (!sessions.isEmpty()) {
            return SimpleStatus.COMPLETED;
        }
        return SimpleStatus.NOT_STARTED;
    }

    // ---- pending ----

    private OperationsSummary.Pending buildPending(Long tenant) {
        long sosOpen = sosEventRepository.findByTenantIdOrderByOccurredAtDesc(tenant).stream()
                .filter(e -> e.getStatus() == SosStatus.OPEN)
                .count();
        long attendancePending = attendanceExceptionRepository.findByTenantIdOrderByTargetDateDesc(tenant).stream()
                .filter(e -> e.getStatus() == ApprovalStatus.PENDING)
                .count();
        long scheduleChangePending = scheduleChangeRequestRepository.findByTenantIdOrderByRequestedDateDesc(tenant).stream()
                .filter(r -> r.getStatus() == ApprovalStatus.PENDING)
                .count();
        return new OperationsSummary.Pending(sosOpen, attendancePending, scheduleChangePending);
    }

    // ---- dispatch ----

    private OperationsSummary.Dispatch buildDispatch(Long tenant, List<Bus> buses, Map<Long, Bus> busById,
                                                      List<Student> activeStudents, LocalDate today) {
        Map<Long, Long> onboardByBus = studentRepository
                .findByAssignedBusIdInAndActiveTrue(buses.stream().map(Bus::getId).toList()).stream()
                .collect(Collectors.groupingBy(s -> s.getAssignedBus().getId(), Collectors.counting()));

        List<OperationsSummary.Dispatch.OverCapacityBus> overCapacityBuses = new ArrayList<>();
        List<OperationsSummary.Dispatch.BusWithoutCrew> busesWithoutCrew = new ArrayList<>();
        for (Bus bus : buses) {
            Route route = bus.getRoute();
            int onboard = onboardByBus.getOrDefault(bus.getId(), 0L).intValue();
            if (route != null && onboard > route.getAssignCapacity()) {
                overCapacityBuses.add(new OperationsSummary.Dispatch.OverCapacityBus(
                        bus.getId(), bus.getName(), onboard, route.getAssignCapacity()));
            }
            boolean missingDriver = bus.getDriver() == null;
            boolean missingAttendant = bus.getAttendant() == null;
            if (missingDriver || missingAttendant) {
                busesWithoutCrew.add(new OperationsSummary.Dispatch.BusWithoutCrew(
                        bus.getId(), bus.getName(), missingDriver, missingAttendant));
            }
        }

        List<OperationsSummary.Dispatch.StudentWithoutCoords> studentsWithoutCoords = activeStudents.stream()
                .filter(s -> missingPickup(s) || missingDropoff(s))
                .map(s -> new OperationsSummary.Dispatch.StudentWithoutCoords(
                        s.getId(), s.getName(), missingPickup(s), missingDropoff(s)))
                .toList();

        List<OperationsSummary.Dispatch.UnpublishedPlan> unpublishedPlans = latestPlansToday(tenant, today).stream()
                .filter(plan -> plan.getStatus() != RoutePlanStatus.PUBLISHED)
                .map(plan -> new OperationsSummary.Dispatch.UnpublishedPlan(
                        plan.getId(), plan.getBusId(), busName(busById, plan.getBusId()),
                        plan.getDirection(), plan.getStatus(), plan.getVersion()))
                .toList();

        return new OperationsSummary.Dispatch(overCapacityBuses, studentsWithoutCoords, busesWithoutCrew, unpublishedPlans);
    }

    /** 등원 좌표 미설정 — 학생 전용 좌표도, 공유 정류장(boardingStop)도 없는 경우(D-K 폴백 둘 다 부재). */
    private boolean missingPickup(Student student) {
        return student.getPickupLat() == null && student.getBoardingStop() == null;
    }

    private boolean missingDropoff(Student student) {
        return student.getDropoffLat() == null || student.getDropoffLng() == null;
    }

    /** 당일 계획을 (busId, direction) 으로 묶어 최신 version 1건만 남긴다 — 재계산이 새 행을 쌓아도 최신만 판정 대상이다. */
    private List<RoutePlan> latestPlansToday(Long tenant, LocalDate today) {
        List<RoutePlan> todayPlans = routePlanRepository.findByTenantIdOrderByCreatedAtDesc(tenant).stream()
                .filter(plan -> plan.getServiceDate().equals(today))
                .toList();
        Map<String, RoutePlan> latestByBusDirection = new LinkedHashMap<>();
        for (RoutePlan plan : todayPlans) {
            String key = plan.getBusId() + ":" + plan.getDirection();
            latestByBusDirection.merge(key, plan,
                    (existing, candidate) -> candidate.getVersion() > existing.getVersion() ? candidate : existing);
        }
        return latestByBusDirection.values().stream()
                .sorted(Comparator.comparing(RoutePlan::getBusId))
                .toList();
    }

    private String busName(Map<Long, Bus> busById, Long busId) {
        Bus bus = busById.get(busId);
        return bus != null ? bus.getName() : null;
    }

    // ---- compliance ----

    private OperationsSummary.Compliance buildCompliance(List<Bus> buses, LocalDate today) {
        List<OperationsSummary.Compliance.InsuranceBus> expired = new ArrayList<>();
        List<OperationsSummary.Compliance.InsuranceBus> expiring = new ArrayList<>();
        for (Bus bus : buses) {
            LocalDate expiry = bus.getInsuranceExpiry();
            if (expiry == null) {
                continue;
            }
            long daysRemaining = ChronoUnit.DAYS.between(today, expiry);
            OperationsSummary.Compliance.InsuranceBus entry =
                    new OperationsSummary.Compliance.InsuranceBus(bus.getId(), bus.getName(), expiry, daysRemaining);
            if (expiry.isBefore(today)) {
                expired.add(entry);
            } else if (daysRemaining <= INSURANCE_EXPIRING_SOON_DAYS) {
                expiring.add(entry);
            }
        }
        return new OperationsSummary.Compliance(expired, expiring);
    }

    // ---- notifications ----

    private OperationsSummary.Notifications buildNotifications(Long tenant, LocalDate today) {
        List<NotificationResponse> todayNotifications = notificationLogRepository
                .findByTenantIdOrderByCreatedAtDesc(tenant).stream()
                .filter(log -> log.getCreatedAt() != null && log.getCreatedAt().toLocalDate().equals(today))
                .map(NotificationResponse::from)
                .toList();
        return new OperationsSummary.Notifications(todayNotifications);
    }

    // ---- attendance ----

    /**
     * MON-06 — 탑승 예정(expected) 은 버스에 배정된 활성 학생 중 당일 승인된 결석을 뺀 값이다.
     * absent 는 그 결석 건수 자체다. {@code absent ≠ no_show}(기획 C-02) — 사전 승인된 결석만 센다.
     */
    private OperationsSummary.Attendance buildAttendance(Long tenant, List<Student> activeStudents, LocalDate today) {
        long assignedCount = activeStudents.stream().filter(s -> s.getAssignedBus() != null).count();
        long absent = attendanceExceptionRepository.findByTenantIdOrderByTargetDateDesc(tenant).stream()
                .filter(e -> e.getTargetDate().equals(today) && e.getStatus() == ApprovalStatus.APPROVED)
                .count();
        long expected = Math.max(0, assignedCount - absent);
        return new OperationsSummary.Attendance(expected, absent);
    }

    // ---- anomalies ----

    private OperationsSummary.Anomalies buildAnomalies(Map<Long, Bus> busById, List<DriveSession> todaySessions,
                                                        LocalDateTime now) {
        List<DriveSession> inProgress = todaySessions.stream()
                .filter(s -> s.getStatus() == DriveSessionStatus.IN_PROGRESS)
                .toList();

        Map<Long, OperationsSummary.Anomalies.StaleLocationBus> staleByBus = new LinkedHashMap<>();
        List<OperationsSummary.Anomalies.LongRunningSession> longRunning = new ArrayList<>();
        for (DriveSession session : inProgress) {
            String name = busName(busById, session.getBusId());

            boolean stale = busLocationRepository.findLatest(session.getBusId()).isEmpty();
            if (stale) {
                staleByBus.putIfAbsent(session.getBusId(),
                        new OperationsSummary.Anomalies.StaleLocationBus(session.getBusId(), name, session.getId()));
            }

            Duration elapsed = Duration.between(session.getStartedAt(), now);
            if (elapsed.compareTo(LONG_RUNNING_THRESHOLD) >= 0) {
                longRunning.add(new OperationsSummary.Anomalies.LongRunningSession(
                        session.getId(), session.getBusId(), name, session.getStartedAt(), elapsed.toMinutes()));
            }
        }

        List<OperationsSummary.Anomalies.DelayedBus> delayed = buildDelayedBuses(busById, inProgress, now);

        return new OperationsSummary.Anomalies(List.copyOf(staleByBus.values()), longRunning, delayed);
    }

    /**
     * ⚠ 추정치. 정류장 실제 도달 시각을 기록하는 수단이 없어 계획 총 소요시간(RoutePlan.totalDurationS)과
     * 세션 경과 시간을 비교한다 — 운행이 계획보다 늦게 시작했거나 계획 자체가 낙관적이면 실제로는
     * 늦지 않았는데도 지연으로 잡힐 수 있다.
     */
    private List<OperationsSummary.Anomalies.DelayedBus> buildDelayedBuses(
            Map<Long, Bus> busById, List<DriveSession> inProgress, LocalDateTime now) {
        List<Long> planIds = inProgress.stream()
                .map(DriveSession::getRoutePlanId)
                .filter(id -> id != null)
                .toList();
        if (planIds.isEmpty()) {
            return List.of();
        }
        Map<Long, RoutePlan> planById = routePlanRepository.findAllById(planIds).stream()
                .collect(Collectors.toMap(RoutePlan::getId, p -> p));

        List<OperationsSummary.Anomalies.DelayedBus> delayed = new ArrayList<>();
        for (DriveSession session : inProgress) {
            RoutePlan plan = session.getRoutePlanId() != null ? planById.get(session.getRoutePlanId()) : null;
            if (plan == null) {
                continue;
            }
            long elapsedSeconds = Duration.between(session.getStartedAt(), now).getSeconds();
            long plannedSeconds = (long) plan.getTotalDurationS();
            if (elapsedSeconds > plannedSeconds) {
                delayed.add(new OperationsSummary.Anomalies.DelayedBus(
                        session.getBusId(), busName(busById, session.getBusId()), session.getId(),
                        elapsedSeconds - plannedSeconds));
            }
        }
        return delayed;
    }
}
