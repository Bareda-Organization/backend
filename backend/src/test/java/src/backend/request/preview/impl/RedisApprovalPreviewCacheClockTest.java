package src.backend.request.preview.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import tools.jackson.databind.json.JsonMapper;

import src.backend.global.location.GeoPoint;
import src.backend.request.preview.spec.ApprovalPreview;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.pipeline.ComputationSnapshot;
import src.backend.routing.pipeline.RouteComputation;

/**
 * Redis 를 쓸 수 없는 동안 승인 미리보기를 들고 있는 인스턴스 안 기억의 만료는 주입된 {@link Clock} 을 따른다 — 시스템 시계를 직접 쓰면 시험이 시간을 앞당길 수 없고 고정 시계 설정(시험 · 데모)과 어긋난다.
 */
class RedisApprovalPreviewCacheClockTest {

    private static final long APPROVAL_ID = 41L;

    @Test
    @DisplayName("Redis 가 안 되는 동안 들고 있는 미리보기는 주입된 시계가 30분을 넘기면 사라진다")
    void 인스턴스_안_기억의_만료는_주입된_시계를_따른다() {
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        given(broken.opsForValue()).willReturn(ops);
        given(ops.get(anyString())).willThrow(new RedisConnectionFailureException("끊김"));
        org.mockito.BDDMockito.willThrow(new RedisConnectionFailureException("끊김")).given(ops).set(anyString(), anyString(), any(Duration.class));
        MutableClock clock = new MutableClock(Instant.parse("2026-10-18T09:00:00Z"));
        RedisApprovalPreviewCache cache = new RedisApprovalPreviewCache(broken, JsonMapper.builder().build(), clock);
        ApprovalPreview preview = new ApprovalPreview("token-1", "fp-1", computation());

        cache.put(APPROVAL_ID, preview);

        assertThat(cache.find(APPROVAL_ID)).as("저장 직후").contains(preview);
        clock.advance(Duration.ofMinutes(29));
        assertThat(cache.find(APPROVAL_ID)).as("29분 뒤").contains(preview);
        clock.advance(Duration.ofMinutes(2));
        assertThat(cache.find(APPROVAL_ID)).as("주입된 시계로 31분 뒤 — 시스템 시계는 그대로다").isEmpty();
    }

    private static RouteComputation computation() {
        GeoPoint point = new GeoPoint(new BigDecimal("37.501234"), new BigDecimal("127.039876"));
        return new RouteComputation(List.of(OrderedStop.ofStop(11L, 1, point), OrderedStop.ofWaypoint(12L, 2, point)),
                25, new BigDecimal("12.34"),
                List.of(OffsetDateTime.parse("2026-10-18T08:00:00+09:00"), OffsetDateTime.parse("2026-10-18T08:10:00+09:00")),
                List.of(99L), List.of(point),
                new ComputationSnapshot("naver-directions", Map.of("early_minutes", 5), RouteVersionSource.APPROVAL, false));
    }

    /** 시험이 앞으로 돌릴 수 있는 시계. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
