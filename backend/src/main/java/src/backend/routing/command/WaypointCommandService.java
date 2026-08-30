package src.backend.routing.command;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.request.domain.ChangeWindow;
import src.backend.request.domain.ChangeWindowPolicy;
import src.backend.request.dto.PreviewStopResponse;
import src.backend.request.dto.RoutePreviewResponse;
import src.backend.request.preview.ApprovalPreviewResolver;
import src.backend.request.preview.ApprovalPreviewResolver.OriginDestination;
import src.backend.request.query.RoutePreviewAssembler;
import src.backend.routing.domain.GeoPoint;
import src.backend.routing.dto.WaypointRequest;
import src.backend.routing.dto.WaypointResponse;
import src.backend.routing.engine.spec.FixedStop;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.Route;
import src.backend.routing.entity.RouteStop;
import src.backend.routing.entity.RouteVersion;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.entity.RunStop;
import src.backend.routing.entity.Waypoint;
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
import src.backend.routing.repository.WaypointRepository;
import src.backend.run.domain.RunConfirmationFingerprint;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.command.AddressVerification;
import src.backend.student.entity.Stop;
import src.backend.student.geocoding.spec.GeocodedPoint;

/**
 * 관계자의 강제 경유 지점 지정·제거 미리보기/배포 오케스트레이터(RTE-10, API_SPEC §5.15).
 *
 * <p><b>{@code @Transactional} 이 없는 것이 이 클래스의 요점이다</b> — {@link RouteComputationPipeline#compute}
 * 가 외부 지도 API 를 부른다(§7 규칙 16, {@code RunConfirmationService}·{@code ApprovalPreviewResolver}
 * 와 같은 근거). "구간 판정 → 좌표 확보 → 재최적화 계산" 은 여기서 트랜잭션 밖에 두고, {@code apply=true}
 * 일 때의 배포 저장만 {@link WaypointStore} 의 짧은 트랜잭션에 맡긴다.
 *
 * <p>"운행 시작 전까지 허용"(§5.15) 은 {@link ChangeWindow#CLOSED} 만 막는 판정이다 — ①②구간
 * (즉시·승인 필요)은 전부 허용하고 ③구간(운행 시작 후)만 막는다는 뜻이라, {@code ChangeRequestCommandService}
 * 와 같은 형태로 판정한다({@code ForcedAdditionCommandService} 처럼 ①구간 전용이 아니다).
 */
@Service
@RequiredArgsConstructor
public class WaypointCommandService {

    /** 온디맨드(관리자가 화면 앞에서 대기) 지도 API 타임아웃 — {@code ApprovalPreviewResolver} 와 같은 값. */
    private static final Duration ON_DEMAND_MAP_TIMEOUT = Duration.ofSeconds(5);

    private final RunRepository runRepository;
    private final AcademyRepository academyRepository;
    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final RunRiderRepository runRiderRepository;
    private final WaypointRepository waypointRepository;
    private final ConfirmedRouteRepository confirmedRouteRepository;
    private final RouteVersionRepository routeVersionRepository;
    private final RunStopRepository runStopRepository;
    private final ApprovalPreviewResolver previewResolver;
    private final RoutePreviewAssembler routePreviewAssembler;
    private final RouteComputationPipeline pipeline;
    private final AddressVerification addressVerification;
    private final WaypointStore waypointStore;
    private final Clock clock;

    /**
     * 새 경유 지점을 지정한다 — 항상 행을 먼저 저장해 {@code waypoint_id} 를 응답에 담는다.
     * {@code apply=false} 는 미리보기만 계산하고 여기서 멈춘다(확정 노선은 그대로다, 목표 11).
     *
     * <p>새 행을 만들기 전에 이 회차의 <b>미배포 행을 전부 지운다</b> — 관계자가 라벨·주소를 바꿔 가며
     * 미리보기를 여러 번 눌러 보는 것이 정상 흐름이라, 그대로 두면 배포되지 않는 고아 행이 호출마다
     * 쌓인다. 이미 배포된(applied=true) 행은 대상이 아니라 이 정리로 지워지지 않는다.
     */
    public WaypointResponse add(AuthUser requester, Long runId, WaypointRequest request) {
        Run run = loadRunInWindow(requester, runId);
        GeoPoint point = resolvePoint(request);

        waypointRepository.deleteAllUnappliedByRunIdAndAcademyId(run.getId(), run.getAcademyId());
        Waypoint waypoint = waypointRepository.save(Waypoint.forRun(run.getId(), request.label(), request.address(),
                point.lat(), point.lng(), request.note(), requester.accountId(), OffsetDateTime.now(clock)));

        RouteContext ctx = routeContextOf(run);
        List<FixedStop> fixedStops = new ArrayList<>(existingFixedStopsOf(ctx));
        fixedStops.add(new FixedStop(waypoint.getId(), point, ctx.beforeRunStops().size() + 1));

        return orchestrate(run, waypoint, ctx, fixedStops, request.apply(), requester.accountId(), false);
    }

    /**
     * 이미 배포된 경유 지점을 제거한다 — 미리보기 단계(아직 {@code apply=true} 로 배포되지 않은) 행은
     * 대상이 아니다({@code WaypointRepository.findAppliedByIdAndRunIdAndAcademyId} 가 그 행을 걸러
     * {@code 404 WAYPOINT_NOT_FOUND} 로 답한다 — 존재 여부를 응답에서 드러내지 않는 관례).
     */
    public WaypointResponse remove(AuthUser requester, Long runId, Long waypointId, boolean apply) {
        Run run = loadRunInWindow(requester, runId);
        Waypoint waypoint = waypointRepository
                .findAppliedByIdAndRunIdAndAcademyId(waypointId, run.getId(), run.getAcademyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.WAYPOINT_NOT_FOUND));

        RouteContext ctx = routeContextOf(run);
        List<FixedStop> fixedStops = existingFixedStopsOf(ctx).stream()
                .filter(fs -> fs.waypointId() != waypoint.getId())
                .toList();

        return orchestrate(run, waypoint, ctx, fixedStops, apply, requester.accountId(), true);
    }

    /** 회차를 학원으로 좁혀 읽고 구간을 판정한다 — ③구간(운행 시작 후)만 막는다(클래스 javadoc). */
    private Run loadRunInWindow(AuthUser requester, Long runId) {
        Run run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        ChangeWindow window = ChangeWindowPolicy.segmentOf(run, OffsetDateTime.now(clock));
        if (window == ChangeWindow.CLOSED) {
            throw new BusinessException(ErrorCode.CHANGE_WINDOW_CLOSED);
        }
        return run;
    }

    /** 좌표를 우선하고(재검증 호출을 늘리지 않는다), 없으면 주소를 검증한다. 둘 다 없으면 422. */
    private GeoPoint resolvePoint(WaypointRequest request) {
        if (request.lat() != null && request.lng() != null) {
            return new GeoPoint(request.lat(), request.lng());
        }
        if (request.address() == null || request.address().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        GeocodedPoint geocoded = addressVerification.verifySingle(request.address());
        return new GeoPoint(geocoded.lat(), geocoded.lng());
    }

    /** 재최적화에 필요한 노선·기준점·현재 배포본·명단을 한 번에 모은다 — add·remove 가 공유한다. */
    private RouteContext routeContextOf(Run run) {
        Long academyId = run.getAcademyId();
        Weekday weekday = weekdayOf(run.getServiceDate());

        Academy academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
        if (!academy.hasCoordinates()) {
            throw new BusinessException(ErrorCode.ACADEMY_COORDINATES_MISSING);
        }
        Route route = routeRepository
                .findByAcademyIdAndBusIdAndWeekdayAndDirection(academyId, run.getBusId(), weekday, run.getDirection())
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTE_NOT_CONFIGURED_FOR_RUN));
        List<RouteStop> routeStops = routeStopRepository.findAllOrderedByRouteIdAndAcademyId(route.getId(),
                academyId);
        OriginDestination originDestination = previewResolver.originDestinationOf(academy, routeStops,
                run.getDirection(), academyId);

        ConfirmedRoute confirmedRoute = confirmedRouteRepository.findById(run.getId())
                .orElseThrow(() -> new IllegalStateException("확정 노선이 없다 — runId=" + run.getId()));
        Long currentVersionId = confirmedRoute.getCurrentVersionId();
        RouteVersion currentVersion = routeVersionRepository.findById(currentVersionId)
                .orElseThrow(() -> new IllegalStateException("노선 버전이 없다 — versionId=" + currentVersionId));
        List<RunStop> beforeRunStops = runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(
                currentVersionId, academyId);

        List<RunRider> riders = runRiderRepository.findAllByRunIdAndAcademyId(run.getId(), academyId);
        DailyRoster roster = rosterOf(run, weekday, riders);

        List<Waypoint> appliedWaypoints = waypointRepository.findAllAppliedByRunIdAndAcademyId(run.getId(),
                academyId);

        return new RouteContext(weekday, originDestination, currentVersion, beforeRunStops, roster,
                appliedWaypoints);
    }

    /** 확정 배치가 쌓은 뒤 지금까지 반영된 명단이 기준선이다({@code ApprovalPreviewResolver.candidateRosterOf} 와 같은 근거) — 결석은 뺀다. */
    private static DailyRoster rosterOf(Run run, Weekday weekday, List<RunRider> riders) {
        List<Long> studentIds = new ArrayList<>();
        Map<Long, Long> stopOverrides = new LinkedHashMap<>();
        for (RunRider rider : riders) {
            if (rider.getStatus() == RiderStatus.ABSENT) {
                continue;
            }
            studentIds.add(rider.getStudentId());
            stopOverrides.put(rider.getStudentId(), rider.getStopId());
        }
        return new DailyRoster(run.getAcademyId(), weekday, run.getDirection(), studentIds, stopOverrides);
    }

    /** 이미 배포된 경유 지점을 지금 노선의 정차 순번 그대로 고정 지점으로 옮긴다 — 위치를 다시 흔들지 않는다(목표 7). */
    private static List<FixedStop> existingFixedStopsOf(RouteContext ctx) {
        Map<Long, Integer> seqByWaypointId = new LinkedHashMap<>();
        for (RunStop rs : ctx.beforeRunStops()) {
            if (rs.getWaypointId() != null) {
                seqByWaypointId.put(rs.getWaypointId(), rs.getSeq());
            }
        }
        List<FixedStop> fixedStops = new ArrayList<>();
        for (Waypoint w : ctx.appliedWaypoints()) {
            Integer seq = seqByWaypointId.get(w.getId());
            if (seq != null) {
                fixedStops.add(new FixedStop(w.getId(), new GeoPoint(w.getLat(), w.getLng()), seq));
            }
        }
        return fixedStops;
    }

    /**
     * 재최적화를 계산하고(트랜잭션 밖) 미리보기를 조립한 뒤, {@code apply} 일 때만 배포한다.
     *
     * @param removal 제거 흐름이면 {@code true} — {@link WaypointStore#deployRemoval} 로 갈린다
     */
    private WaypointResponse orchestrate(Run run, Waypoint waypoint, RouteContext ctx, List<FixedStop> fixedStops,
            boolean apply, Long accountId, boolean removal) {
        String fingerprint = RunConfirmationFingerprint.of(run.getAcademyId(), ctx.weekday(), run.getDirection(),
                run.getDepartTime(), ctx.originDestination().origin(), ctx.originDestination().destination(),
                ctx.roster().stopOverrides(), fixedStops);
        ComputationPolicy policy = new ComputationPolicy(ON_DEMAND_MAP_TIMEOUT, CallerPolicy.ON_DEMAND,
                RouteVersionSource.WAYPOINT);
        RouteComputationInput input = new RouteComputationInput(ctx.roster(), ctx.originDestination().origin(),
                ctx.originDestination().destination(), fixedStops, run.getDepartTime(), policy);

        // 외부 지도 API 를 부르는 계산은 트랜잭션 밖에서 돈다(클래스 javadoc).
        RouteComputation computation = pipeline.compute(input);

        RoutePreviewResponse routePreview = routePreviewResponseOf(ctx, computation, run.getAcademyId(), waypoint);

        if (apply) {
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (removal) {
                waypointStore.deployRemoval(run, waypoint, computation, fingerprint, accountId, now);
            } else {
                waypointStore.deployAdd(run, waypoint, computation, fingerprint, accountId, now);
            }
        }

        BigDecimal estDistanceBefore = ctx.currentVersion().getEstDistanceKm();
        BigDecimal estDistanceAfter = computation.estDistanceKm();
        return new WaypointResponse(waypoint.getId(), routePreview,
                routePreviewAssembler.lastEtaOf(routePreview.stopsBefore()),
                routePreviewAssembler.lastEtaOf(routePreview.stopsAfter()), estDistanceBefore, estDistanceAfter,
                apply);
    }

    /**
     * {@code RoutePreviewAssembler} 를 §5.5 상세와 같은 순서로 불러 전/후 대조를 조립한다.
     * 경유 지점 항목의 {@code stop_name} 은 {@code stopsById} 로 못 찾으므로(승하차지 명단이 아니다)
     * {@code waypointLabelsById} 를 별도로 넘겨 채운다 — 지금 이 요청의 대상 {@code waypoint} 는
     * 신규 추가라면 아직 {@code ctx.appliedWaypoints()} 에 없어 그 목록만으로는 부족하다.
     */
    private RoutePreviewResponse routePreviewResponseOf(RouteContext ctx, RouteComputation computation,
            Long academyId, Waypoint waypoint) {
        Set<Long> stopIds = routePreviewAssembler.unionOfStopIds(ctx.beforeRunStops(), computation);
        Map<Long, Stop> stopsById = routePreviewAssembler.stopsByIdOf(stopIds, academyId);
        Map<Long, String> waypointLabelsById = waypointLabelsOf(ctx, waypoint);
        List<PreviewStopResponse> stopsBefore = routePreviewAssembler.toPreviewStopsFromRunStops(
                ctx.beforeRunStops(), stopsById, waypointLabelsById);
        List<PreviewStopResponse> stopsAfter = routePreviewAssembler.toPreviewStopsFromComputation(computation,
                stopsById, waypointLabelsById);
        Map<Long, Integer> beforeSeq = routePreviewAssembler.seqMapOfRunStops(ctx.beforeRunStops());
        Map<Long, Integer> afterSeq = routePreviewAssembler.seqMapOfComputation(computation);
        return RoutePreviewResponse.of(stopsBefore, stopsAfter,
                routePreviewAssembler.reorderedOf(beforeSeq, afterSeq, stopsById),
                routePreviewAssembler.removedOf(beforeSeq, afterSeq, stopsById));
    }

    /** 이미 배포된 경유 지점 전부 + 지금 이 요청의 대상 경유 지점, 합쳐서 id → label 맵을 만든다. */
    private static Map<Long, String> waypointLabelsOf(RouteContext ctx, Waypoint waypoint) {
        Map<Long, String> labels = new LinkedHashMap<>();
        for (Waypoint w : ctx.appliedWaypoints()) {
            labels.put(w.getId(), w.getLabel());
        }
        labels.put(waypoint.getId(), waypoint.getLabel());
        return labels;
    }

    /** {@code RunConfirmationService.weekdayOf} 와 같은 계산. {@code LocalDate} 자체가 요일을 들고 있으므로 시계를 보지 않는다. */
    private static Weekday weekdayOf(LocalDate serviceDate) {
        return Weekday.valueOf(serviceDate.getDayOfWeek().name().substring(0, 3).toUpperCase(Locale.ROOT));
    }

    /** 재최적화 한 번에 필요한 문맥 — add·remove 가 이 조립을 공유한다. */
    private record RouteContext(Weekday weekday, OriginDestination originDestination, RouteVersion currentVersion,
            List<RunStop> beforeRunStops, DailyRoster roster, List<Waypoint> appliedWaypoints) {
    }
}
