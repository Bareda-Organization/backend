package src.backend.routing.command;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.attendance.query.AttendanceQueryService;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.tenant.TenantGuard;
import src.backend.routing.domain.LatLng;
import src.backend.routing.domain.RouteDirection;
import src.backend.routing.domain.RoutePlanStatus;
import src.backend.routing.dto.GenerateRoutePlanRequest;
import src.backend.routing.dto.RoutePlanResponse;
import src.backend.routing.engine.spec.RouteEngine;
import src.backend.routing.entity.RoutePlan;
import src.backend.routing.event.RoutePlanRecommendedEvent;
import src.backend.routing.infrastructure.spec.MapRouteClient;
import src.backend.routing.infrastructure.spec.RouteResult;
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
    private final AttendanceQueryService attendanceQueryService;
    private final RouteEngine routeEngine;
    private final MapRouteClient mapRouteClient;
    private final ApplicationEventPublisher eventPublisher;
    private final int maxWaypoints;

    public RoutingCommandService(RoutePlanRepository routePlanRepository,
                                 BusRepository busRepository,
                                 StudentRepository studentRepository,
                                 AttendanceQueryService attendanceQueryService,
                                 RouteEngine routeEngine,
                                 MapRouteClient mapRouteClient,
                                 ApplicationEventPublisher eventPublisher,
                                 @Value("${routing.max-waypoints:15}") int maxWaypoints) {
        this.routePlanRepository = routePlanRepository;
        this.busRepository = busRepository;
        this.studentRepository = studentRepository;
        this.attendanceQueryService = attendanceQueryService;
        this.routeEngine = routeEngine;
        this.mapRouteClient = mapRouteClient;
        this.eventPublisher = eventPublisher;
        this.maxWaypoints = maxWaypoints;
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

    /** 관리자 배포(APPROVED → PUBLISHED, Phase 6f). 배포되면 기사 조회 API에 노출된다. */
    @Transactional
    public RoutePlanResponse publish(AuthUser admin, Long id) {
        RoutePlan plan = findForAdmin(admin, id);
        plan.publish(admin.userId());
        return RoutePlanResponse.from(plan);
    }

    private RoutePlan findForAdmin(AuthUser admin, Long id) {
        RoutePlan plan = routePlanRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "노선 계획을 찾을 수 없습니다"));
        TenantGuard.resolveTenantId(admin, plan.getTenantId());
        return plan;
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
        LatLng depot = requireDepot(bus);

        List<Student> roster = attendanceQueryService.getActiveRoster(bus.getId(), serviceDate);
        if (roster.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "생성할 로스터가 없습니다(당일 활성 학생 0명)");
        }
        if (roster.size() > bus.getSeatCapacity()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "로스터(" + roster.size() + "명)가 버스 정원(" + bus.getSeatCapacity() + "명)을 초과합니다");
        }

        Map<Long, LatLng> studentPoints = resolveStudentPoints(roster, direction);

        List<Long> optimizedOrder = routeEngine.optimizeOrder(depot, studentPoints);
        List<Long> stopOrder = direction == RouteDirection.DROPOFF
                ? optimizedOrder
                : reversed(optimizedOrder);

        List<LatLng> waypoints = buildWaypoints(depot, stopOrder, studentPoints, direction);
        RouteResult routeResult = resolveRoute(waypoints);

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
                .polyline(routeResult.polyline())
                .totalDistanceM(routeResult.totalDistanceM())
                .totalDurationS(routeResult.totalDurationS())
                .build();

        List<Double> cumulative = cumulativeSeconds(routeResult.legDurationsS());
        int stopWaypointOffset = direction == RouteDirection.DROPOFF ? 1 : 0;
        for (int i = 0; i < stopOrder.size(); i++) {
            LatLng point = studentPoints.get(stopOrder.get(i));
            long eta = Math.round(cumulative.get(i + stopWaypointOffset));
            plan.addStop(stopOrder.get(i), point.lat(), point.lng(), eta);
        }

        return routePlanRepository.save(plan);
    }

    private LatLng requireDepot(Bus bus) {
        if (bus.getTenant().getLat() == null || bus.getTenant().getLng() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "학원 위치(depot)가 설정되지 않았습니다");
        }
        return new LatLng(bus.getTenant().getLat(), bus.getTenant().getLng());
    }

    private Map<Long, LatLng> resolveStudentPoints(List<Student> roster, RouteDirection direction) {
        Map<Long, LatLng> points = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (Student student : roster) {
            LatLng point = direction == RouteDirection.PICKUP
                    ? boardingPoint(student)
                    : dropoffPoint(student);
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

    private LatLng boardingPoint(Student student) {
        if (student.getBoardingStop() == null) {
            return null;
        }
        return new LatLng(student.getBoardingStop().getLat(), student.getBoardingStop().getLng());
    }

    private LatLng dropoffPoint(Student student) {
        if (student.getDropoffLat() == null || student.getDropoffLng() == null) {
            return null;
        }
        return new LatLng(student.getDropoffLat(), student.getDropoffLng());
    }

    /** DROPOFF: [depot, ...순서대로]. PICKUP: [...역순, depot] — 대칭거리에서 depot고정 최적경로를 뒤집어도 총거리는 동일하다는 성질 이용. */
    private List<LatLng> buildWaypoints(LatLng depot, List<Long> stopOrder, Map<Long, LatLng> points,
                                        RouteDirection direction) {
        List<LatLng> waypoints = new ArrayList<>();
        if (direction == RouteDirection.DROPOFF) {
            waypoints.add(depot);
            for (Long id : stopOrder) {
                waypoints.add(points.get(id));
            }
        } else {
            for (Long id : stopOrder) {
                waypoints.add(points.get(id));
            }
            waypoints.add(depot);
        }
        return waypoints;
    }

    private List<Long> reversed(List<Long> order) {
        List<Long> copy = new ArrayList<>(order);
        java.util.Collections.reverse(copy);
        return copy;
    }

    private List<Double> cumulativeSeconds(List<Double> legDurationsS) {
        List<Double> cumulative = new ArrayList<>(legDurationsS.size() + 1);
        cumulative.add(0.0);
        double running = 0;
        for (double leg : legDurationsS) {
            running += leg;
            cumulative.add(running);
        }
        return cumulative;
    }

    /** waypoint 가 상한을 넘으면 경계를 공유하는 구간으로 나눠 여러 번 호출 후 병합한다. */
    private RouteResult resolveRoute(List<LatLng> waypoints) {
        if (waypoints.size() <= maxWaypoints) {
            return mapRouteClient.route(waypoints);
        }
        double totalDistance = 0;
        double totalDuration = 0;
        List<Double> legDurations = new ArrayList<>();
        StringBuilder polylineBuilder = new StringBuilder("[");
        boolean first = true;
        int start = 0;
        while (start < waypoints.size() - 1) {
            int end = Math.min(start + maxWaypoints - 1, waypoints.size() - 1);
            RouteResult chunkResult = mapRouteClient.route(waypoints.subList(start, end + 1));
            totalDistance += chunkResult.totalDistanceM();
            totalDuration += chunkResult.totalDurationS();
            legDurations.addAll(chunkResult.legDurationsS());
            String p = chunkResult.polyline();
            if (!first) {
                polylineBuilder.append(',');
            }
            if (p != null && p.length() >= 2) {
                polylineBuilder.append(p, 1, p.length() - 1);
            }
            first = false;
            start = end;
        }
        polylineBuilder.append(']');
        return new RouteResult(totalDistance, totalDuration, legDurations, polylineBuilder.toString());
    }
}
