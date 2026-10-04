package src.backend.student.query;

import java.util.List;

import src.backend.routing.domain.GeoPoint;
import src.backend.student.dto.StudentRouteResponse;

/**
 * 확정 노선 도로 좌표를 학생 노선 응답의 표시 범위(P-08)에 맞춰 자른다 — 외부 지도 API 없이 저장된 좌표만 쓴다(Ruling 831).
 *
 * <p>자르는 방법: 표시 승하차지를 순서대로 훑으며 각각 <b>가장 가까운 경로 점</b>을 찾되, 뒤 승하차지는 앞 승하차지가 잡힌 위치
 * 이후에서만 찾는다(노선은 한 방향으로 달리므로 순서가 거꾸로 잡히지 않게 한다). 첫 승하차지의 점부터 마지막 승하차지의 점까지가
 * 응답 구간이다. 거리가 같으면 앞쪽 점을 고른다.
 */
final class RoadPathSlicer {

    private RoadPathSlicer() {
    }

    /**
     * {@code path} 중 {@code stops} 의 처음~끝 좌표 구간. 경로가 2점 미만이거나 좌표를 가진 승하차지가 2개 미만이면 빈 목록 —
     * 앱이 표시 승하차지를 점선으로 잇는다.
     */
    static List<GeoPoint> slice(List<GeoPoint> path, List<StudentRouteResponse.Stop> stops) {
        List<GeoPoint> targets = stops.stream().filter(stop -> stop.lat() != null && stop.lng() != null)
                .map(stop -> new GeoPoint(stop.lat(), stop.lng())).toList();
        if (path.size() < 2 || targets.size() < 2) {
            return List.of();
        }
        int start = nearestIndex(path, targets.get(0), 0);
        int end = start;
        for (GeoPoint target : targets.subList(1, targets.size())) {
            end = nearestIndex(path, target, end);
        }
        return List.copyOf(path.subList(start, end + 1));
    }

    private static int nearestIndex(List<GeoPoint> path, GeoPoint target, int fromIndex) {
        int best = fromIndex;
        double bestMeters = path.get(fromIndex).distanceMetersTo(target);
        for (int i = fromIndex + 1; i < path.size(); i++) {
            double meters = path.get(i).distanceMetersTo(target);
            if (meters < bestMeters) {
                best = i;
                bestMeters = meters;
            }
        }
        return best;
    }
}
