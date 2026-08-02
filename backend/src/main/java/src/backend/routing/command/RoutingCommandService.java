package src.backend.routing.command;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.attendance.roster.ActiveRosterReader;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.routing.domain.BusCapacity;
import src.backend.routing.domain.LatLng;
import src.backend.routing.domain.PlannedRoute;
import src.backend.routing.domain.RouteDirection;
import src.backend.routing.domain.RoutePlanStatus;
import src.backend.routing.dto.AutoAssignRequest;
import src.backend.routing.dto.AutoAssignResponse;
import src.backend.routing.dto.GenerateRoutePlanRequest;
import src.backend.routing.dto.RoutePlanResponse;
import src.backend.routing.dto.SimulateRoutePlanRequest;
import src.backend.routing.dto.StudentOverride;
import src.backend.routing.engine.RoutePlanComputer;
import src.backend.routing.engine.spec.BusAssigner;
import src.backend.routing.entity.RoutePlan;
import src.backend.routing.entity.RoutePlanStop;
import src.backend.routing.event.RoutePlanPublishedEvent;
import src.backend.routing.event.RoutePlanRecommendedEvent;
import src.backend.routing.query.RoutePlanSimulationService;
import src.backend.routing.query.RoutePlanSimulationService.SimulationOutcome;
import src.backend.routing.repository.spec.RoutePlanRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.spec.StudentRepository;

/**
 * 노선 계획 생성 — 단순 CRUD가 아니라 orchestration(로스터 조회→알고리즘→directions API→저장)이지만
 * 대체 구현체 후보가 아니라 인터페이스 없이 concrete 클래스로 둔다(§11.3, RouteEngine/MapRouteClient가
 * 이미 각자 포트로 분리돼 있어 이 서비스 자체를 갈아끼울 필요가 없다).
 */
@Service
public class RoutingCommandService {

    private static final Logger log = LoggerFactory.getLogger(RoutingCommandService.class);

    private final RoutePlanRepository routePlanRepository;
    private final BusRepository busRepository;
    private final StudentRepository studentRepository;
    private final ActiveRosterReader activeRosterReader;
    private final BusAssigner busAssigner;
    private final ApplicationEventPublisher eventPublisher;
    private final RoutePlanComputer computer;
    private final RoutePlanSimulationService simulationService;

    public RoutingCommandService(RoutePlanRepository routePlanRepository,
                                 BusRepository busRepository,
                                 StudentRepository studentRepository,
                                 ActiveRosterReader activeRosterReader,
                                 BusAssigner busAssigner,
                                 ApplicationEventPublisher eventPublisher,
                                 RoutePlanComputer computer,
                                 RoutePlanSimulationService simulationService) {
        this.routePlanRepository = routePlanRepository;
        this.busRepository = busRepository;
        this.studentRepository = studentRepository;
        this.activeRosterReader = activeRosterReader;
        this.busAssigner = busAssigner;
        this.eventPublisher = eventPublisher;
        this.computer = computer;
        this.simulationService = simulationService;
    }

    @Transactional
    public RoutePlanResponse generate(AuthUser admin, GenerateRoutePlanRequest req) {
        Bus bus = busRepository.findById(req.busId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "버스를 찾을 수 없습니다"));
        TenantGuard.resolveTenantId(admin, bus.getTenant().getId());

        LocalDate serviceDate = req.serviceDate() != null ? req.serviceDate() : LocalDate.now();
        RoutePlan saved = buildPlan(bus, req.direction(), serviceDate, RoutePlanStatus.DRAFT);
        return RoutePlanResponse.from(saved);
    }

    /** 관리자 승인(DRAFT/RECOMMENDED → APPROVED, Phase 6f). 상태전이 자체는 {@link RoutePlan#approve}. */
    @Transactional
    public RoutePlanResponse approve(AuthUser admin, Long id) {
        RoutePlan plan = findForAdmin(admin, id);
        plan.approve(admin.userId());
        return RoutePlanResponse.from(plan);
    }

    /** 관리자 배포(APPROVED → PUBLISHED, Phase 6f). 배포되면 기사 조회 API에 노출되고, 담당 기사에게 알림이 간다(F3). */
    @Transactional
    public RoutePlanResponse publish(AuthUser admin, Long id) {
        RoutePlan plan = findForAdmin(admin, id);
        publishAndNotify(plan, admin.userId());
        return RoutePlanResponse.from(plan);
    }

    /**
     * F3: 배포 직후 {@link RoutePlanPublishedEvent}를 발행해 담당 기사에게 알림을 보낸다. 알림 파이프라인이
     * studentId 기준이라 배포 계획의 첫 정차 학생을 대표로 삼는다(배포 계획은 정차 ≥ 1 보장). 관리자 수동
     * 배포({@link #publish})·F4 자동배정 확정({@link #confirmAutoAssign}) 양쪽에서 공용으로 쓴다.
     */
    private void publishAndNotify(RoutePlan plan, Long adminUserId) {
        plan.publish(adminUserId);
        RoutePlanStop first = plan.getStops().get(0);
        String studentName = studentRepository.findById(first.getStudentId()).map(Student::getName).orElse("학생");
        eventPublisher.publishEvent(RoutePlanPublishedEvent.of(plan, first.getStudentId(), studentName));
    }

    private RoutePlan findForAdmin(AuthUser admin, Long id) {
        RoutePlan plan = routePlanRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "노선 계획을 찾을 수 없습니다"));
        TenantGuard.resolveTenantId(admin, plan.getTenantId());
        return plan;
    }

    /**
     * F4: 테넌트 전체 활성 로스터를 버스별로 자동 배정(제안)한다. 이 시점엔 {@code Student.assignedBus}를
     * 바꾸지 않는다 — 계획의 stops 가 제안 로스터를 겸하고, 실제 배정 커밋은 {@link #confirmAutoAssign}에서 이뤄진다.
     */
    @Transactional
    public AutoAssignResponse autoAssign(AuthUser admin, AutoAssignRequest req) {
        Long tenantId = TenantGuard.resolveTenantId(admin, req.tenantId());
        LocalDate serviceDate = req.serviceDate() != null ? req.serviceDate() : LocalDate.now();

        List<Bus> buses = busRepository.findByTenantId(tenantId).stream()
                .sorted(Comparator.comparing(Bus::getId))
                .toList();
        if (buses.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "배차 가능한 버스가 없습니다");
        }
        LatLng depot = computer.requireDepot(buses.get(0).getTenant());

        List<Student> roster = activeRosterReader.forTenant(tenantId, serviceDate);
        Map<Long, LatLng> points = new LinkedHashMap<>();
        List<String> excluded = new ArrayList<>();
        for (Student student : roster) {
            LatLng point = RoutePlanComputer.pointOf(student, req.direction());
            if (point == null) {
                excluded.add(student.getName());
            } else {
                points.put(student.getId(), point);
            }
        }
        if (points.isEmpty()) {
            return new AutoAssignResponse(List.of(), excluded);
        }

        List<BusCapacity> capacities = buses.stream()
                .map(bus -> new BusCapacity(bus.getId(), bus.getSeatCapacity()))
                .toList();
        Map<Long, List<Long>> assignment = busAssigner.assign(depot, points, capacities)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT,
                        "전체 정원(" + capacities.stream().mapToInt(BusCapacity::seatCapacity).sum()
                                + "명) 초과: 대상 " + points.size() + "명"));

        Map<Long, Student> studentById = roster.stream().collect(Collectors.toMap(Student::getId, s -> s));
        List<RoutePlanResponse> plans = new ArrayList<>();
        for (Bus bus : buses) {
            List<Long> studentIds = assignment.get(bus.getId());
            if (studentIds == null || studentIds.isEmpty()) {
                continue; // 이 버스엔 배정된 학생이 없음 — 계획 생성 생략
            }
            List<Student> busRoster = studentIds.stream().map(studentById::get).toList();
            RoutePlan plan = buildPlan(bus, req.direction(), serviceDate, RoutePlanStatus.RECOMMENDED, busRoster);
            plans.add(RoutePlanResponse.from(plan));
        }
        return new AutoAssignResponse(plans, excluded);
    }

    /** F4: 검토를 마친 RECOMMENDED 계획들을 확정 — 학생 배정을 커밋하고 승인·배포까지 이어서 수행한다. */
    @Transactional
    public List<RoutePlanResponse> confirmAutoAssign(AuthUser admin, List<Long> planIds) {
        List<RoutePlanResponse> results = new ArrayList<>();
        for (Long planId : planIds) {
            RoutePlan plan = findForAdmin(admin, planId);
            for (RoutePlanStop stop : plan.getStops()) {
                studentRepository.findById(stop.getStudentId())
                        .ifPresent(student -> student.assignBus(busRepository.getReferenceById(plan.getBusId())));
            }
            plan.approve(admin.userId());
            publishAndNotify(plan, admin.userId());
            results.add(RoutePlanResponse.from(plan));
        }
        return results;
    }

    /**
     * 시뮬레이션 채택(BE-5) — 변경안을 배정에 커밋하고 version+1 계획을 승인·배포까지 수행한다.
     * <p>⚠️ 화면이 본 비교 결과({@code RoutePlanComparison})를 받지 않는다. 요청의 {@code overrides} 만 받아
     * <b>서버가 다시 계산</b>한다 — 클라이언트가 보낸 거리·시간·정차 순서를 그대로 저장하면 조작된 값이 DB 에 들어간다.
     * 관리자 인가·테넌트 격리는 {@link RoutePlanSimulationService#computeCandidate}가 수행한다.
     */
    @Transactional
    public RoutePlanResponse applySimulation(AuthUser admin, SimulateRoutePlanRequest req) {
        SimulationOutcome outcome = simulationService.computeCandidate(admin, req);
        Bus bus = outcome.bus();

        // "보여주기는 관대, 저장은 엄격" — 시뮬레이션은 정원 초과도 보여주지만 저장은 거부한다(기존 buildPlan 과 같은 규칙)
        if (outcome.candidateStudentIds().size() > bus.getSeatCapacity()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "변경안(" + outcome.candidateStudentIds().size() + "명)이 버스 정원("
                            + bus.getSeatCapacity() + "명)을 초과합니다");
        }

        commitOverrides(bus, req);
        RoutePlan plan = persistPlan(bus, req.direction(), outcome.serviceDate(),
                RoutePlanStatus.RECOMMENDED, outcome.candidate());   // outcome 재사용 = 경로 API 재호출 없음
        plan.approve(admin.userId());
        publishAndNotify(plan, admin.userId());   // confirmAutoAssign 과 동일한 approve → publish 흐름
        return RoutePlanResponse.from(plan);
    }

    /**
     * P2 자동 적용 전용(BE-10) — 재계산해 새 version 을 만들고 승인·배포까지 한 번에 한다(D-H, 무승인).
     * <p>기존 {@link #replanForStudent} 계열은 {@code RECOMMENDED} 에서 멈춰 관리자 승인을 기다리는데,
     * 학부모 위치 변경은 승인 단계가 없다 — 그대로 두면 기사·선탑자 조회 API({@code PUBLISHED} 만 노출)에
     * 새 노선이 보이지 않아 "반영됐다"고 응답해놓고 실제 운행은 옛 노선으로 돈다.
     * <p>{@code actorUserId}(신청한 학부모)가 {@code approvedBy}·{@code publishedBy} 에 남는데,
     * "누가 촉발했는지" 를 남기려는 의도적 선택이다.
     * <p>인가는 호출자({@code LocationChangeCommandService})가 이미 끝냈다 — 이 메서드를 컨트롤러에 직접 노출하지 마라.
     */
    @Transactional
    public Long republishForBus(Long busId, RouteDirection direction, LocalDate serviceDate, Long actorUserId) {
        Bus bus = busRepository.findById(busId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "버스를 찾을 수 없습니다"));
        RoutePlan plan = buildPlan(bus, direction, serviceDate, RoutePlanStatus.RECOMMENDED);
        plan.approve(actorUserId);
        publishAndNotify(plan, actorUserId);   // 기존 private 메서드 재사용
        return plan.getId();
    }

    /**
     * 변경안을 Student 에 커밋한다. 방향에 따라 저장 위치가 다르다 —
     * PICKUP 은 pickupLat/pickupLng(D-K), DROPOFF 는 dropoffLat/dropoffLng.
     * boardingStop 은 여러 학생이 공유하는 Stop 이라 절대 건드리지 않는다.
     * <p>주소 문자열은 기존 값을 그대로 넘긴다 — 관리자는 지도에서 점만 찍으므로 주소를 null 로 덮으면
     * 다음 비교 화면의 정차 라벨이 "좌표 지정"으로 퇴화한다(역지오코딩은 범위 밖).
     */
    private void commitOverrides(Bus bus, SimulateRoutePlanRequest req) {
        if (req.overrides() == null) {
            return;
        }
        for (StudentOverride o : req.overrides()) {
            Student student = studentRepository.findById(o.studentId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                            "학생을 찾을 수 없습니다: " + o.studentId()));
            if (o.action() == StudentOverride.OverrideAction.REMOVE) {
                student.assignBus(null);
                continue;
            }
            // I-9: 쓰기 경로는 자기 전제를 스스로 검사한다. 시뮬레이션(applyOverrides)이 먼저 같은 검사를
            // 하지만, 그건 다른 클래스의 조회 메서드라 여기서 기대면 결합이 보이지 않아 깨지기 쉽다.
            if (!student.isActive()) {
                throw new BusinessException(ErrorCode.INVALID_INPUT,
                        "비활성(퇴원) 학생은 배차에 넣을 수 없습니다: studentId=" + o.studentId());
            }
            student.assignBus(bus);   // ADD · MOVE 공통
            if (req.direction() == RouteDirection.PICKUP) {
                student.updatePickup(student.getPickupAddress(), o.lat(), o.lng());
            } else {
                student.updateDropoff(student.getDropoffAddress(), o.lat(), o.lng());
            }
        }
    }

    /**
     * 결석/일정변경 승인 이벤트를 소비한 국소 replan(Phase 6e) — 관리자 요청이 아니라 시스템이 트리거하므로
     * {@link AuthUser} 없이 studentId 기준으로 동작한다. 해당 학생이 배정된 버스의 "최신" 계획이 마침
     * 이벤트가 가리키는 날짜({@code eventDate})의 것일 때만(= 그 계획이 실제로 영향받을 때만) 재계산한다.
     */
    @Transactional
    public void replanForStudent(Long studentId, LocalDate eventDate) {
        Student student = studentRepository.findById(studentId).orElse(null);
        if (student == null || student.getAssignedBus() == null) {
            return; // 배차되지 않은 학생 — 영향받는 노선 계획이 없다
        }
        replanForBus(student.getAssignedBus(), studentId, student.getName(), eventDate);
    }

    /**
     * F2: 배정 버스 변경 이벤트를 소비한 국소 replan — 이전 버스·신규 버스 각각의 당일 계획을 재계산한다.
     * 정류장만 바뀌어 old/new 버스가 같으면 한 번만 수행해 외부 경로 API 중복 호출을 막는다.
     */
    @Transactional
    public void replanForAssignmentChange(Long studentId, Long oldBusId, Long newBusId, LocalDate eventDate) {
        Student student = studentRepository.findById(studentId).orElse(null);
        if (student == null) {
            return;
        }
        replanForBusId(oldBusId, studentId, student.getName(), eventDate);
        if (!Objects.equals(oldBusId, newBusId)) {
            replanForBusId(newBusId, studentId, student.getName(), eventDate);
        }
    }

    private void replanForBusId(Long busId, Long triggerStudentId, String triggerStudentName, LocalDate eventDate) {
        if (busId == null) {
            return;
        }
        busRepository.findById(busId)
                .ifPresent(bus -> replanForBus(bus, triggerStudentId, triggerStudentName, eventDate));
    }

    /** 버스 하나의 방향별(PICKUP/DROPOFF) "당일 최신 계획이 있을 때만" 재계산 — replanForStudent/replanForAssignmentChange 공용. */
    private void replanForBus(Bus bus, Long triggerStudentId, String triggerStudentName, LocalDate eventDate) {
        for (RouteDirection direction : RouteDirection.values()) {
            routePlanRepository.findTopByBusIdAndDirectionOrderByVersionDesc(bus.getId(), direction)
                    .filter(existing -> existing.getServiceDate().equals(eventDate))
                    .ifPresent(existing -> replanDirection(bus, direction, eventDate, triggerStudentId, triggerStudentName));
        }
    }

    /** 실패해도(로스터 0명·정원 초과·경로 API 오류) 기존 계획은 그대로 두고 다음 이벤트로 넘어간다. */
    private void replanDirection(Bus bus, RouteDirection direction, LocalDate serviceDate,
                                  Long triggerStudentId, String triggerStudentName) {
        try {
            RoutePlan recommended = buildPlan(bus, direction, serviceDate, RoutePlanStatus.RECOMMENDED);
            eventPublisher.publishEvent(
                    RoutePlanRecommendedEvent.of(recommended, triggerStudentId, triggerStudentName));
        } catch (RuntimeException e) {
            log.warn("[routing] replan 실패, 기존 계획 유지: busId={} direction={} serviceDate={} reason={}",
                    bus.getId(), direction, serviceDate, e.getMessage());
        }
    }

    private RoutePlan buildPlan(Bus bus, RouteDirection direction, LocalDate serviceDate, RoutePlanStatus status) {
        List<Student> roster = activeRosterReader.forBus(bus.getId(), serviceDate);
        if (roster.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "생성할 로스터가 없습니다(당일 활성 학생 0명)");
        }
        return buildPlan(bus, direction, serviceDate, status, roster);
    }

    /** F4 자동배정처럼 로스터를 외부(Sweep 결과)에서 받는 경우용 — 좌표해석 이후 로직은 기존과 동일. */
    private RoutePlan buildPlan(Bus bus, RouteDirection direction, LocalDate serviceDate, RoutePlanStatus status,
                                List<Student> roster) {
        computer.requireDepot(bus.getTenant());   // ⚠️ 순서 보존: depot 미설정이 정원 초과보다 먼저 걸려야 한다

        if (roster.size() > bus.getSeatCapacity()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "로스터(" + roster.size() + "명)가 버스 정원(" + bus.getSeatCapacity() + "명)을 초과합니다");
        }
        Map<Long, LatLng> studentPoints = resolveStudentPoints(roster, direction);
        return persistPlan(bus, direction, serviceDate, status,
                computer.computeRoute(bus, direction, studentPoints));
    }

    /**
     * 계산 결과를 version+1 인 <b>새 행</b>으로 저장한다(I-5 — 기존 행은 수정하지 않는다).
     * BE-5 의 시뮬레이션 채택도 이 메서드로 합류해 "계산은 한 번, 저장은 한 곳"을 유지한다.
     */
    private RoutePlan persistPlan(Bus bus, RouteDirection direction, LocalDate serviceDate, RoutePlanStatus status,
                                  PlannedRoute route) {
        int nextVersion = routePlanRepository
                .findTopByBusIdAndDirectionOrderByVersionDesc(bus.getId(), direction)
                .map(p -> p.getVersion() + 1)
                .orElse(1);

        RoutePlan plan = RoutePlan.builder()
                .tenantId(bus.getTenant().getId())
                .busId(bus.getId())
                .direction(direction)
                .status(status)
                .version(nextVersion)
                .serviceDate(serviceDate)
                .polyline(route.polyline())
                .totalDistanceM(route.totalDistanceM())
                .totalDurationS(route.totalDurationS())
                .build();

        for (int i = 0; i < route.stopStudentIds().size(); i++) {
            LatLng point = route.stopPoints().get(i);
            plan.addStop(route.stopStudentIds().get(i), point.lat(), point.lng(), route.stopEtaSeconds().get(i));
        }

        return routePlanRepository.save(plan);
    }

    private Map<Long, LatLng> resolveStudentPoints(List<Student> roster, RouteDirection direction) {
        Map<Long, LatLng> points = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (Student student : roster) {
            LatLng point = RoutePlanComputer.pointOf(student, direction);
            if (point == null) {
                missing.add(student.getName());
            } else {
                points.put(student.getId(), point);
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "좌표가 없는 학생이 있습니다: " + missing);
        }
        return points;
    }
}
