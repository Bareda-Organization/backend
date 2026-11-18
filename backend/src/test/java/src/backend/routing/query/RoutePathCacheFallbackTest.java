package src.backend.routing.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
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

/**
 * 도로 경로 캐시는 Redis 가 멈춘 동안 이 인스턴스 안의 기억이 대신하고, 같은 키의 계산은 한 번에 하나만 돈다 — 그렇지 않으면 Redis 장애 중이나
 * 같은 편성 화면을 여럿이 동시에 열 때 요청마다 지도 API 를 부른다.
 */
class RoutePathCacheFallbackTest {

    private static final String KEY = "schoolbus:route-path:1:abc";

    private static final List<GeoPoint> PATH = List.of(
            new GeoPoint(new BigDecimal("37.560000"), new BigDecimal("126.970000")),
            new GeoPoint(new BigDecimal("37.561000"), new BigDecimal("126.971000")));

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private final RoutePathCache cache = new RoutePathCache(redis, JsonMapper.builder().build(), Clock.systemUTC(),
            new SimpleMeterRegistry());

    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    @AfterEach
    void 정리한다() {
        executor.shutdownNow();
    }

    private static CachedRoadRoute route() {
        return new CachedRoadRoute(new RoadRoute(List.of(new RoadLeg(700, 100, PATH)), false), 1L);
    }

    private void redisIsDown() {
        given(redis.opsForValue()).willReturn(values);
        given(values.get(anyString())).willThrow(new RedisConnectionFailureException("끊김"));
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("끊김")).when(values)
                .set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Redis 가 멈춘 동안 저장한 경로는 같은 키의 다음 조회에서 계산 없이 읽힌다")
    void 장애_중_저장한_경로는_다음_조회에서_읽힌다() {
        redisIsDown();
        cache.put(KEY, route());

        assertThat(cache.find(KEY)).isPresent();
    }

    @Test
    @DisplayName("Redis 가 멈춘 동안 같은 키를 두 번 조회해도 계산은 한 번이다")
    void 장애_중_두_번째_조회는_계산하지_않는다() {
        redisIsDown();
        AtomicInteger computations = new AtomicInteger();

        cache.findOrCompute(KEY, () -> {
            computations.incrementAndGet();
            cache.put(KEY, route());
            return route();
        });
        cache.findOrCompute(KEY, () -> {
            computations.incrementAndGet();
            return route();
        });

        assertThat(computations).hasValue(1);
    }

    @Test
    @DisplayName("같은 키를 동시에 조회하면 계산은 한 번이고 모두 그 결과를 받는다")
    void 동시_조회는_한_번만_계산한다() throws Exception {
        redisIsDown();
        int requests = 8;
        CyclicBarrier start = new CyclicBarrier(requests);
        AtomicInteger computations = new AtomicInteger();
        List<Future<CachedRoadRoute>> results = new ArrayList<>();

        for (int i = 0; i < requests; i++) {
            results.add(executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return cache.findOrCompute(KEY, () -> {
                    computations.incrementAndGet();
                    sleepWhileMapProviderAnswers();
                    return route();
                });
            }));
        }

        for (Future<CachedRoadRoute> result : results) {
            assertThat(result.get(10, TimeUnit.SECONDS).computedAtEpochMillis()).isEqualTo(1L);
        }
        assertThat(computations).hasValue(1);
    }

    @Test
    @DisplayName("계산이 예외로 끝나면 기다리던 요청도 같은 예외를 받고 다음 요청은 다시 계산한다")
    void 계산_실패는_기다린_요청에도_전해지고_다음_요청은_다시_계산한다() {
        redisIsDown();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cache.findOrCompute(KEY, () -> {
            throw new IllegalStateException("지도 오류");
        })).isInstanceOf(IllegalStateException.class).hasMessage("지도 오류");

        assertThat(cache.findOrCompute(KEY, RoutePathCacheFallbackTest::route).computedAtEpochMillis()).isEqualTo(1L);
    }

    /** 지도 API 가 답하는 데 걸리는 시간 — 같은 키의 다른 요청이 그동안 계산 자리에 들어오려 한다. */
    private static void sleepWhileMapProviderAnswers() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
