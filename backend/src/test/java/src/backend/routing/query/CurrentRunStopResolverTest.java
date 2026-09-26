package src.backend.routing.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

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
}
