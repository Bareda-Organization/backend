package src.backend.routing.map.impl;

import java.util.ArrayList;
import java.util.List;

import src.backend.routing.domain.GeoPoint;

/**
 * 지점열을 공급자 1회 호출이 감당하는 크기로 나눈다 — 경유지 상한 초과 시 구간 분할
 * ({@code ARCHITECTURE §8.2} ③).
 *
 * <p><b>이웃한 두 구간은 경계 지점을 공유한다.</b> 그래야 구간별 {@code legs} 를 이어 붙였을 때
 * {@code legs.size() == points.size() - 1} 이 그대로 복원된다 — 공유하지 않으면 경계를 잇는 구간이
 * 통째로 사라지고, 그 사고는 총 거리만 조금 줄어든 형태라 눈으로 드러나지 않는다.
 */
final class RoutePointSegments {

    /** 지점이 2개는 돼야 구간 1개가 나온다 — 상한이 그보다 작으면 분할 자체가 성립하지 않는다. */
    private static final int MINIMUM_POINTS_PER_CALL = 2;

    private RoutePointSegments() {
    }

    /**
     * 앞 구간의 마지막 지점이 다음 구간의 첫 지점이 되도록 나눈다 — 각 구간의 지점 수는
     * {@code maxPointsPerCall} 이하이고, 구간별 구간 수의 합은 {@code points.size() - 1} 이다.
     */
    static List<List<GeoPoint>> split(List<GeoPoint> points, int maxPointsPerCall) {
        if (maxPointsPerCall < MINIMUM_POINTS_PER_CALL) {
            throw new IllegalStateException("경유지 상한이 " + MINIMUM_POINTS_PER_CALL + " 미만이다: " + maxPointsPerCall);
        }
        List<List<GeoPoint>> segments = new ArrayList<>();
        int last = points.size() - 1;
        int from = 0;
        while (from < last) {
            int to = Math.min(from + maxPointsPerCall - 1, last);
            segments.add(List.copyOf(points.subList(from, to + 1)));
            from = to;
        }
        return segments;
    }
}
