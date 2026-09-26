package src.backend.routing.command;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.request.assembly.RoutePreviewAssembler;
import src.backend.request.domain.ChangeWindow;
import src.backend.request.domain.ChangeWindowPolicy;
import src.backend.request.dto.PreviewStopResponse;
import src.backend.request.dto.RoutePreviewResponse;
import src.backend.routing.domain.GeoPoint;
import src.backend.routing.dto.WaypointRequest;
import src.backend.routing.command.WaypointPreviewCache.WaypointPreview;
import src.backend.routing.dto.WaypointResponse;
import src.backend.routing.engine.spec.FixedStop;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.entity.RunStop;
import src.backend.routing.entity.Waypoint;
import src.backend.routing.map.spec.CallerPolicy;
import src.backend.routing.pipeline.ComputationPolicy;
import src.backend.routing.pipeline.RouteComputation;
import src.backend.routing.pipeline.RouteComputationInput;
import src.backend.routing.pipeline.RouteComputationPipeline;
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
 *
 * <p>§20.2 크기 신호(BR-101) — 문맥 조립은 {@link RouteContextAssembler} 로 뺐으나(342→312줄) 여전히
 * 200줄을 넘는다. add·remove·preview·deploy·responseOf 가 같은 {@link RouteContext}·{@link RouteComputation}
 * 을 순서대로 주고받는 한 흐름이라 더 가르면 그 상태를 필드로 다시 들고 다녀야 해(협력자 중복) 오히려
 * 추적이 어려워진다고 판단해 여기 남긴다.
 */
@Service
@RequiredArgsConstructor
public class WaypointCommandService {

    /** 온디맨드(관리자가 화면 앞에서 대기) 지도 API 타임아웃 — {@code ApprovalPreviewResolver} 와 같은 값. */
    private static final Duration ON_DEMAND_MAP_TIMEOUT = Duration.ofSeconds(5);

    private final RunRepository runRepository;
    private final WaypointRepository waypointRepository;
    private final RoutePreviewAssembler routePreviewAssembler;
    private final RouteComputationPipeline pipeline;
    private final AddressVerification addressVerification;
    private final WaypointStore waypointStore;
    private final WaypointPreviewCache previewCache;
    private final RouteContextAssembler routeContextAssembler;
    private final Clock clock;

    /**
     * 새 경유 지점을 지정한다(§5.15) — {@code apply=false} 는 미리보기, {@code apply=true} 는 그 미리보기의 배포다.
     *
     * <p>미리보기는 항상 행을 먼저 저장해 {@code waypoint_id} 를 응답에 담고, 계산 결과를 {@link WaypointPreviewCache}
     * 에 {@code preview_token} 으로 남긴다. 새 행을 만들기 전에 이 회차의 <b>미배포 행을 전부 지운다</b> — 관계자가
     * 라벨·주소를 바꿔 가며 미리보기를 여러 번 눌러 보는 것이 정상 흐름이라, 그대로 두면 고아 행이 호출마다 쌓인다.
     * 이 "지우고 저장" 두 쓰기는 {@link WaypointStore#saveCandidate} 의 짧은 트랜잭션으로 묶여 있다.
     *
     * <p>배포는 토큰이 가리키는 미리보기의 지점·순번·계산을 그대로 쓴다(BR-051) — 다시 계산하면 관리자가 본
     * 대조표와 다른 노선이 나갈 수 있다(ARCHITECTURE §8.4). 본문의 지점 값은 배포에 쓰이지 않는다.
     */
    public WaypointResponse add(AuthUser requester, Long runId, WaypointRequest request) {
        Run run = loadRunInWindow(requester, runId);
        if (request.apply()) {
            WaypointPreview preview = previewOf(run, request.previewToken(), false);
            Waypoint waypoint = waypointRepository.findById(preview.waypointId())
                    .filter(w -> !w.isApplied() && w.getRunId().equals(run.getId()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.PREVIEW_STALE));
            RouteContext ctx = routeContextAssembler.contextOf(run);
            return deploy(run, waypoint, ctx, addedFixedStops(ctx, waypoint, preview.seq()), preview,
                    requester.accountId());
        }
        GeoPoint point = resolvePoint(request);
        Waypoint waypoint = waypointStore.saveCandidate(run, Waypoint.forRun(run.getId(), request.label(),
                request.address(), point.lat(), point.lng(), request.note(), requester.accountId(),
                OffsetDateTime.now(clock)));
        RouteContext ctx = routeContextAssembler.contextOf(run);
        int seq = seqOf(request, ctx);
        return preview(run, waypoint, ctx, addedFixedStops(ctx, waypoint, seq), seq, false);
    }

    /**
     * 이미 배포된 경유 지점을 제거한다 — 추가와 같은 미리보기 → 배포 절차다. 미리보기 단계(아직 배포되지 않은)
     * 행은 대상이 아니다({@code WaypointRepository.findAppliedByIdAndRunIdAndAcademyId} 가 그 행을 걸러
     * {@code 404 WAYPOINT_NOT_FOUND} 로 답한다 — 존재 여부를 응답에서 드러내지 않는 관례).
     */
    public WaypointResponse remove(AuthUser requester, Long runId, Long waypointId, boolean apply,
            String previewToken) {
        Run run = loadRunInWindow(requester, runId);
        Waypoint waypoint = waypointRepository
                .findAppliedByIdAndRunIdAndAcademyId(waypointId, run.getId(), run.getAcademyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.WAYPOINT_NOT_FOUND));
        RouteContext ctx = routeContextAssembler.contextOf(run);
        List<FixedStop> fixedStops = removedFixedStops(ctx, waypoint);
        if (!apply) {
            return preview(run, waypoint, ctx, fixedStops, 0, true);
        }
        WaypointPreview preview = previewOf(run, previewToken, true);
        if (preview.waypointId() != waypoint.getId()) {
            throw new BusinessException(ErrorCode.PREVIEW_STALE);
        }
        return deploy(run, waypoint, ctx, fixedStops, preview, requester.accountId());
    }

    /** 새 경유 지점을 {@code seq} 에 끼운 고정 지점 목록 — 그 자리부터 뒤 경유 지점은 한 칸 밀린다(BR-020). */
    private static List<FixedStop> addedFixedStops(RouteContext ctx, Waypoint waypoint, int seq) {
        List<FixedStop> fixedStops = new ArrayList<>(existingFixedStopsOf(ctx).stream()
                .map(fs -> fs.seq() >= seq ? shifted(fs, 1) : fs)
                .toList());
        fixedStops.add(new FixedStop(waypoint.getId(), new GeoPoint(waypoint.getLat(), waypoint.getLng()), seq));
        return fixedStops;
    }

    /** 경유 지점을 뺀 고정 지점 목록 — 그 뒤 경유 지점은 한 칸 당겨진다(BR-020). */
    private static List<FixedStop> removedFixedStops(RouteContext ctx, Waypoint waypoint) {
        List<FixedStop> existing = existingFixedStopsOf(ctx);
        int removedSeq = existing.stream()
                .filter(fs -> fs.waypointId() == waypoint.getId())
                .mapToInt(FixedStop::seq)
                .findFirst()
                .orElse(Integer.MAX_VALUE);
        return existing.stream()
                .filter(fs -> fs.waypointId() != waypoint.getId())
                .map(fs -> fs.seq() > removedSeq ? shifted(fs, -1) : fs)
                .toList();
    }

    /** 이 회차의 지금 유효한 미리보기 — 토큰이 없거나 다르거나 흐름(추가·제거)이 다르면 {@code 409 PREVIEW_STALE}. */
    private WaypointPreview previewOf(Run run, String token, boolean removal) {
        return previewCache.find(run.getId())
                .filter(preview -> preview.token().equals(token) && preview.removal() == removal)
                .orElseThrow(() -> new BusinessException(ErrorCode.PREVIEW_STALE));
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

    /**
     * 설 자리 — 생략하면 맨 뒤(2026-09-22 이전의 유일한 동작).
     *
     * <p>상한이 <b>정차지 수 + 1</b> 인 이유는 "맨 뒤에 붙이기" 가 그 값이기 때문이다. 넘으면
     * {@code 422} 다 — 조용히 맨 뒤로 보내면 관계자가 지정한 자리와 다른 결과가 나온 것을 알 수 없다.
     */
    private int seqOf(WaypointRequest request, RouteContext ctx) {
        // 학원 항목(Ruling 327)은 계산 대상이 아니라 늘 맨 뒤에 따로 붙는다 — 세지 않는다.
        int last = (int) ctx.beforeRunStops().stream().filter(rs -> !rs.isDestination()).count() + 1;
        if (request.seq() == null) {
            return last;
        }
        if (request.seq() > last) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "설 자리는 1부터 %d 사이여야 합니다".formatted(last));
        }
        return request.seq();
    }

    /**
     * 고정 순번을 {@code delta} 만큼 옮긴다 — 순번은 절대 자리라, 앞에 경유 지점이 하나 끼거나 빠지면 뒤
     * 경유 지점이 같은 승하차지 사이에 머물려면 순번이 함께 움직여야 한다(BR-020). 옮기지 않으면 제거 뒤
     * 순번이 자리 수를 넘거나 추가 때 순번이 겹쳐 엔진이 거부하고({@code RouteSlots}, 500), 넘지 않아도
     * 관계자가 정한 자리가 다른 승하차지 사이로 바뀐다.
     */
    private static FixedStop shifted(FixedStop fixedStop, int delta) {
        return new FixedStop(fixedStop.waypointId(), fixedStop.point(), fixedStop.seq() + delta);
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

    /** 재최적화를 계산하고(트랜잭션 밖 — 외부 지도 API) 미리보기로 남긴다. 확정 노선은 그대로다. */
    private WaypointResponse preview(Run run, Waypoint waypoint, RouteContext ctx, List<FixedStop> fixedStops, int seq,
            boolean removal) {
        ComputationPolicy policy = new ComputationPolicy(ON_DEMAND_MAP_TIMEOUT, CallerPolicy.ON_DEMAND,
                RouteVersionSource.WAYPOINT);
        RouteComputationInput input = new RouteComputationInput(ctx.roster(), ctx.originDestination().origin(),
                ctx.originDestination().destination(), fixedStops, run.getDepartTime(), policy);
        RouteComputation computation = pipeline.compute(input);
        String token = UUID.randomUUID().toString();
        previewCache.put(run.getId(), new WaypointPreview(token, waypoint.getId(), removal, seq,
                fingerprintOf(run, ctx, fixedStops), ctx.currentVersion().getId(), computation));
        return responseOf(run, waypoint, ctx, computation, false, token);
    }

    /**
     * 미리보기의 계산을 그대로 배포한다 — 그 사이 입력(명단·승하차지·경유 지점)이 바뀌었으면 지문이 어긋나
     * {@code 409 PREVIEW_STALE} 이다(승인 배포 {@code ChangeRequestDecisionService.assertFingerprintFresh} 와 같은 형태).
     */
    private WaypointResponse deploy(Run run, Waypoint waypoint, RouteContext ctx, List<FixedStop> fixedStops,
            WaypointPreview preview, Long accountId) {
        if (!fingerprintOf(run, ctx, fixedStops).equals(preview.fingerprint())) {
            throw new BusinessException(ErrorCode.PREVIEW_STALE);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (preview.removal()) {
            waypointStore.deployRemoval(run, waypoint, preview, accountId, now);
        } else {
            waypointStore.deployAdd(run, waypoint, preview, accountId, now);
        }
        previewCache.evict(run.getId());
        return responseOf(run, waypoint, ctx, preview.computation(), true, null);
    }

    private static String fingerprintOf(Run run, RouteContext ctx, List<FixedStop> fixedStops) {
        return RunConfirmationFingerprint.of(run.getAcademyId(), ctx.weekday(), run.getDirection(),
                run.getDepartTime(), ctx.originDestination().origin(), ctx.originDestination().destination(),
                ctx.roster().stopOverrides(), fixedStops);
    }

    private WaypointResponse responseOf(Run run, Waypoint waypoint, RouteContext ctx, RouteComputation computation,
            boolean applied, String previewToken) {
        RoutePreviewResponse routePreview = routePreviewResponseOf(ctx, computation, run.getAcademyId(), waypoint);
        BigDecimal estDistanceBefore = ctx.currentVersion().getEstDistanceKm();
        BigDecimal estDistanceAfter = computation.estDistanceKm();
        return new WaypointResponse(waypoint.getId(), routePreview,
                routePreviewAssembler.lastEtaOf(routePreview.stopsBefore()),
                routePreviewAssembler.lastEtaOf(routePreview.stopsAfter()), estDistanceBefore, estDistanceAfter,
                ctx.currentVersion().getEstDurationMin(), computation.estDurationMin(), applied, previewToken);
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
                routePreviewAssembler.removedOf(beforeSeq, afterSeq, stopsById),
                routePreviewAssembler.roadPathOrEmpty(ctx.currentVersion().getRoadPath()), computation.roadPath());
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
}
