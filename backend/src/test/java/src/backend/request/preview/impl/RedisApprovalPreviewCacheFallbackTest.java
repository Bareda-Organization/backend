package src.backend.request.preview.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import src.backend.global.location.GeoPoint;
import src.backend.request.preview.spec.ApprovalPreview;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.pipeline.ComputationSnapshot;
import src.backend.routing.pipeline.RouteComputation;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis 가 멈춘 동안 이 인스턴스 안의 기억에 들어간 승인 미리보기는 Redis 가 다시 답해도(키가 없다고 답해도) 읽힌다 — 그렇지 않으면 승인이
 * {@code 409 PREVIEW_STALE} 로 끝난다. 또 Redis 실패가 반복돼도 경고 로그는 한 간격에 한 건이다.
 */
class RedisApprovalPreviewCacheFallbackTest {

    private static final int FAILURES = 5;

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private final RedisApprovalPreviewCache cache = new RedisApprovalPreviewCache(redis, JsonMapper.builder().build(),
            Clock.systemUTC());

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private final Logger logger = (Logger) LoggerFactory.getLogger(RedisApprovalPreviewCache.class);

    @BeforeEach
    void 준비한다() {
        given(redis.opsForValue()).willReturn(values);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void 정리한다() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("Redis 가 멈춘 동안 저장한 미리보기는 Redis 가 다시 답하며 키가 없다고 해도 읽힌다")
    void 장애_중_저장한_미리보기는_복구_뒤에도_읽힌다() {
        ApprovalPreview preview = new ApprovalPreview("token-1", "fp-1", computation());
        doThrow(new RedisConnectionFailureException("끊김")).when(values).set(anyString(), anyString(), any(Duration.class));

        cache.put(7L, preview);
        given(values.get(anyString())).willReturn(null);

        assertThat(cache.find(7L)).contains(preview);
    }

    @Test
    @DisplayName("Redis 가 답하고 기억에도 없으면 빈 값이다")
    void 둘_다_없으면_빈_값이다() {
        given(values.get(anyString())).willReturn(null);

        assertThat(cache.find(8L)).isEmpty();
    }

    @Test
    @DisplayName("읽기 실패가 반복돼도 경고는 한 건이다")
    void 읽기_실패는_경고_한_건() {
        given(values.get(anyString())).willThrow(new RedisConnectionFailureException("끊김"));

        for (int i = 0; i < FAILURES; i++) {
            cache.find(1L);
        }

        assertThat(warnings()).isEqualTo(1);
    }

    @Test
    @DisplayName("저장 실패가 반복돼도 경고는 한 건이다")
    void 저장_실패는_경고_한_건() {
        doThrow(new RedisConnectionFailureException("끊김")).when(values).set(anyString(), anyString(), any(Duration.class));
        ApprovalPreview preview = new ApprovalPreview("token-1", "fp-1", computation());

        for (int i = 0; i < FAILURES; i++) {
            cache.put((long) i, preview);
        }

        assertThat(warnings()).isEqualTo(1);
    }

    @Test
    @DisplayName("삭제 실패가 반복돼도 경고는 한 건이다")
    void 삭제_실패는_경고_한_건() {
        given(redis.delete(anyString())).willThrow(new RedisConnectionFailureException("끊김"));

        for (int i = 0; i < FAILURES; i++) {
            cache.evict((long) i);
        }

        assertThat(warnings()).isEqualTo(1);
    }

    @Test
    @DisplayName("전체 삭제 실패가 반복돼도 경고는 한 건이다")
    void 전체_삭제_실패는_경고_한_건() {
        given(redis.keys(anyString())).willThrow(new RedisConnectionFailureException("끊김"));

        for (int i = 0; i < FAILURES; i++) {
            cache.clear();
        }

        assertThat(warnings()).isEqualTo(1);
    }

    private long warnings() {
        return appender.list.stream().filter(event -> event.getLevel().levelStr.equals("WARN")).count();
    }

    private static RouteComputation computation() {
        GeoPoint point = new GeoPoint(new BigDecimal("37.501234"), new BigDecimal("127.039876"));
        return new RouteComputation(List.of(OrderedStop.ofStop(11L, 1, point), OrderedStop.ofWaypoint(12L, 2, point)),
                25, new BigDecimal("12.34"),
                List.of(OffsetDateTime.parse("2026-10-18T08:00:00+09:00"), OffsetDateTime.parse("2026-10-18T08:10:00+09:00")),
                List.of(99L), List.of(point),
                new ComputationSnapshot("naver-directions", Map.of("early_minutes", 5), RouteVersionSource.APPROVAL, false));
    }
}
