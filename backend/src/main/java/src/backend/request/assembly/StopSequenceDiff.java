package src.backend.request.assembly;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import src.backend.request.dto.StopRefResponse;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.entity.RunStop;
import src.backend.routing.pipeline.RouteComputation;
import src.backend.student.entity.Stop;

/** 승하차지 순번 전/후 대조(순번 맵 · 순서가 바뀐 승하차지 · 빠진 승하차지 · 바뀐 승하차지 id)를 만드는 순수 함수 모음. */
final class StopSequenceDiff {

    private StopSequenceDiff() {
    }

    /** 승하차지 id → 확정 배치의 순번. {@link #reorderedOf}·{@link #removedOf} 대조의 "전" 쪽이다. */
    static Map<Long, Integer> seqMapOfRunStops(List<RunStop> runStops) {
        Map<Long, Integer> map = new LinkedHashMap<>();
        for (RunStop rs : runStops) {
            if (rs.getStopId() != null) {
                map.put(rs.getStopId(), rs.getSeq());
            }
        }
        return map;
    }

    /** 승하차지 id → 재최적화 결과의 순번. {@link #reorderedOf}·{@link #removedOf} 대조의 "후" 쪽이다. */
    static Map<Long, Integer> seqMapOfComputation(RouteComputation computation) {
        Map<Long, Integer> map = new LinkedHashMap<>();
        for (OrderedStop stop : computation.stops()) {
            if (stop.stopId() != null) {
                map.put(stop.stopId(), stop.seq());
            }
        }
        return map;
    }

    /** 전/후 모두에 있지만 순번이 달라진 승하차지(§5.5 상세 {@code reordered[]}). */
    static List<StopRefResponse> reorderedOf(Map<Long, Integer> beforeSeq, Map<Long, Integer> afterSeq,
            Map<Long, Stop> stopsById) {
        return afterSeq.entrySet().stream()
                .filter(entry -> beforeSeq.containsKey(entry.getKey())
                        && !beforeSeq.get(entry.getKey()).equals(entry.getValue()))
                .map(entry -> stopRefOf(entry.getKey(), stopsById))
                .toList();
    }

    /** 전에는 있었으나 후에는 없는 승하차지(§5.5 상세 {@code removed[]}) — 잔여 인원이 0이 된 경우다. */
    static List<StopRefResponse> removedOf(Map<Long, Integer> beforeSeq, Map<Long, Integer> afterSeq,
            Map<Long, Stop> stopsById) {
        return beforeSeq.keySet().stream()
                .filter(stopId -> !afterSeq.containsKey(stopId))
                .map(stopId -> stopRefOf(stopId, stopsById))
                .toList();
    }

    private static StopRefResponse stopRefOf(Long stopId, Map<Long, Stop> stopsById) {
        Stop stop = stopsById.get(stopId);
        return new StopRefResponse(stopId, stop != null ? stop.getName() : null,
                stop != null ? stop.getLat() : null, stop != null ? stop.getLng() : null);
    }

    static Set<Long> changedStopIdsOf(Map<Long, Integer> beforeSeq, Map<Long, Integer> afterSeq) {
        Set<Long> changed = new LinkedHashSet<>();
        afterSeq.forEach((stopId, seq) -> {
            if (!seq.equals(beforeSeq.get(stopId))) {
                changed.add(stopId);
            }
        });
        beforeSeq.keySet().stream().filter(stopId -> !afterSeq.containsKey(stopId)).forEach(changed::add);
        return changed;
    }
}
