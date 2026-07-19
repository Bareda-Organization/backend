package src.backend.routing.infrastructure.spec;

import java.util.List;

/**
 * directions API 호출 결과 — waypoint 순서대로의 실도로 거리·소요시간·경로.
 * {@code legDurationsS}는 waypoint 구간별(0→1, 1→2, ...) 소요시간(초)이라 길이는 waypoints.size()-1.
 */
public record RouteResult(
        double totalDistanceM,
        double totalDurationS,
        List<Double> legDurationsS,
        String polyline) {
}
