package src.backend.routing.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import src.backend.global.location.GeoPoint;
import src.backend.routing.map.spec.RoadLeg;
import src.backend.routing.map.spec.RoadRoute;
import src.backend.routing.query.RoutePathCache.CachedRoadRoute;
import tools.jackson.databind.json.JsonMapper;

/** 도로 경로 캐시의 조회는 적중과 미적중을 각각 센다 — Redis 장애로 읽지 못한 조회는 미적중이고, 이 인스턴스 안의 기억에서 읽은 조회는 적중이다. */
class RoutePathCacheCountersTest {

    private static final String KEY = "schoolbus:route-path:1:abc";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final RoutePathCache cache = new RoutePathCache(redis, JsonMapper.builder().build(), Clock.systemUTC(),
            registry);

    private double count(String result) {
        return registry.get("schoolbus.cache.route_path." + result).counter().count();
    }

    private static CachedRoadRoute route() {
        GeoPoint point = new GeoPoint(new BigDecimal("37.560000"), new BigDecimal("126.970000"));
        return new CachedRoadRoute(new RoadRoute(List.of(new RoadLeg(700, 100, List.of(point))), false), 1L);
    }

    @Test
    @DisplayName("없는 키는 미적중, 저장한 키는 적중으로 센다")
    void 미적중과_적중을_센다() {
        AtomicReference<String> stored = new AtomicReference<>();
        given(redis.opsForValue()).willReturn(values);
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        given(values.get(anyString())).willAnswer(invocation -> stored.get());

        cache.find(KEY);
        assertThat(count("miss")).isEqualTo(1.0);
        assertThat(count("hit")).isZero();

        cache.put(KEY, route());
        cache.find(KEY);

        assertThat(count("hit")).isEqualTo(1.0);
        assertThat(count("miss")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Redis 가 멈춰 읽지 못하면 미적중이고, 멈춘 동안 저장한 경로를 기억에서 읽으면 적중이다")
    void 장애_중에는_기억에서_읽은_조회만_적중이다() {
        given(redis.opsForValue()).willReturn(values);
        given(values.get(anyString())).willThrow(new RedisConnectionFailureException("끊김"));
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("끊김")).when(values)
                .set(anyString(), anyString(), any(Duration.class));

        cache.find(KEY);
        assertThat(count("miss")).isEqualTo(1.0);

        cache.put(KEY, route());
        cache.find(KEY);

        assertThat(count("hit")).isEqualTo(1.0);
        assertThat(count("miss")).isEqualTo(1.0);
    }
}
