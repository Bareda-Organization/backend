package src.backend.routing.query;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import src.backend.routing.entity.RunStop;

/**
 * 확정 노선의 정차 순서에서 "지금 정차 항목" 을 고른다(BR-099, 위치 방송·노선 조회 양쪽이 각자 다시
 * 적던 판정 통합) — 도착 시각이 채워진 정차 중 {@code seq} 최댓값.
 */
public final class CurrentRunStopResolver {

    private CurrentRunStopResolver() {
    }

    public static Optional<RunStop> resolve(List<RunStop> ordered) {
        return resolve(ordered, RunStop::getSeq, RunStop::getArrivedAt);
    }

    /**
     * 정차 항목이 엔티티가 아닌 투영(위치 1건마다 읽는 {@code PositionStopView} 등)이어도 같은 규칙을 쓰도록
     * {@code seq}·도착 시각 접근자를 받는다 — 규칙은 이 한 곳에만 둔다(BR-341, BR-099 회귀 방지).
     */
    public static <T> Optional<T> resolve(List<T> ordered, ToIntFunction<T> seq, Function<T, OffsetDateTime> arrivedAt) {
        return ordered.stream()
                .filter(stop -> arrivedAt.apply(stop) != null)
                .max(Comparator.comparingInt(seq));
    }
}
