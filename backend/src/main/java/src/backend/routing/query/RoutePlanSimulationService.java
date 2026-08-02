package src.backend.routing.query;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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
import src.backend.routing.domain.PlannedRoute;
import src.backend.routing.domain.RouteDirection;
import src.backend.routing.dto.RoutePlanComparison;
import src.backend.routing.dto.RoutePlanComparison.Delta;
import src.backend.routing.dto.RoutePlanComparison.Snapshot;
import src.backend.routing.dto.RoutePlanComparison.StopView;
import src.backend.routing.dto.SimulateRoutePlanRequest;
import src.backend.routing.dto.StudentOverride;
import src.backend.routing.engine.RoutePlanComputer;
import src.backend.routing.entity.RoutePlan;
import src.backend.routing.entity.RoutePlanStop;
import src.backend.routing.repository.spec.RoutePlanRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.spec.StudentRepository;

/**
 * 배차 변경안 시뮬레이션. baseline(저장된 최신 계획) vs candidate(변경안 재계산)를 비교한다.
 * 어떤 행도 저장하지 않는다(I-3). 요구 8과 P2 가 공유한다(D-I).
 */
@Service
public class RoutePlanSimulationService {

    /** 좌표·라벨을 전부 해석하지 못했을 때의 표시 문자열 — 화면이 빈 칸 대신 이걸 보여준다. */
    private static final String UNRESOLVED_LABEL = "좌표 지정";

    private final RoutePlanRepository routePlanRepository;
    private final BusRepository busRepository;
    private final StudentRepository studentRepository;
    private final AttendanceQueryService attendanceQueryService;
    private final RoutePlanComputer computer;

    public RoutePlanSimulationService(RoutePlanRepository routePlanRepository,
                                      BusRepository busRepository,
                                      StudentRepository studentRepository,
                                      AttendanceQueryService attendanceQueryService,
                                      RoutePlanComputer computer) {
        this.routePlanRepository = routePlanRepository;
        this.busRepository = busRepository;
        this.studentRepository = studentRepository;
        this.attendanceQueryService = attendanceQueryService;
        this.computer = computer;
    }

    /** apply 가 재계산 없이 그대로 저장하도록 계산 재료를 함께 들고 있는다(경로 API 호출 2배 방지). */
    public record SimulationOutcome(Bus bus, LocalDate serviceDate, RouteDirection direction,
                                    List<Long> candidateStudentIds, PlannedRoute candidate,
                                    RoutePlanComparison comparison) {}

    // ① REST 진입점(BE-5) — 인가를 거친 뒤 공통 코어로 합류한다.
    @Transactional(readOnly = true)
    public RoutePlanComparison simulate(AuthUser admin, SimulateRoutePlanRequest req) {
        return computeCandidate(admin, req).comparison();
    }

    /**
     * ② 내부 호출용(BE-10) — AuthUser 를 받지 않는다. 학부모 요청으로 트리거되므로
     * 관리자 인가를 태우면 막힌다. 호출자가 "그 학생의 보호자인가" 를 이미 검증했다는 전제다.
     * ⚠️ 이 메서드를 컨트롤러에 직접 노출하지 마라 — 인가 없이 남의 버스 노선을 계산해볼 수 있게 된다.
     */
    @Transactional(readOnly = true)
    public RoutePlanComparison compare(Long busId, RouteDirection direction, LocalDate serviceDate,
                                       List<StudentOverride> overrides) {
        Bus bus = busRepository.findById(busId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "버스를 찾을 수 없습니다"));
        return computeCore(bus, direction, serviceDate, overrides).comparison();
    }

    @Transactional(readOnly = true)   // ⚠️ 저장 금지(I-3) — 이 애너테이션을 지우지 않는다
    public SimulationOutcome computeCandidate(AuthUser admin, SimulateRoutePlanRequest req) {
        Bus bus = busRepository.findById(req.busId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "버스를 찾을 수 없습니다"));
        TenantGuard.resolveTenantId(admin, bus.getTenant().getId());
        return computeCore(bus, req.direction(), req.serviceDate(), req.overrides());
    }

    /** ①·② 가 합류하는 공통 코어. 인가는 하지 않는다 — 호출자 책임이다(D-I: 계산 경로는 하나여야 한다). */
    private SimulationOutcome computeCore(Bus bus, RouteDirection direction, LocalDate reqDate,
                                          List<StudentOverride> overrides) {
        LocalDate serviceDate = reqDate != null ? reqDate : LocalDate.now();

        Map<Long, LatLng> points = new LinkedHashMap<>();   // 삽입 순서 유지
        for (Student s : attendanceQueryService.getActiveRoster(bus.getId(), serviceDate)) {
            LatLng p = RoutePlanComputer.pointOf(s, direction);
            if (p != null) {
                points.put(s.getId(), p);   // 좌표 없는 학생은 조용히 제외 — 한 명 때문에 비교 화면이 죽으면 안 된다
            }
        }
        applyOverrides(points, bus, overrides);
        if (points.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "변경안 적용 후 정차가 0개입니다");
        }
        PlannedRoute candidate = computer.computeRoute(bus, direction, points);
        RoutePlan baseline = routePlanRepository
                .findTopByBusIdAndDirectionOrderByVersionDesc(bus.getId(), direction).orElse(null);

        return new SimulationOutcome(bus, serviceDate, direction, List.copyOf(points.keySet()),
                candidate, toComparison(baseline, candidate, direction, bus.getSeatCapacity()));
    }

    private void applyOverrides(Map<Long, LatLng> points, Bus bus, List<StudentOverride> overrides) {
        if (overrides == null) {
            return;
        }
        for (StudentOverride o : overrides) {
            if (o.action() == StudentOverride.OverrideAction.REMOVE) {
                points.remove(o.studentId());
                continue;
            }
            if (o.lat() == null || o.lng() == null) {
                throw new BusinessException(ErrorCode.INVALID_INPUT,
                        o.action() + " 변경안에는 좌표가 필요합니다: studentId=" + o.studentId());
            }
            Student student = studentRepository.findById(o.studentId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                            "학생을 찾을 수 없습니다: " + o.studentId()));
            if (!student.getTenant().getId().equals(bus.getTenant().getId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN);   // 타 학원 학생 끌어오기 차단
            }
            // I-9: override 는 getActiveRoster(활성 필터)를 거치지 않고 학생을 직접 주입하는 경로다.
            // 여기서 막지 않으면 퇴원 학생이 비교 결과에 되살아나고, 채택(apply)까지 가면
            // 배포된 노선의 정차로 저장돼 그 보호자에게 근접·미승차 알림이 나간다.
            // REMOVE 는 위에서 이미 빠져나갔다 — 빼는 것은 비활성이어도 언제나 허용한다.
            if (!student.isActive()) {
                throw new BusinessException(ErrorCode.INVALID_INPUT,
                        "비활성(퇴원) 학생은 배차에 넣을 수 없습니다: studentId=" + o.studentId());
            }
            points.put(o.studentId(), new LatLng(o.lat(), o.lng()));
        }
    }

    // ⚠️ 이름이 `compare` 가 아니다 — 공개 API `compare(Long, RouteDirection, LocalDate, List<StudentOverride>)`
    //    와 충돌하기 때문이다. 이쪽은 "이미 계산된 둘을 비교 DTO 로 옮기는" 순수 변환이다.
    private RoutePlanComparison toComparison(RoutePlan baseline, PlannedRoute candidate,
                                             RouteDirection direction, int seatCapacity) {
        Map<Long, Student> byId = loadStudents(baseline, candidate);

        List<StopView> candidateStops = new ArrayList<>(candidate.stopStudentIds().size());
        for (int i = 0; i < candidate.stopStudentIds().size(); i++) {
            Long sid = candidate.stopStudentIds().get(i);
            LatLng point = candidate.stopPoints().get(i);
            candidateStops.add(new StopView(i + 1, sid, nameOf(byId.get(sid)), labelOf(byId.get(sid), direction),
                    point.lat(), point.lng(), candidate.stopEtaSeconds().get(i)));
        }
        Snapshot candidateSnap = new Snapshot(null, null, candidate.totalDistanceM(), candidate.totalDurationS(),
                candidateStops, candidate.polyline());   // id·version 은 미저장이라 null

        if (baseline == null) {
            // baseline 없으면 delta 도 null. seatCapacity 는 baseline 유무와 무관하게 항상 채운다 —
            // 첫 계획이어도 화면은 "정원 초과" 를 판단해야 한다(OVERVIEW §4.4).
            return new RoutePlanComparison(null, candidateSnap, null, seatCapacity);
        }

        List<StopView> baselineStops = new ArrayList<>(baseline.getStops().size());
        for (RoutePlanStop stop : baseline.getStops()) {
            Student s = byId.get(stop.getStudentId());
            baselineStops.add(new StopView(stop.getSeq(), stop.getStudentId(), nameOf(s), labelOf(s, direction),
                    stop.getLat(), stop.getLng(), stop.getEtaSeconds()));
        }
        Snapshot baselineSnap = new Snapshot(baseline.getId(), baseline.getVersion(),
                baseline.getTotalDistanceM(), baseline.getTotalDurationS(), baselineStops, baseline.getPolyline());

        Delta delta = new Delta(
                candidate.totalDistanceM() - baseline.getTotalDistanceM(),   // 부호는 항상 candidate − baseline
                candidate.totalDurationS() - baseline.getTotalDurationS(),
                candidateStops.size() - baseline.getStops().size());
        return new RoutePlanComparison(baselineSnap, candidateSnap, delta, seatCapacity);
    }

    /** candidate·baseline 정차의 studentId 합집합을 한 번에 읽는다(정차마다 조회하면 N+1 이 된다). */
    private Map<Long, Student> loadStudents(RoutePlan baseline, PlannedRoute candidate) {
        Set<Long> ids = new LinkedHashSet<>(candidate.stopStudentIds());
        if (baseline != null) {
            baseline.getStops().forEach(stop -> ids.add(stop.getStudentId()));
        }
        return studentRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity(), (a, b) -> a));
    }

    private String nameOf(Student s) {
        return s == null ? "알 수 없음" : s.getName();
    }

    /** PICKUP=pickupAddress→boardingStop.name, DROPOFF=dropoffAddress, 없으면 "좌표 지정"(좌표 우선순위 D-K 와 같은 순서). */
    private String labelOf(Student s, RouteDirection direction) {
        if (s == null) {
            return UNRESOLVED_LABEL;
        }
        if (direction == RouteDirection.PICKUP) {
            if (hasText(s.getPickupAddress())) {
                return s.getPickupAddress();
            }
            return s.getBoardingStop() != null && hasText(s.getBoardingStop().getName())
                    ? s.getBoardingStop().getName() : UNRESOLVED_LABEL;
        }
        return hasText(s.getDropoffAddress()) ? s.getDropoffAddress() : UNRESOLVED_LABEL;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
