package src.backend.request.preview.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import src.backend.global.location.GeoPoint;
import src.backend.request.preview.spec.ApprovalPreview;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.pipeline.ComputationSnapshot;
import src.backend.routing.pipeline.RouteComputation;
import testsupport.redis.RedisTestContainerBase;

import tools.jackson.databind.json.JsonMapper;

/**
 * 승인 미리보기는 캐시 객체의 메모리가 아니라 Redis 에 있다(Ruling 900 C). 넣은 것을 같은 내용으로 읽고, 결정 뒤 지우면 키가 사라지고, 하루 만료가 걸리며, Redis 오류는 없음으로 읽는다.
 */
class RedisApprovalPreviewCacheTest extends RedisTestContainerBase {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private RedisApprovalPreviewCache cache;
    private final long approvalId = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L);

    @BeforeEach
    void connect() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        cache = new RedisApprovalPreviewCache(redis, mapper);
    }

    @AfterEach
    void close() {
        cache.evict(approvalId);
        factory.destroy();
    }

    @Test
    @DisplayName("넣은 미리보기를 같은 내용으로 읽고, 결정 뒤 지우면 읽기와 Redis 키 모두 사라진다")
    void 넣은_미리보기를_읽고_결정_뒤_지운다() {
        ApprovalPreview preview = new ApprovalPreview("token-1", "fp-1", computation());

        cache.put(approvalId, preview);

        assertThat(cache.find(approvalId)).as("읽은 미리보기").contains(preview);
        cache.evict(approvalId);
        assertThat(cache.find(approvalId)).as("결정 뒤 지운 키").isEmpty();
        assertThat(redis.hasKey("schoolbus:approval-preview:" + approvalId)).isFalse();
    }

    @Test
    @DisplayName("저장한 키에 1일 만료가 걸려 있다")
    void 하루_만료가_걸린다() {
        cache.put(approvalId, new ApprovalPreview("token-1", "fp-1", computation()));

        Long seconds = redis.getExpire("schoolbus:approval-preview:" + approvalId);

        assertThat(seconds).isBetween(Duration.ofDays(1).minusMinutes(1).toSeconds(), Duration.ofDays(1).toSeconds());
    }

    @Test
    @DisplayName("Redis 가 오류를 내면 읽기는 없음으로, 쓰기·지우기는 예외 없이 지나간다 — Redis 오류는 없음으로 읽는다")
    void Redis_오류는_없음으로_읽는다() {
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        given(broken.opsForValue()).willReturn(ops);
        given(ops.get(anyString())).willThrow(new RedisConnectionFailureException("끊김"));
        given(broken.delete(anyString())).willThrow(new RedisConnectionFailureException("끊김"));
        RedisApprovalPreviewCache brokenCache = new RedisApprovalPreviewCache(broken, mapper);

        assertThat(brokenCache.find(approvalId)).isEmpty();
        brokenCache.evict(approvalId);
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
