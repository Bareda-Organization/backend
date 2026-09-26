package src.backend.routing.query;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import src.backend.routing.entity.RunStop;

/**
 * 확정 노선의 정차 순서에서 "지금 정차 항목" 을 고른다(BR-099, 위치 방송·노선 조회 양쪽이 각자 다시
 * 적던 판정 통합) — 도착 시각이 채워진 정차 중 {@code seq} 최댓값.
 */
public final class CurrentRunStopResolver {

    private CurrentRunStopResolver() {
    }

    public static Optional<RunStop> resolve(List<RunStop> ordered) {
        return ordered.stream()
                .filter(stop -> stop.getArrivedAt() != null)
                .max(Comparator.comparingInt(RunStop::getSeq));
    }
}
