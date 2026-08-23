package src.backend.operations.query;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.attendance.entity.AttendanceException;
import src.backend.attendance.repository.spec.AttendanceExceptionRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.drivesession.dto.DriveSessionResponse;
import src.backend.drivesession.entity.DriveSessionStatus;
import src.backend.drivesession.query.DriveSessionQueryService;
import src.backend.global.common.ApprovalStatus;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.location.repository.spec.BusLocationRepository;
import src.backend.notification.NotificationThresholds;
import src.backend.operations.domain.BoardingStatus;
import src.backend.operations.domain.BoardingStatusResolver;
import src.backend.operations.dto.BusOperationDetail;
import src.backend.operations.dto.BusOperationSummary;
import src.backend.operations.dto.BusSessionStatus;
import src.backend.rideevent.entity.RideEvent;
import src.backend.rideevent.entity.RideType;
import src.backend.rideevent.repository.spec.RideEventRepository;
import src.backend.routing.domain.RouteDirection;
import src.backend.routing.domain.RoutePlanStatus;
import src.backend.routing.entity.RoutePlan;
import src.backend.routing.entity.RoutePlanStop;
import src.backend.routing.repository.spec.RoutePlanRepository;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.student.repository.spec.StudentRepository;

/**
 * 관리자 운영 현황 조회(BG-21) — 단순 CRUD가 아니라 여러 저장소를 합쳐 판정하는 조회 전용 서비스라
 * 인터페이스 없이 concrete 클래스로 둔다(§11.3). CQRS 원칙(§7)에 따라 command 계층을 두지 않는다.
 *
 * <p>버스 N대 × 학생 M명을 매 호출마다 훑으므로 N+1 을 피하는 게 핵심이다 — 버스 목록·배정 학생·
 * 당일 승하차기록·당일 승인결석·당일 배포 계획·진행/완료 세션을 **각각 정확히 한 번씩** 조회하고
 * 나머지는 메모리에서 버스·학생 단위로 접는다(설계 §7, `BusQueryService.listBuses` 와 같은 패턴).
 */
@Service
public class OperationsQueryService {

    private final BusRepository busRepository;
    private final StudentRepository studentRepository;
    private final RideEventRepository rideEventRepository;
    private final AttendanceExceptionRepository attendanceExceptionRepository;
    private final RoutePlanRepository routePlanRepository;
    private final BusLocationRepository busLocationRepository;
    private final DriveSessionQueryService driveSessionQueryService;
    private final StudentGuardianRepository studentGuardianRepository;

    public OperationsQueryService(BusRepository busRepository,
                                  StudentRepository studentRepository,
                                  RideEventRepository rideEventRepository,
                                  AttendanceExceptionRepository attendanceExceptionRepository,
                                  RoutePlanRepository routePlanRepository,
                                  BusLocationRepository busLocationRepository,
                                  DriveSessionQueryService driveSessionQueryService,
                                  StudentGuardianRepository studentGuardianRepository) {
        this.busRepository = busRepository;
        this.studentRepository = studentRepository;
        this.rideEventRepository = rideEventRepository;
        this.attendanceExceptionRepository = attendanceExceptionRepository;
        this.routePlanRepository = routePlanRepository;
        this.busLocationRepository = busLocationRepository;
        this.driveSessionQueryService = driveSessionQueryService;
        this.studentGuardianRepository = studentGuardianRepository;
    }

    /** 관리자: 학원 버스별 운행 현황 목록. `tenantId` 는 학원 관리자면 생략 가능, 플랫폼 관리자는 필수다. */
    @Transactional(readOnly = true)
    public List<BusOperationSummary> getBusOperations(AuthUser admin, Long tenantId) {
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, tenantId);
        List<Bus> buses = busRepository.findByTenantId(effectiveTenant);
        if (buses.isEmpty()) {
            return List.of();
        }
        List<Long> busIds = buses.stream().map(Bus::getId).toList();

        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        // 배정 학생(N+1 회피) — 버스별로 따로 조회하지 않고 배정 버스 id 목록으로 한 번에 읽는다.
        Map<Long, List<Student>> studentsByBus = studentRepository
                .findByAssignedBusIdInAndActiveTrue(busIds).stream()
                .collect(Collectors.groupingBy(s -> s.getAssignedBus().getId()));

        // 당일 승하차 기록(N+1 회피) — 학원 전체를 오늘 하루로 한 번에 조회해 학생별로 접는다.
        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.plusDays(1).atStartOfDay();
        Map<Long, List<RideType>> recordedTypesByStudent = rideEventRepository
                .findByTenantIdAndOccurredAtBetweenOrderByOccurredAtAsc(effectiveTenant, startOfDay, endOfDay)
                .stream()
                .collect(Collectors.groupingBy(RideEvent::getStudentId,
                        Collectors.mapping(RideEvent::getType, Collectors.toList())));

        // 당일 승인 결석(N+1 회피) — ActiveRosterReader(학생별 개별 조회)를 재사용하지 않는다.
        // 그 경로는 학생 수만큼 쿼리가 나가 이 화면의 성능 요구(§7)를 어긴다. 여기서는 학원 전체
        // 이력을 한 번에 읽어 오늘·승인 건만 걸러 학생 id 집합으로 접는다.
        Set<Long> approvedAbsentTodayStudentIds = attendanceExceptionRepository
                .findByTenantIdOrderByTargetDateDesc(effectiveTenant).stream()
                .filter(e -> e.getStatus() == ApprovalStatus.APPROVED && e.getTargetDate().equals(today))
                .map(AttendanceException::getStudentId)
                .collect(Collectors.toSet());

        // 정차 도달 시각 추정용 노선 계획(N+1 회피) — 학원 전체를 한 번에 조회해 당일·배포됨만 남기고
        // (버스, 방향)별 최신 버전 하나로 접는다. 학생별 ETA 는 그 계획의 stops 에서 얻는다.
        Map<Long, Map<RouteDirection, RoutePlan>> publishedPlanByBus = routePlanRepository
                .findByTenantIdOrderByCreatedAtDesc(effectiveTenant).stream()
                .filter(p -> p.getStatus() == RoutePlanStatus.PUBLISHED && p.getServiceDate().equals(today))
                .collect(Collectors.groupingBy(RoutePlan::getBusId,
                        Collectors.toMap(RoutePlan::getDirection, p -> p,
                                (a, b) -> a.getVersion() >= b.getVersion() ? a : b,
                                () -> new EnumMap<>(RouteDirection.class))));

        // 진행 중/완료 세션(N+1 회피, BG-7 상태 필터 재사용) — 학원 전체를 상태별로 한 번씩 조회한다.
        // 전체 이력을 받아 고르지 않는다: COMPLETED 는 세션 시작 시각으로 최신 1건만 남기고, 그중에서도
        // 서비스 날짜가 오늘인 것만 "오늘 완료"로 취급한다.
        Map<Long, DriveSessionResponse> inProgressByBus = driveSessionQueryService
                .getTenantHistory(admin, effectiveTenant, DriveSessionStatus.IN_PROGRESS).stream()
                .collect(Collectors.toMap(DriveSessionResponse::busId, s -> s, (a, b) -> a));
        Map<Long, DriveSessionResponse> completedTodayByBus = driveSessionQueryService
                .getTenantHistory(admin, effectiveTenant, DriveSessionStatus.COMPLETED).stream()
                .filter(s -> s.serviceDate().equals(today))
                .collect(Collectors.toMap(DriveSessionResponse::busId, s -> s,
                        (a, b) -> a.startedAt().isAfter(b.startedAt()) ? a : b));

        return buses.stream()
                .map(bus -> toSummary(bus, studentsByBus.getOrDefault(bus.getId(), List.of()),
                        recordedTypesByStudent, approvedAbsentTodayStudentIds,
                        publishedPlanByBus.getOrDefault(bus.getId(), Map.of()),
                        inProgressByBus.get(bus.getId()), completedTodayByBus.get(bus.getId()), now))
                .toList();
    }

    /**
     * 관리자: 버스 1대 상세 — 학생별 상태 + 경로(설계 §4.2). 목록 API({@link #getBusOperations})와
     * 판정 규칙(§3)은 같지만 버스 1대만 다루므로 학원 전체를 조회해 접는 대신 이 버스 하나로
     * 좁힌 저장소 메서드({@code findByAssignedBusIdAndActiveTrue}·{@code findByBusIdAndOccurredAt...})를
     * 쓴다 — 학원 전체를 훑을 필요가 없다.
     *
     * <p>노선 계획이 없으면(당일 미배포·미생성) {@code routePlan} 이 null 인 응답을 200 으로 돌려준다.
     * 버스·학생 명단은 노선 계획과 무관하게 유효한 정보이고, 계획 부재는 "아직 배차 준비 중"인 정상
     * 상태라 404 로 다룰 근거가 없다 — 404 는 busId 자체가 없을 때만 쓴다.
     */
    @Transactional(readOnly = true)
    public BusOperationDetail getBusOperationDetail(AuthUser admin, Long busId) {
        Bus bus = busRepository.findById(busId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "버스를 찾을 수 없습니다"));
        Long effectiveTenant = TenantGuard.resolveTenantId(admin, bus.getTenant().getId());

        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.plusDays(1).atStartOfDay();

        List<Student> roster = studentRepository.findByAssignedBusIdAndActiveTrue(busId);

        // 명단의 학생 id 로 조회한다 — busId 로 조회하면 목록 API 와 답이 갈린다. RideEvent.busId 는
        // 기록 당시의 버스이고 명단은 현재 배정이라, 당일 중 배정이 바뀐 학생(StudentCommandService
        // .updateAssignment)의 오전 기록이 busId 필터에서 빠져 상세만 WAITING·NO_SHOW 로 판정한다.
        // 목록 API 는 학원 전체를 studentId 로 접으므로 그 기록을 반영한다(BOARDED).
        Map<Long, List<RideEvent>> eventsByStudent = rideEventRepository
                .findByStudentIdInAndOccurredAtBetweenOrderByOccurredAtAsc(
                        roster.stream().map(Student::getId).toList(), startOfDay, endOfDay).stream()
                .collect(Collectors.groupingBy(RideEvent::getStudentId));

        // 당일 승인 결석 — 목록 API 와 같은 이유로 학생별 개별 조회(ActiveRosterReader)를 쓰지 않는다.
        Set<Long> approvedAbsentTodayStudentIds = attendanceExceptionRepository
                .findByTenantIdOrderByTargetDateDesc(effectiveTenant).stream()
                .filter(e -> e.getStatus() == ApprovalStatus.APPROVED && e.getTargetDate().equals(today))
                .map(AttendanceException::getStudentId)
                .collect(Collectors.toSet());

        // 이 버스의 당일 배포 계획 — 방향별 최신 버전 하나로 접는다(목록 API 와 같은 패턴).
        Map<RouteDirection, RoutePlan> plansForBus = routePlanRepository
                .findByTenantIdAndBusIdOrderByCreatedAtDesc(effectiveTenant, busId).stream()
                .filter(p -> p.getStatus() == RoutePlanStatus.PUBLISHED && p.getServiceDate().equals(today))
                .collect(Collectors.toMap(RoutePlan::getDirection, p -> p,
                        (a, b) -> a.getVersion() >= b.getVersion() ? a : b,
                        () -> new EnumMap<>(RouteDirection.class)));

        DriveSessionResponse inProgress = driveSessionQueryService
                .getTenantHistory(admin, effectiveTenant, DriveSessionStatus.IN_PROGRESS).stream()
                .filter(s -> s.busId().equals(busId))
                .findFirst().orElse(null);
        DriveSessionResponse completedToday = driveSessionQueryService
                .getTenantHistory(admin, effectiveTenant, DriveSessionStatus.COMPLETED).stream()
                .filter(s -> s.busId().equals(busId) && s.serviceDate().equals(today))
                .max(Comparator.comparing(DriveSessionResponse::startedAt))
                .orElse(null);
        DriveSessionResponse session = inProgress != null ? inProgress : completedToday;
        BusSessionStatus sessionStatus = inProgress != null ? BusSessionStatus.IN_PROGRESS
                : completedToday != null ? BusSessionStatus.COMPLETED
                : BusSessionStatus.NOT_STARTED;
        RouteDirection direction = resolveDirection(session, plansForBus);
        LocalDateTime sessionStartedAt = session != null ? session.startedAt() : null;

        RoutePlan plan = direction != null ? plansForBus.get(direction) : null;
        Map<Long, RoutePlanStop> stopByStudent = plan == null ? Map.of()
                : plan.getStops().stream()
                        .collect(Collectors.toMap(RoutePlanStop::getStudentId, s -> s, (a, b) -> a));

        List<Long> studentIds = roster.stream().map(Student::getId).toList();
        Map<Long, List<StudentGuardian>> guardiansByStudent = studentIds.isEmpty() ? Map.of()
                : studentGuardianRepository.findWithGuardianByStudentIdIn(studentIds).stream()
                        .collect(Collectors.groupingBy(sg -> sg.getStudent().getId()));

        List<BusOperationDetail.StudentStatus> students = roster.stream()
                .map(student -> toStudentStatus(student, direction, eventsByStudent,
                        approvedAbsentTodayStudentIds, stopByStudent, guardiansByStudent, sessionStartedAt, now))
                .toList();

        BusOperationDetail.RoutePlanInfo routePlanInfo = plan == null ? null
                : new BusOperationDetail.RoutePlanInfo(plan.getVersion(), plan.getPolyline(),
                        plan.getStops().stream()
                                .map(stop -> toStopInfo(stop, roster, direction, sessionStartedAt))
                                .toList());

        return new BusOperationDetail(
                new BusOperationDetail.BusInfo(bus.getId(), bus.getName()),
                new BusOperationDetail.SessionInfo(
                        session != null ? session.id() : null, direction, sessionStatus, sessionStartedAt),
                routePlanInfo,
                students);
    }

    private BusOperationDetail.StudentStatus toStudentStatus(Student student, RouteDirection direction,
                                                             Map<Long, List<RideEvent>> eventsByStudent,
                                                             Set<Long> approvedAbsentTodayStudentIds,
                                                             Map<Long, RoutePlanStop> stopByStudent,
                                                             Map<Long, List<StudentGuardian>> guardiansByStudent,
                                                             LocalDateTime sessionStartedAt,
                                                             LocalDateTime now) {
        List<RideEvent> events = eventsByStudent.getOrDefault(student.getId(), List.of());
        List<RideType> recordedTypes = events.stream().map(RideEvent::getType).toList();
        boolean approvedAbsence = approvedAbsentTodayStudentIds.contains(student.getId());
        RoutePlanStop stop = stopByStudent.get(student.getId());
        LocalDateTime stopReachedAt = sessionStartedAt == null ? null
                : BoardingStatusResolver.estimateStopReachedAt(
                        sessionStartedAt, stop != null ? stop.getEtaSeconds() : 0L);
        BoardingStatus status = BoardingStatusResolver.resolve(
                approvedAbsence, recordedTypes, stopReachedAt, now, NotificationThresholds.NO_SHOW);
        LocalDateTime recordedAt = events.stream()
                .map(RideEvent::getOccurredAt)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        List<BusOperationDetail.GuardianView> guardians = guardiansByStudent
                .getOrDefault(student.getId(), List.of()).stream()
                .map(sg -> new BusOperationDetail.GuardianView(sg.getGuardian().getId(),
                        sg.getGuardian().getName(), sg.getRelation(), sg.getGuardian().getPhone()))
                .toList();

        return new BusOperationDetail.StudentStatus(student.getId(), student.getName(), status,
                stop != null ? stop.getSeq() : null,
                // stopSeq 와 같은 조건으로 묶는다 — 계획에 이 학생이 없는데 이름만 채우면 화면에
                // "정차 순서는 빈칸인데 정차 이름은 있는" 행이 생겨 DTO 계약(둘 다 null)과 어긋난다.
                stop != null ? resolveStopName(student, direction) : null, recordedAt, guardians);
    }

    private BusOperationDetail.RoutePlanInfo.StopInfo toStopInfo(RoutePlanStop stop, List<Student> roster,
                                                                 RouteDirection direction,
                                                                 LocalDateTime sessionStartedAt) {
        Student student = roster.stream()
                .filter(s -> s.getId().equals(stop.getStudentId()))
                .findFirst().orElse(null);
        LocalDateTime reachedAtEstimate = sessionStartedAt == null ? null
                : BoardingStatusResolver.estimateStopReachedAt(sessionStartedAt, stop.getEtaSeconds());
        return new BusOperationDetail.RoutePlanInfo.StopInfo(stop.getSeq(),
                student != null ? resolveStopName(student, direction) : null,
                stop.getLat(), stop.getLng(), stop.getEtaSeconds(), reachedAtEstimate);
    }

    /**
     * 정차 이름 — {@link RoutePlanStop} 은 정류장을 studentId·좌표로만 표현해 이름이 없다(§4.2 갭).
     * 학생 전용 pickup 좌표가 있으면 그 주소, 없으면 공유 {@code Stop} 엔티티의 이름을 쓴다(D-K,
     * {@code DriveSessionQueryService.toRosterEntry} 와 같은 우선순위 — 화면마다 다른 이름을 대면
     * 관리자가 같은 정류장을 다른 곳으로 오인한다). 하원(DROPOFF)은 공유 정류장 개념이 없어 학생별
     * 하차지 주소를 그대로 쓴다. 방향을 특정할 수 없으면(direction == null) 어느 주소가 맞는지
     * 판단할 근거가 없어 null 로 둔다.
     */
    private String resolveStopName(Student student, RouteDirection direction) {
        if (direction == RouteDirection.PICKUP) {
            if (student.getPickupLat() != null && student.getPickupLng() != null) {
                return student.getPickupAddress();
            }
            var stop = student.getBoardingStop();
            return stop != null ? stop.getName() : null;
        }
        if (direction == RouteDirection.DROPOFF) {
            return student.getDropoffAddress();
        }
        return null;
    }

    private BusOperationSummary toSummary(Bus bus, List<Student> roster,
                                          Map<Long, List<RideType>> recordedTypesByStudent,
                                          Set<Long> approvedAbsentTodayStudentIds,
                                          Map<RouteDirection, RoutePlan> plansForBus,
                                          DriveSessionResponse inProgressSession,
                                          DriveSessionResponse completedSession,
                                          LocalDateTime now) {
        DriveSessionResponse session = inProgressSession != null ? inProgressSession : completedSession;
        BusSessionStatus sessionStatus = inProgressSession != null ? BusSessionStatus.IN_PROGRESS
                : completedSession != null ? BusSessionStatus.COMPLETED
                : BusSessionStatus.NOT_STARTED;
        RouteDirection direction = resolveDirection(session, plansForBus);

        RoutePlan plan = direction != null ? plansForBus.get(direction) : null;
        Map<Long, Long> etaSecondsByStudent = plan == null ? Map.of()
                : plan.getStops().stream().collect(Collectors.toMap(
                        RoutePlanStop::getStudentId, RoutePlanStop::getEtaSeconds, (a, b) -> a));
        LocalDateTime sessionStartedAt = session != null ? session.startedAt() : null;

        BusOperationSummary.Counts counts = countStatuses(roster, recordedTypesByStudent,
                approvedAbsentTodayStudentIds, etaSecondsByStudent, sessionStartedAt, now);

        return new BusOperationSummary(bus.getId(), bus.getName(), direction, sessionStatus,
                toLocationInfo(bus.getId()), counts, toCrew(bus));
    }

    private BusOperationSummary.Counts countStatuses(List<Student> roster,
                                                     Map<Long, List<RideType>> recordedTypesByStudent,
                                                     Set<Long> approvedAbsentTodayStudentIds,
                                                     Map<Long, Long> etaSecondsByStudent,
                                                     LocalDateTime sessionStartedAt,
                                                     LocalDateTime now) {
        int boarded = 0, alighted = 0, waiting = 0, absent = 0, noShow = 0;
        for (Student student : roster) {
            boolean approvedAbsence = approvedAbsentTodayStudentIds.contains(student.getId());
            List<RideType> recorded = recordedTypesByStudent.getOrDefault(student.getId(), List.of());
            LocalDateTime stopReachedAt = sessionStartedAt == null ? null
                    : BoardingStatusResolver.estimateStopReachedAt(sessionStartedAt,
                            etaSecondsByStudent.getOrDefault(student.getId(), 0L));
            BoardingStatus status = BoardingStatusResolver.resolve(
                    approvedAbsence, recorded, stopReachedAt, now, NotificationThresholds.NO_SHOW);
            switch (status) {
                case BOARDED -> boarded++;
                case ALIGHTED -> alighted++;
                case ABSENT -> absent++;
                case NO_SHOW -> noShow++;
                case WAITING -> waiting++;
            }
        }
        return new BusOperationSummary.Counts(roster.size(), boarded, alighted, waiting, absent, noShow);
    }

    /**
     * 세션이 있으면 그 방향을 그대로 쓴다. 세션이 아직 없으면(NOT_STARTED) 오늘 배포된 계획으로
     * 짐작한다 — 등원(PICKUP)을 우선한다(관리자가 아침에 가장 먼저 보는 화면이라는 전제, 설계 §5.2 MON-01).
     * 계획도 없으면 방향을 특정할 근거가 없어 null 로 둔다.
     */
    private RouteDirection resolveDirection(DriveSessionResponse session, Map<RouteDirection, RoutePlan> plans) {
        if (session != null) {
            return session.direction();
        }
        if (plans.containsKey(RouteDirection.PICKUP)) {
            return RouteDirection.PICKUP;
        }
        if (plans.containsKey(RouteDirection.DROPOFF)) {
            return RouteDirection.DROPOFF;
        }
        return null;
    }

    /** 좌표 TTL(15초) 만료를 좌표 부재와 구분한다 — 조회가 empty 면 stale=true, 좌표·시각·출처는 null. */
    private BusOperationSummary.LocationInfo toLocationInfo(Long busId) {
        return busLocationRepository.findLatest(busId)
                .map(ping -> BusOperationSummary.LocationInfo.of(
                        ping.lat(), ping.lng(), ping.recordedAt(), ping.origin()))
                .orElseGet(BusOperationSummary.LocationInfo::staleInfo);
    }

    private BusOperationSummary.Crew toCrew(Bus bus) {
        return new BusOperationSummary.Crew(
                bus.getDriver() != null ? bus.getDriver().getName() : null,
                bus.getAttendant() != null ? bus.getAttendant().getName() : null,
                bus.getAttendant() == null);
    }
}
