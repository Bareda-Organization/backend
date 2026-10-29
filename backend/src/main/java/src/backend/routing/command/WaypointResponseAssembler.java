package src.backend.routing.command;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.request.assembly.RoutePreviewAssembler;
import src.backend.request.dto.PreviewStopResponse;
import src.backend.request.dto.RoutePreviewResponse;
import src.backend.routing.dto.WaypointResponse;
import src.backend.routing.entity.Waypoint;
import src.backend.routing.pipeline.RouteComputation;
import src.backend.student.entity.Stop;

/** 경유 지점 미리보기·배포 응답({@link WaypointResponse}) 조립 — 전/후 노선 대조와 거리·시간 비교를 만든다(API_SPEC §5.15). */
@Component
@RequiredArgsConstructor
public class WaypointResponseAssembler {

    private final RoutePreviewAssembler routePreviewAssembler;

    /** 변경 한 건과 그 계산 결과로 응답을 만든다 — 배포 응답이면 {@code applied=true}, 미리보기면 {@code previewToken} 을 담는다. */
    public WaypointResponse responseOf(WaypointChange change, RouteComputation computation, boolean applied,
            String previewToken) {
        RouteContext ctx = change.ctx();
        RoutePreviewResponse routePreview = routePreviewResponseOf(ctx, computation, change.run().getAcademyId(),
                change.waypoint());
        BigDecimal estDistanceBefore = ctx.currentVersion().getEstDistanceKm();
        BigDecimal estDistanceAfter = computation.estDistanceKm();
        return new WaypointResponse(change.waypoint().getId(), routePreview,
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
