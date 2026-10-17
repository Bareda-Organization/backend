package src.backend.request.preview.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import src.backend.request.preview.spec.ApprovalPreview;
import src.backend.routing.pipeline.RouteComputation;

/**
 * DA-11(Ruling 899) — 승인 미리보기 캐시는 상한 500건 · 하루 만료다(Redis 로 옮기기 전까지). 결정되지 않은 승인 건이 쌓여도 힙이
 * 무한히 늘지 않는다.
 */
class InMemoryApprovalPreviewCacheTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2030-04-01T00:00:00Z"));

    private final InMemoryApprovalPreviewCache cache = new InMemoryApprovalPreviewCache(clock);

    @Test
    @DisplayName("DA-11 — 501번째 항목을 넣으면 가장 오래된 하나가 밀려나 500건이 남는다")
    void 상한_500건을_넘으면_가장_오래된_것이_밀려난다() {
        for (long id = 1; id <= 501; id++) {
            cache.put(id, preview(id));
        }

        assertThat(cache.find(1L)).as("가장 오래된 항목").isEmpty();
        assertThat(cache.find(2L)).isPresent();
        assertThat(cache.find(501L)).isPresent();
    }

    @Test
    @DisplayName("DA-11 — 하루가 지나면 만료되고 그 전에는 남아 있다")
    void 하루가_지나면_만료된다() {
        cache.put(7L, preview(7L));

        clock.advance(Duration.ofHours(23).plusMinutes(59));
        assertThat(cache.find(7L)).as("23시간 59분 뒤").isPresent();

        clock.advance(Duration.ofMinutes(2));
        assertThat(cache.find(7L)).as("24시간 1분 뒤").isEmpty();
    }

    private static ApprovalPreview preview(long id) {
        return new ApprovalPreview("token-" + id, "fingerprint-" + id, mock(RouteComputation.class));
    }

    /** 시험이 시각을 앞으로 돌리는 시계. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
