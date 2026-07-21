package src.backend.drivesession.command;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import src.backend.notification.NotificationThresholds;
import src.backend.rideevent.entity.RideEvent;
import src.backend.rideevent.entity.RideType;
import src.backend.rideevent.repository.spec.RideEventRepository;
import src.backend.routing.domain.RouteDirection;
import src.backend.routing.domain.RoutePlanStatus;
import src.backend.routing.entity.RoutePlan;
import src.backend.routing.entity.RoutePlanStop;
import src.backend.routing.repository.spec.RoutePlanRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.spec.StudentRepository;

/**
 * 운행 세션 시작/종료 — 단순 CRUD라 인터페이스 없이 concrete 클래스로 둔다(§11.3).
 */
@Service
public class DriveSessionCommandService {

    private final DriveSessionRepository driveSessionRepository;
    private final BusRepository busRepository;
    private final RoutePlanRepository routePlanRepository;
    private final RideEventRepository rideEventRepository;
    private final StudentRepository studentRepository;
    private final ApplicationEventPublisher eventPublisher;

    public DriveSessionCommandService(DriveSessionRepository driveSessionRepository,
                                      BusRepository busRepository,
                                      RoutePlanRepository routePlanRepository,
                                      RideEventRepository rideEventRepository,
                                      StudentRepository studentRepository,
                                      ApplicationEventPublisher eventPublisher) {
        this.driveSessionRepository = driveSessionRepository;
        this.busRepository = busRepository;
        this.routePlanRepository = routePlanRepository;
        this.rideEventRepository = rideEventRepository;
        this.studentRepository = studentRepository;
        this.eventPublisher = eventPublisher;
    }

    /** 기사: 운행 시작. 배포된 계획이 있으면 자동 연결하되, 없어도 시작은 허용한다(계획 없는 수동 운행). */
    @Transactional
    public DriveSessionResponse start(AuthUser driver, StartDriveSessionRequest req) {
        Bus bus = busRepository.findById(req.busId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "버스를 찾을 수 없습니다"));
        requireAssignedDriver(bus, driver);

        LocalDate serviceDate = req.serviceDate() != null ? req.serviceDate() : LocalDate.now();
        driveSessionRepository
                .findByBusIdAndDirectionAndServiceDateAndStatus(
                        bus.getId(), req.direction(), serviceDate, DriveSessionStatus.IN_PROGRESS)
                .ifPresent(s -> {
                    throw new BusinessException(ErrorCode.CONFLICT, "이미 진행 중인 운행이 있습니다");
                });

        Long routePlanId = routePlanRepository
                .findByBusIdAndServiceDateAndStatusOrderByDirectionAsc(bus.getId(), serviceDate, RoutePlanStatus.PUBLISHED)
                .stream()
                .filter(plan -> plan.getDirection() == req.direction())
                .findFirst()
                .map(RoutePlan::getId)
                .orElse(null);

        DriveSession saved = driveSessionRepository.save(DriveSession.builder()
                .tenantId(bus.getTenant().getId())
                .busId(bus.getId())
                .driverId(driver.userId())
                .direction(req.direction())
                .serviceDate(serviceDate)
                .routePlanId(routePlanId)
                .build());
        return DriveSessionResponse.from(saved);
    }

    /** 기사: 운행 종료(IN_PROGRESS → COMPLETED). 하차 처리되지 않은 잔류 학생이 있으면 차단한다(§6 차내 잔류 방지). */
    @Transactional
    public DriveSessionResponse end(AuthUser driver, Long id) {
        DriveSession session = driveSessionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "운행 세션을 찾을 수 없습니다"));
        requireOwner(session, driver);
        requireNoOnboardStudents(session);
        session.end();
        return DriveSessionResponse.from(session);
    }

    /**
     * 세션 시작 이후 발생한 승하차 기록을 학생별로 훑어 마지막 기록이 BOARD(하차 미기록)인
     * 학생이 남아있으면 종료를 막는다. driveSessionId 없이 busId+시간창으로 소속을 판별한다 —
     * IN_PROGRESS 세션은 버스당 동시에 1개뿐이라(start()의 중복 체크) 시간창이 곧 세션 경계다.
     */
    private void requireNoOnboardStudents(DriveSession session) {
        List<RideEvent> events = rideEventRepository.findByBusIdAndOccurredAtBetweenOrderByOccurredAtAsc(
                session.getBusId(), session.getStartedAt(), LocalDateTime.now());
        Map<Long, RideType> lastTypeByStudent = new LinkedHashMap<>();
        for (RideEvent event : events) {
            lastTypeByStudent.put(event.getStudentId(), event.getType());
        }
        boolean anyOnboard = lastTypeByStudent.values().stream().anyMatch(type -> type == RideType.BOARD);
        if (anyOnboard) {
            throw new BusinessException(ErrorCode.CONFLICT, "하차 처리되지 않은 학생이 있어 운행을 종료할 수 없습니다");
        }
    }

    /**
     * APPROACH(도착 5분 전)·NO_SHOW(도착 +10분 미승차) 판정 — {@code ApproachNoShowScheduler}가 주기 호출한다.
     * 진행 중인 등원(PICKUP) 세션만 대상(하원은 학원에서 이미 승차한 상태로 출발하므로 해당 없음).
     * 정류장별 ETA는 세션 시작시각(startedAt) + RoutePlanStop.etaSeconds 로 근사한다(§11.4 Phase 6f 설계).
     * 매 틱 조건을 다시 평가해 이벤트를 발행하지만, {@code NotificationCommandService}의 dedupKey
     * 멱등 덕분에 실제 알림 발송은 학생당 최초 1회만 일어난다(SosEscalationScheduler와 동일 패턴).
     */
    @Transactional
    public void checkApproachAndNoShow() {
        LocalDateTime now = LocalDateTime.now();
        List<DriveSession> sessions = driveSessionRepository
                .findByStatusAndDirection(DriveSessionStatus.IN_PROGRESS, RouteDirection.PICKUP);
        for (DriveSession session : sessions) {
            if (session.getRoutePlanId() == null) {
                continue; // 계획 없이 시작한 수동 운행은 ETA를 계산할 수 없어 판정에서 제외
            }
            routePlanRepository.findById(session.getRoutePlanId())
                    .ifPresent(plan -> checkSession(session, plan, now));
        }
    }

    private void checkSession(DriveSession session, RoutePlan plan, LocalDateTime now) {
        Set<Long> boarded = boardedStudentIds(session, now);
        for (RoutePlanStop stop : plan.getStops()) {
            if (boarded.contains(stop.getStudentId())) {
                continue; // 이미 승차 완료면 근접/미승차 판정이 필요 없다
            }
            LocalDateTime eta = session.getStartedAt().plusSeconds(stop.getEtaSeconds());
            boolean isApproaching = !now.isBefore(eta.minus(NotificationThresholds.APPROACH)) && now.isBefore(eta);
            boolean isNoShow = !now.isBefore(eta.plus(NotificationThresholds.NO_SHOW));
            if (!isApproaching && !isNoShow) {
                continue;
            }
            String studentName = studentRepository.findById(stop.getStudentId()).map(Student::getName).orElse("학생");
            if (isApproaching) {
                eventPublisher.publishEvent(ApproachEvent.of(
                        session.getTenantId(), stop.getStudentId(), studentName, session.getBusId(), stop.getId()));
            }
            if (isNoShow) {
                eventPublisher.publishEvent(NoShowEvent.of(
                        session.getTenantId(), stop.getStudentId(), studentName, session.getBusId(), stop.getId()));
            }
        }
    }

    /** 세션 시작 이후 이 버스에서 BOARD 기록이 한 번이라도 있었던 학생 id 집합. */
    private Set<Long> boardedStudentIds(DriveSession session, LocalDateTime now) {
        return rideEventRepository.findByBusIdAndOccurredAtBetweenOrderByOccurredAtAsc(
                        session.getBusId(), session.getStartedAt(), now)
                .stream()
                .filter(event -> event.getType() == RideType.BOARD)
                .map(RideEvent::getStudentId)
                .collect(Collectors.toSet());
    }

    private void requireAssignedDriver(Bus bus, AuthUser driver) {
        if (bus.getDriver() == null || !bus.getDriver().getId().equals(driver.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "담당 기사만 처리할 수 있습니다");
        }
    }

    private void requireOwner(DriveSession session, AuthUser driver) {
        if (!session.getDriverId().equals(driver.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인이 시작한 운행만 종료할 수 있습니다");
        }
    }
}
