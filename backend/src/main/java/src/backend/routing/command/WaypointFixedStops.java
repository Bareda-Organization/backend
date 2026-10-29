package src.backend.routing.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import src.backend.global.location.GeoPoint;
import src.backend.routing.engine.spec.FixedStop;
import src.backend.routing.entity.RunStop;
import src.backend.routing.entity.Waypoint;

/** 경유 지점 추가·제거가 만드는 재최적화 입력의 고정 지점 목록 계산(BR-020) — 상태가 없는 순수 계산이다. */
final class WaypointFixedStops {

    private WaypointFixedStops() {
    }

    /** 새 경유 지점을 {@code seq} 에 끼운 고정 지점 목록 — 그 자리부터 뒤 경유 지점은 한 칸 밀린다(BR-020). */
    static List<FixedStop> addedFixedStops(RouteContext ctx, Waypoint waypoint, int seq) {
        List<FixedStop> fixedStops = new ArrayList<>(existingFixedStopsOf(ctx).stream()
                .map(fs -> fs.seq() >= seq ? shifted(fs, 1) : fs)
                .toList());
        fixedStops.add(new FixedStop(waypoint.getId(), new GeoPoint(waypoint.getLat(), waypoint.getLng()), seq));
        return fixedStops;
    }

    /** 경유 지점을 뺀 고정 지점 목록 — 그 뒤 경유 지점은 한 칸 당겨진다(BR-020). */
    static List<FixedStop> removedFixedStops(RouteContext ctx, Waypoint waypoint) {
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

    /**
     * 고정 순번을 {@code delta} 만큼 옮긴다 — 순번은 절대 자리라, 앞에 경유 지점이 하나 끼거나 빠지면 뒤
     * 경유 지점이 같은 승하차지 사이에 머물려면 순번이 함께 움직여야 한다(BR-020). 옮기지 않으면 제거 뒤
     * 순번이 자리 수를 넘거나 추가 때 순번이 겹쳐 엔진이 거부하고({@code RouteSlots}, 500), 넘지 않아도
     * 관계자가 정한 자리가 다른 승하차지 사이로 바뀐다.
     */
    private static FixedStop shifted(FixedStop fixedStop, int delta) {
        return new FixedStop(fixedStop.waypointId(), fixedStop.point(), fixedStop.seq() + delta);
    }

    /** 이미 배포된 경유 지점을 지금 노선의 정차 순번 그대로 고정 지점으로 옮긴다 — 위치를 다시 흔들지 않는다. */
    static List<FixedStop> existingFixedStopsOf(RouteContext ctx) {
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
}
