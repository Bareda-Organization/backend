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
 * S-4 — 승인 미리보기는 Redis 에 있어 인스턴스 A 가 만든 것을 B 가 결정에 쓴다(Ruling 900 C). 인스턴스 하나 = 이 캐시 객체 하나, Redis 는 둘이 같은 컨테이너를 본다.
 * 결정 뒤 지우면 어느 쪽에서도 사라지고, 하루 만료가 걸리며, Redis 오류는 모자람(없음)으로 읽는다(K4).
 */
class RedisApprovalPreviewCacheTest extends RedisTestContainerBase {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private RedisApprovalPreviewCache instanceA;
    private RedisApprovalPreviewCache instanceB;
    private final long approvalId = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L);

    @BeforeEach
    void connect() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        instanceA = new RedisApprovalPreviewCache(redis, mapper);
        instanceB = new RedisApprovalPreviewCache(new StringRedisTemplate(factory), mapper);
    }

    @AfterEach
    void close() {
        instanceA.evict(approvalId);
        factory.destroy();
    }

    @Test
    @DisplayName("A 에서 만든 미리보기를 B 가 같은 내용으로 읽고, B 가 결정 뒤 지우면 A 에서도 사라진다")
    void 한_인스턴스가_만들고_다른_인스턴스가_결정한다() {
        ApprovalPreview preview = new ApprovalPreview("token-1", "fp-1", computation());

        instanceA.put(approvalId, preview);

        assertThat(instanceB.find(approvalId)).as("B 가 읽은 미리보기").contains(preview);
        instanceB.evict(approvalId);
        assertThat(instanceA.find(approvalId)).as("결정 뒤 지운 키").isEmpty();
        assertThat(redis.hasKey("schoolbus:approval-preview:" + approvalId)).isFalse();
    }

    @Test
    @DisplayName("저장한 키에 1일 만료가 걸려 있다")
    void 하루_만료가_걸린다() {
        instanceA.put(approvalId, new ApprovalPreview("token-1", "fp-1", computation()));

        Long seconds = redis.getExpire("schoolbus:approval-preview:" + approvalId);

        assertThat(seconds).isBetween(Duration.ofDays(1).minusMinutes(1).toSeconds(), Duration.ofDays(1).toSeconds());
    }

    @Test
    @DisplayName("Redis 가 오류를 내면 읽기는 없음으로, 쓰기·지우기는 예외 없이 지나간다(K4)")
    void Redis_오류는_없음으로_읽는다() {
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        given(broken.opsForValue()).willReturn(ops);
        given(ops.get(anyString())).willThrow(new RedisConnectionFailureException("끊김"));
        given(broken.delete(anyString())).willThrow(new RedisConnectionFailureException("끊김"));
        RedisApprovalPreviewCache cache = new RedisApprovalPreviewCache(broken, mapper);

        assertThat(cache.find(approvalId)).isEmpty();
        cache.evict(approvalId);
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
