package src.backend.request.query;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.security.access.AcademyScope;
import src.backend.request.dto.AffectedStudentResponse;
import src.backend.request.dto.ApprovalCapacityResponse;
import src.backend.request.dto.ApprovalDetailResponse;
import src.backend.request.dto.ApprovalListResponse;
import src.backend.request.dto.ApprovalSummaryResponse;
import src.backend.request.dto.PreviewStopResponse;
import src.backend.request.dto.RoutePreviewResponse;
import src.backend.request.dto.StopRefResponse;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestStatus;
import src.backend.request.entity.ChangeRequestType;
import src.backend.request.preview.spec.ApprovalPreview;
import src.backend.request.preview.spec.ApprovalPreviewCache;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.domain.GeoPoint;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.Route;
import src.backend.routing.entity.RouteStop;
import src.backend.routing.entity.RouteVersion;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.entity.RunStop;
import src.backend.routing.map.spec.CallerPolicy;
import src.backend.routing.pipeline.ComputationPolicy;
import src.backend.routing.pipeline.DailyRoster;
import src.backend.routing.pipeline.RouteComputation;
import src.backend.routing.pipeline.RouteComputationInput;
import src.backend.routing.pipeline.RouteComputationPipeline;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.domain.RunConfirmationFingerprint;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Stop;
import src.backend.student.entity.Student;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 승인 대기 목록·상세 조회(API_SPEC §5.5) — 목록은 저장된 값만 집계하고 재최적화를 실행하지 않는다.
 * 상세는 이 시점에 재최적화를 <b>1회</b> 실행해 전/후 대조를 산출한다.
 *
 * <p>⚠ <b>목록·상세를 가른 이유</b>(API_SPEC §5.5) — 재최적화는 계산 비용이 크다. 목록 항목마다
 * 계산하면 대기 건이 N개일 때 한 번의 목록 조회에 N회 최적화가 동기로 실행되어 응답이 지연된다.
 * 승인 화면은 한 건씩 열므로 목록은 요약만, 대조는 상세에서 1건만 계산한다(ARCHITECTURE §8.4).
 *
 * <p>같은 계산 코드를 확정 배치({@link src.backend.run.command.RunConfirmationService})와 공유하되
 * 실행 정책만 다르다(ARCHITECTURE §8.3) — 관리자가 화면 앞에서 기다리므로 배치보다 짧은 타임아웃의
 * {@link CallerPolicy#ON_DEMAND} 를 쓴다.
 */
@Service
@RequiredArgsConstructor
public class ApprovalQueryService {

    /**
     * 온디맨드 지도 API 1회 요청의 상한 — 확정 배치({@code RunConfirmationService.MAP_TIMEOUT},
     * 15초)는 사용자가 대기하지 않아 길게 둔 값이라 여기 그대로 쓰면 승인 화면이 수십 초 멈춘다
     * ({@link ComputationPolicy} javadoc). 5초는 이 태스크가 처음 도입하는 값이라 참조할 기존
     * 상수가 없다.
     */
    private static final Duration ON_DEMAND_MAP_TIMEOUT = Duration.ofSeconds(5);

    private final ChangeRequestRepository changeRequestRepository;

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final StudentRepository studentRepository;

    private final StopRepository stopRepository;

    private final RunRiderRepository runRiderRepository;

    private final AcademyRepository academyRepository;

    private final RouteRepository routeRepository;

    private final RouteStopRepository routeStopRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RouteVersionRepository routeVersionRepository;

    private final RunStopRepository runStopRepository;

    private final RouteComputationPipeline pipeline;

    private final ApprovalPreviewCache previewCache;

    /** 승인 대기 목록(§5.5 목록) — 재최적화를 실행하지 않는다. 저장된 값과 단순 집계만 반환한다. */
    public ApprovalListResponse list(AuthUser requester, ChangeRequestStatus status) {
        Long academyId = requester.academyId();
        List<ChangeRequest> requests = changeRequestRepository
                .findAllByAcademyIdAndStatusOrderByRequestedAtAsc(academyId, status);
        List<ApprovalSummaryResponse> items = requests.stream()
                .map(cr -> toSummary(cr, academyId))
                .toList();
        long pendingCount = changeRequestRepository.countByAcademyIdAndStatus(academyId, ChangeRequestStatus.PENDING);
        return ApprovalListResponse.of(items, pendingCount);
    }

    /**
     * 승인 대기 상세(§5.5 상세) — 이 시점에 재최적화를 1회 실행한다. 입력(명단·승하차지)이 그대로인 채
     * 다시 부르면 캐시가 같은 {@code preview_token} 을 돌려주고 계산은 다시 돌지 않는다.
     *
     * @throws BusinessException {@code 404 APPROVAL_NOT_FOUND}(대상 없음) ·
     *                            {@code 403 ACADEMY_SCOPE_VIOLATION}(다른 학원 소속)
     */
    public ApprovalDetailResponse detail(AuthUser requester, Long approvalId) {
        ChangeRequest cr = changeRequestRepository.findById(approvalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.APPROVAL_NOT_FOUND));
        AcademyScope.assertAccessible(requester, cr.getAcademyId());
        Long academyId = cr.getAcademyId();

        Run run = runRepository.findById(cr.getRunId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        Weekday weekday = weekdayOf(run.getServiceDate());
        List<RunRider> riders = runRiderRepository.findAllByRunIdAndAcademyId(run.getId(), academyId);
        ApprovalSummaryResponse summary = toSummary(cr, run, riders, academyId);

        Academy academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
        if (!academy.hasCoordinates()) {
            throw new BusinessException(ErrorCode.ACADEMY_COORDINATES_MISSING);
        }
        Route route = routeRepository
                .findByAcademyIdAndBusIdAndWeekdayAndDirection(academyId, run.getBusId(), weekday, run.getDirection())
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTE_NOT_CONFIGURED_FOR_RUN));
        List<RouteStop> routeStops = routeStopRepository.findAllOrderedByRouteIdAndAcademyId(route.getId(), academyId);
        OriginDestination originDestination = originDestinationOf(academy, routeStops, run.getDirection(), academyId);

        DailyRoster roster = candidateRosterOf(cr, run, weekday, riders);
        String fingerprint = RunConfirmationFingerprint.of(academyId, weekday, run.getDirection(),
                run.getDepartTime(), originDestination.origin(), originDestination.destination(),
                roster.stopOverrides(), List.of());

        PreviewResult previewResult = resolvePreview(approvalId, fingerprint, roster, originDestination, run);
        RouteComputation computation = previewResult.preview().computation();

        ConfirmedRoute confirmedRoute = confirmedRouteRepository.findById(run.getId())
                .orElseThrow(() -> new IllegalStateException("확정 노선이 없다 — runId=" + run.getId()));
        Long currentVersionId = confirmedRoute.getCurrentVersionId();
        RouteVersion currentVersion = routeVersionRepository.findById(currentVersionId)
                .orElseThrow(() -> new IllegalStateException("노선 버전이 없다 — versionId=" + currentVersionId));
        List<RunStop> beforeRunStops = runStopRepository
                .findAllByRouteVersionIdAndAcademyIdOrderBySeq(currentVersionId, academyId);

        Map<Long, Stop> stopsById = stopsByIdOf(unionOfStopIds(beforeRunStops, computation), academyId);
        List<PreviewStopResponse> stopsBefore = toPreviewStopsFromRunStops(beforeRunStops, stopsById);
        List<PreviewStopResponse> stopsAfter = toPreviewStopsFromComputation(computation, stopsById);
        Map<Long, Integer> beforeSeq = seqMapOfRunStops(beforeRunStops);
        Map<Long, Integer> afterSeq = seqMapOfComputation(computation);

        RoutePreviewResponse routePreview = RoutePreviewResponse.of(stopsBefore, stopsAfter,
                reorderedOf(beforeSeq, afterSeq, stopsById), removedOf(beforeSeq, afterSeq, stopsById));

        Bus bus = busRepository.findByIdAndAcademyId(run.getBusId(), academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUS_NOT_FOUND));
        ApprovalCapacityResponse capacity = new ApprovalCapacityResponse(bus.getStudentCapacity(),
                roster.studentIds().size());

        List<AffectedStudentResponse> affectedStudents = affectedStudentsOf(cr.getStudentId(), summary.studentName(),
                riders, beforeSeq, afterSeq);

        return ApprovalDetailResponse.of(summary, routePreview, lastEtaOf(stopsBefore), lastEtaOf(stopsAfter),
                currentVersion.getEstDistanceKm(), computation.estDistanceKm(), affectedStudents, capacity,
                previewResult.preview().token(), previewResult.stale());
    }

    /** 요약 1건 — 목록(§5.5 목록)이 회차·명단을 매번 새로 읽어야 할 때 쓰는 얕은 진입점. */
    private ApprovalSummaryResponse toSummary(ChangeRequest cr, Long academyId) {
        Run run = runRepository.findById(cr.getRunId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        List<RunRider> riders = runRiderRepository.findAllByRunIdAndAcademyId(run.getId(), academyId);
        return toSummary(cr, run, riders, academyId);
    }

    /**
     * 요약 1건 — 상세(§5.5 상세)가 이미 읽어 둔 회차·명단을 그대로 넘겨 재조회를 피할 때 쓴다.
     *
     * <p>{@code remainingRiders}·{@code willRemoveStop} 은 이 학생을 뺀 뒤 같은 승하차지에 남는
     * {@code run_rider} 행 수만 세면 나온다 — 재최적화 없이 저장된 값의 집계만으로 답한다(§5.5 목록).
     */
    private ApprovalSummaryResponse toSummary(ChangeRequest cr, Run run, List<RunRider> riders, Long academyId) {
        Bus bus = busRepository.findByIdAndAcademyId(run.getBusId(), academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUS_NOT_FOUND));
        Student student = studentRepository.findById(cr.getStudentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));
        RunRider mine = riders.stream()
                .filter(r -> r.getStudentId().equals(cr.getStudentId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "승인 대기 학생이 회차 명단에 없다 — runId=" + run.getId() + ", studentId=" + cr.getStudentId()));
        Stop stop = stopRepository.findAllByAcademyIdAndIdIn(academyId, List.of(mine.getStopId())).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("승하차지가 없다 — stopId=" + mine.getStopId()));
        long remaining = riders.stream()
                .filter(r -> !r.getStudentId().equals(cr.getStudentId()))
                .filter(r -> mine.getStopId().equals(r.getStopId()))
                .filter(r -> r.getStatus() != RiderStatus.ABSENT)
                .count();
        return ApprovalSummaryResponse.of(cr, student.getName(), bus.getBusNo(), run.getDirection(),
                cr.getDeadlineAt(), stop.getName(), (int) remaining, remaining == 0);
    }

    /**
     * 이 승인 건을 반영했다고 가정한 명단 — 재최적화 "후" 를 계산하는 입력이다.
     *
     * <p>기준선은 {@code run_rider}(확정 배치 산출물에 그동안 승인된 변경까지 반영된 <b>지금</b> 상태)
     * 다 — {@code weekly_address} 를 다시 읽지 않는 이유는 이 회차가 이미 확정돼(Ruling 198) 그
     * 산출물이 이 회차의 진짜 명단이기 때문이다. {@code ABSENT}(결석 처리됨)는 오늘 타지 않는 학생이라
     * 뺀다. 이 변경 요청의 대상 학생만 {@link ChangeRequestType} 에 따라 다르게 가정한다 — 취소는
     * 명단에서 빼고, 승하차지 변경은 새 승하차지로 덮어쓴다.
     */
    private DailyRoster candidateRosterOf(ChangeRequest cr, Run run, Weekday weekday, List<RunRider> riders) {
        List<Long> studentIds = new ArrayList<>();
        Map<Long, Long> stopOverrides = new LinkedHashMap<>();
        for (RunRider rider : riders) {
            if (rider.getStatus() == RiderStatus.ABSENT) {
                continue;
            }
            boolean isTarget = rider.getStudentId().equals(cr.getStudentId());
            if (isTarget && cr.getType() == ChangeRequestType.CANCEL) {
                continue;
            }
            Long stopId = (isTarget && cr.getType() == ChangeRequestType.RELOCATE) ? cr.getNewStopId()
                    : rider.getStopId();
            studentIds.add(rider.getStudentId());
            stopOverrides.put(rider.getStudentId(), stopId);
        }
        return new DailyRoster(run.getAcademyId(), weekday, run.getDirection(), studentIds, stopOverrides);
    }

    /**
     * 출발지·도착지 도출 — {@code RunConfirmationService} 와 같은 규칙이다(Ruling 190): 등원은
     * 첫 승차지→학원, 하원은 학원→마지막 하차지. 온디맨드 미리보기도 배치와 같은 기준점을 써야
     * 전/후 대조가 "같은 구간을 비교한 것" 이 된다 — 기준점이 갈리면 대조 자체가 무의미해진다.
     */
    private OriginDestination originDestinationOf(Academy academy, List<RouteStop> routeStops, Direction direction,
            Long academyId) {
        if (routeStops.isEmpty()) {
            throw new BusinessException(ErrorCode.ROUTE_NOT_CONFIGURED_FOR_RUN);
        }
        List<Long> stopIds = routeStops.stream().map(RouteStop::getStopId).toList();
        Map<Long, Stop> stopsById = stopRepository.findAllByIdInAndAcademyId(stopIds, academyId).stream()
                .collect(Collectors.toMap(Stop::getId, stop -> stop));
        Stop firstStop = stopsById.get(routeStops.get(0).getStopId());
        Stop lastStop = stopsById.get(routeStops.get(routeStops.size() - 1).getStopId());
        if (firstStop == null || lastStop == null) {
            throw new BusinessException(ErrorCode.ROUTE_NOT_CONFIGURED_FOR_RUN);
        }
        GeoPoint academyPoint = new GeoPoint(academy.getLat(), academy.getLng());
        if (direction == Direction.TO_ACADEMY) {
            return new OriginDestination(new GeoPoint(firstStop.getLat(), firstStop.getLng()), academyPoint);
        }
        return new OriginDestination(academyPoint, new GeoPoint(lastStop.getLat(), lastStop.getLng()));
    }

    /**
     * 캐시 조회·계산을 한곳에 모은다 — 지문이 일치하는 캐시가 있으면 파이프라인을 <b>부르지 않고</b>
     * 같은 토큰을 돌려준다(호출 수 검증의 본체, 목표 12). 지문이 어긋나거나 캐시가 없으면 새로
     * 계산하고, <b>기존 값을 대체하는 경우에만</b> {@code stale=true} 를 돌려준다 — 첫 조회는 대체할
     * 낡은 값 자체가 없으므로 낡았다고 말할 수 없다.
     */
    private PreviewResult resolvePreview(Long approvalId, String fingerprint, DailyRoster roster,
            OriginDestination originDestination, Run run) {
        Optional<ApprovalPreview> cached = previewCache.find(approvalId);
        if (cached.isPresent() && cached.get().fingerprint().equals(fingerprint)) {
            return new PreviewResult(cached.get(), false);
        }
        ComputationPolicy policy = new ComputationPolicy(ON_DEMAND_MAP_TIMEOUT, CallerPolicy.ON_DEMAND,
                RouteVersionSource.APPROVAL);
        RouteComputationInput input = new RouteComputationInput(roster, originDestination.origin(),
                originDestination.destination(), List.of(), run.getDepartTime(), policy);
        RouteComputation computation = pipeline.compute(input);
        ApprovalPreview fresh = new ApprovalPreview(UUID.randomUUID().toString(), fingerprint, computation);
        previewCache.put(approvalId, fresh);
        boolean stale = cached.isPresent();
        return new PreviewResult(fresh, stale);
    }

    private static Set<Long> unionOfStopIds(List<RunStop> beforeRunStops, RouteComputation computation) {
        Set<Long> ids = new LinkedHashSet<>();
        beforeRunStops.forEach(rs -> {
            if (rs.getStopId() != null) {
                ids.add(rs.getStopId());
            }
        });
        computation.stops().forEach(os -> {
            if (os.stopId() != null) {
                ids.add(os.stopId());
            }
        });
        return ids;
    }

    private Map<Long, Stop> stopsByIdOf(Set<Long> stopIds, Long academyId) {
        if (stopIds.isEmpty()) {
            return Map.of();
        }
        return stopRepository.findAllByAcademyIdAndIdIn(academyId, stopIds).stream()
                .collect(Collectors.toMap(Stop::getId, stop -> stop));
    }

    private static List<PreviewStopResponse> toPreviewStopsFromRunStops(List<RunStop> runStops,
            Map<Long, Stop> stopsById) {
        return runStops.stream()
                .map(rs -> new PreviewStopResponse(rs.getSeq(), nameOf(rs.getStopId(), stopsById), rs.getEta()))
                .toList();
    }

    private static List<PreviewStopResponse> toPreviewStopsFromComputation(RouteComputation computation,
            Map<Long, Stop> stopsById) {
        List<OrderedStop> ordered = computation.stops();
        List<OffsetDateTime> etas = computation.etas();
        List<PreviewStopResponse> stops = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            OrderedStop stop = ordered.get(i);
            stops.add(new PreviewStopResponse(stop.seq(), nameOf(stop.stopId(), stopsById), etas.get(i)));
        }
        return stops;
    }

    private static String nameOf(Long stopId, Map<Long, Stop> stopsById) {
        if (stopId == null) {
            return null;
        }
        Stop stop = stopsById.get(stopId);
        return stop != null ? stop.getName() : null;
    }

    private static Map<Long, Integer> seqMapOfRunStops(List<RunStop> runStops) {
        Map<Long, Integer> map = new LinkedHashMap<>();
        for (RunStop rs : runStops) {
            if (rs.getStopId() != null) {
                map.put(rs.getStopId(), rs.getSeq());
            }
        }
        return map;
    }

    private static Map<Long, Integer> seqMapOfComputation(RouteComputation computation) {
        Map<Long, Integer> map = new LinkedHashMap<>();
        for (OrderedStop stop : computation.stops()) {
            if (stop.stopId() != null) {
                map.put(stop.stopId(), stop.seq());
            }
        }
        return map;
    }

    /** 전/후 모두에 있지만 순번이 달라진 승하차지(§5.5 상세 {@code reordered[]}). */
    private static List<StopRefResponse> reorderedOf(Map<Long, Integer> beforeSeq, Map<Long, Integer> afterSeq,
            Map<Long, Stop> stopsById) {
        return afterSeq.entrySet().stream()
                .filter(entry -> beforeSeq.containsKey(entry.getKey())
                        && !beforeSeq.get(entry.getKey()).equals(entry.getValue()))
                .map(entry -> stopRefOf(entry.getKey(), stopsById))
                .toList();
    }

    /** 전에는 있었으나 후에는 없는 승하차지(§5.5 상세 {@code removed[]}) — 잔여 인원이 0이 된 경우다. */
    private static List<StopRefResponse> removedOf(Map<Long, Integer> beforeSeq, Map<Long, Integer> afterSeq,
            Map<Long, Stop> stopsById) {
        return beforeSeq.keySet().stream()
                .filter(stopId -> !afterSeq.containsKey(stopId))
                .map(stopId -> stopRefOf(stopId, stopsById))
                .toList();
    }

    private static StopRefResponse stopRefOf(Long stopId, Map<Long, Stop> stopsById) {
        Stop stop = stopsById.get(stopId);
        return new StopRefResponse(stopId, stop != null ? stop.getName() : null);
    }

    /**
     * 영향 학생(§5.5 상세 {@code affected_students[]}) — 이 승인의 대상 학생 자신과, 순번이 바뀌거나
     * 노선에서 빠진 승하차지를 타는 다른 학생들이다. 도착 예정 시각은 사실 전 구간이 조금씩 밀리지만,
     * 그 정도로 "영향" 을 넓히면 승인 화면이 전원을 영향 학생으로 표시해 이 필드가 무의미해진다 —
     * 정차 위치·순서가 실제로 바뀐 학생만 추린다.
     */
    private List<AffectedStudentResponse> affectedStudentsOf(Long targetStudentId, String targetStudentName,
            List<RunRider> riders, Map<Long, Integer> beforeSeq, Map<Long, Integer> afterSeq) {
        Set<Long> changedStopIds = changedStopIdsOf(beforeSeq, afterSeq);
        Map<Long, String> byId = new LinkedHashMap<>();
        byId.put(targetStudentId, targetStudentName);
        for (RunRider rider : riders) {
            if (rider.getStatus() == RiderStatus.ABSENT || byId.containsKey(rider.getStudentId())) {
                continue;
            }
            if (changedStopIds.contains(rider.getStopId())) {
                String name = studentRepository.findById(rider.getStudentId()).map(Student::getName).orElse(null);
                byId.put(rider.getStudentId(), name);
            }
        }
        return byId.entrySet().stream()
                .map(entry -> new AffectedStudentResponse(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static Set<Long> changedStopIdsOf(Map<Long, Integer> beforeSeq, Map<Long, Integer> afterSeq) {
        Set<Long> changed = new LinkedHashSet<>();
        afterSeq.forEach((stopId, seq) -> {
            if (!seq.equals(beforeSeq.get(stopId))) {
                changed.add(stopId);
            }
        });
        beforeSeq.keySet().stream().filter(stopId -> !afterSeq.containsKey(stopId)).forEach(changed::add);
        return changed;
    }

    private static OffsetDateTime lastEtaOf(List<PreviewStopResponse> stops) {
        return stops.isEmpty() ? null : stops.get(stops.size() - 1).eta();
    }

    /**
     * 그 날짜의 요일 — {@code RunConfirmationService.weekdayOf} 와 같은 계산(중복 헬퍼 관례, 그
     * 클래스 javadoc 참고). {@code LocalDate} 자체가 요일을 들고 있으므로 시계를 보지 않는다.
     */
    private Weekday weekdayOf(LocalDate serviceDate) {
        return Weekday.valueOf(serviceDate.getDayOfWeek().name().substring(0, 3).toUpperCase(Locale.ROOT));
    }

    /** 노선 계산 1회의 출발·도착 기준점 쌍 — {@link RouteComputationInput} 조립 전 임시로 들고 있는다. */
    private record OriginDestination(GeoPoint origin, GeoPoint destination) {
    }

    /** 캐시 조회·계산 결과 — {@code stale} 은 기존 값을 대체한 경우에만 참이다. */
    private record PreviewResult(ApprovalPreview preview, boolean stale) {
    }
}
