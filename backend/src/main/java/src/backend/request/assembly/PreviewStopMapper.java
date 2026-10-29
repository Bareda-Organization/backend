package src.backend.request.assembly;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import src.backend.request.dto.PreviewStopResponse;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.entity.RunStop;
import src.backend.routing.pipeline.RouteComputation;
import src.backend.student.entity.Stop;

/** 확정 배치 산출물·재최적화 결과를 화면용 정차 목록({@link PreviewStopResponse})으로 바꾸는 순수 변환 함수 모음. */
final class PreviewStopMapper {

    private PreviewStopMapper() {
    }

    /** 확정 배치 산출물({@code RunStop})을 화면용 정차 목록으로 바꾼다(경유 지점 이름은 {@code waypointLabelsById} 로 채운다). */
    static List<PreviewStopResponse> fromRunStops(List<RunStop> runStops, Map<Long, Stop> stopsById,
            Map<Long, String> waypointLabelsById) {
        // 학원 항목(Ruling 327)은 뺀다 — "후" 쪽(계산 결과)에는 없는 항목이라, 두면 전/후 대조가 어긋난다.
        return runStops.stream()
                .filter(rs -> !rs.isDestination())
                .map(rs -> {
                    Stop stop = stopsById.get(rs.getStopId());
                    return new PreviewStopResponse(rs.getSeq(),
                            nameOf(rs.getStopId(), rs.getWaypointId(), stopsById, waypointLabelsById), rs.getEta(),
                            stop != null ? stop.getLat() : null, stop != null ? stop.getLng() : null);
                })
                .toList();
    }

    /** 재최적화 결과를 화면용 정차 목록으로 바꾼다(경유 지점 이름은 {@code waypointLabelsById} 로 채운다). */
    static List<PreviewStopResponse> fromComputation(RouteComputation computation,
            Map<Long, Stop> stopsById, Map<Long, String> waypointLabelsById) {
        List<OrderedStop> ordered = computation.stops();
        List<OffsetDateTime> etas = computation.etas();
        List<PreviewStopResponse> stops = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            OrderedStop orderedStop = ordered.get(i);
            Stop stop = stopsById.get(orderedStop.stopId());
            stops.add(new PreviewStopResponse(orderedStop.seq(),
                    nameOf(orderedStop.stopId(), orderedStop.waypointId(), stopsById, waypointLabelsById), etas.get(i),
                    stop != null ? stop.getLat() : null, stop != null ? stop.getLng() : null));
        }
        return stops;
    }

    /** {@code stopId} 는 승하차지 명단에서, {@code waypointId} 는 경유 지점 label 맵에서 이름을 찾는다. */
    private static String nameOf(Long stopId, Long waypointId, Map<Long, Stop> stopsById,
            Map<Long, String> waypointLabelsById) {
        if (stopId != null) {
            Stop stop = stopsById.get(stopId);
            return stop != null ? stop.getName() : null;
        }
        if (waypointId != null) {
            return waypointLabelsById.get(waypointId);
        }
        return null;
    }
}
