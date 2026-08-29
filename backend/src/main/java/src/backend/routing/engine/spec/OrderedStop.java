package src.backend.routing.engine.spec;

import java.util.Objects;

import src.backend.routing.domain.GeoPoint;

/**
 * 산출 순서의 한 자리 — 승하차지이거나 경유 지점이고, 둘 다이거나 둘 다 아닐 수는 없다.
 *
 * <p>배타 조건을 여기서 막는 이유는 {@code ck_run_stop_target_exclusive} 가 DB 에만 있으면 계산
 * 중에는 아무도 막지 않아, 두 자리가 뭉갠 값이 저장 단계(Phase 7)까지 가서야 드러나기 때문이다.
 */
public record OrderedStop(Long stopId, Long waypointId, int seq, GeoPoint point) {

    public OrderedStop {
        Objects.requireNonNull(point, "정차지 좌표가 없다");
        if ((stopId == null) == (waypointId == null)) {
            throw new IllegalArgumentException("승하차지와 경유 지점 중 정확히 하나만 채운다");
        }
        if (seq < 1) {
            throw new IllegalArgumentException("순번은 1부터 시작한다: " + seq);
        }
    }

    /** 승하차지 한 자리. */
    public static OrderedStop ofStop(long stopId, int seq, GeoPoint point) {
        return new OrderedStop(stopId, null, seq, point);
    }

    /** 경유 지점 한 자리. */
    public static OrderedStop ofWaypoint(long waypointId, int seq, GeoPoint point) {
        return new OrderedStop(null, waypointId, seq, point);
    }
}
