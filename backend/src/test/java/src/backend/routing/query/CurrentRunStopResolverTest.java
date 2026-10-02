package src.backend.routing.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import src.backend.routing.dto.PositionStopView;
import src.backend.routing.entity.RunStop;

/** {@link CurrentRunStopResolver#resolve} — 도착 시각이 채워진 정차 중 seq 최댓값을 고르는지(BR-099 통합). */
class CurrentRunStopResolverTest {

    @Test
    void 도착_처리된_정차_중_seq가_가장_큰_것을_고른다() {
        RunStop first = RunStop.forStop(1L, 10L, 1, OffsetDateTime.now());
        first.markArrived(OffsetDateTime.now());
        RunStop second = RunStop.forStop(1L, 11L, 2, OffsetDateTime.now());
        second.markArrived(OffsetDateTime.now());
        RunStop notYetArrived = RunStop.forStop(1L, 12L, 3, OffsetDateTime.now());

        assertThat(CurrentRunStopResolver.resolve(List.of(first, second, notYetArrived))).contains(second);
    }

    @Test
    void 도착_처리된_정차가_없으면_빈다() {
        RunStop notYetArrived = RunStop.forStop(1L, 10L, 1, OffsetDateTime.now());

        assertThat(CurrentRunStopResolver.resolve(List.of(notYetArrived))).isEmpty();
    }

    @Test
    void 엔티티가_아닌_투영도_같은_규칙으로_고른다() {
        OffsetDateTime now = OffsetDateTime.now();
        PositionStopView first = new PositionStopView(1, now, now, "첫째");
        PositionStopView second = new PositionStopView(2, now, now, "둘째");
        PositionStopView notYetArrived = new PositionStopView(3, null, now, "셋째");

        assertThat(CurrentRunStopResolver.resolve(List.of(first, second, notYetArrived),
                PositionStopView::seq, PositionStopView::arrivedAt)).contains(second);
        assertThat(CurrentRunStopResolver.resolve(List.of(notYetArrived),
                PositionStopView::seq, PositionStopView::arrivedAt)).isEmpty();
    }
}
