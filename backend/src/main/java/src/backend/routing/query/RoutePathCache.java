package src.backend.routing.query;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import tools.jackson.databind.json.JsonMapper;

import src.backend.global.common.logging.RateLimitedWarn;
import src.backend.global.location.GeoPoint;
import src.backend.global.redis.LocalFallbackMemory;
import src.backend.routing.map.spec.RoadRoute;

/**
 * 지도 API 가 돌려준 편성 도로 경로의 Redis 캐시(API_SPEC §5.9 {@code GET /staff/routes/{id}/path}, Ruling 937 L) — 화면을 열 때마다 구간별로
 * 지도 호출이 나가 일일 한도를 쓰고 07:30 확정 배치와 같은 서킷을 쓰는 것을 막는다.
 *
 * <p>키는 편성 id 와 지도에 보낸 <b>지점열(정차 순서·좌표·학원 기준점·방향이 만든 순서 있는 좌표)의 해시</b>다. 정차지가 바뀌거나 순서·좌표·
 * 방향이 바뀌면 키가 달라져 저절로 새로 계산하므로 지우는 곳이 따로 없고, 유효 시간({@link #TTL})이 지나면 도로 변경도 반영된다.
 * <b>Redis 오류로 조회가 5xx 가 되면 안 된다</b> — Redis 를 못 쓰는 동안은 쓰기를 이 인스턴스 안의 기억({@link LocalFallbackMemory})이 받고
 * 읽기도 거기서 읽어, 같은 편성 화면을 다시 열어도 지도를 매번 부르지 않는다. 같은 키의 계산은 {@link #findOrCompute} 가 한 번에 하나만
 * 돌려 여러 요청이 동시에 같은 경로를 열어도 지도 호출은 한 번이다. 조회는 적중 · 미적중을 각각 센다.
 */
@Component
public class RoutePathCache {

    /** 도로 경로는 도로망이 바뀌지 않는 한 같다 — 한 번 연 편성 화면을 6시간 안에 다시 열어도 지도를 부르지 않을 만큼 길게 둔다. */
    static final Duration TTL = Duration.ofHours(6);

    /** Redis 를 못 쓰는 동안 이 인스턴스가 경로를 들고 있는 시간 — 장애가 길어져도 낡은 도로 경로가 오래 남지 않게 한다. */
    private static final Duration FALLBACK_TTL = Duration.ofMinutes(30);

    /** 경로 하나가 좌표 수천 개라 상한을 작게 둔다 — 넘으면 만료된 항목부터, 그래도 가득이면 아무 항목 하나를 버린다. */
    private static final int FALLBACK_MAX_ENTRIES = 200;

    private static final String KEY_PREFIX = "schoolbus:route-path:";

    private final StringRedisTemplate redis;

    private final JsonMapper mapper;

    private final LocalFallbackMemory<CachedRoadRoute> whileRedisIsDown;

    private final Counter hits;

    private final Counter misses;

    /** 지금 계산 중인 키 — 같은 키의 다른 요청은 이 계산의 결과를 기다린다. */
    private final Map<String, CompletableFuture<CachedRoadRoute>> inFlight = new ConcurrentHashMap<>();

    private final RateLimitedWarn redisFailure = RateLimitedWarn.perMinute(
            org.slf4j.LoggerFactory.getLogger(RoutePathCache.class));

    public RoutePathCache(StringRedisTemplate redis, JsonMapper mapper, Clock clock, MeterRegistry meters) {
        this.redis = redis;
        this.mapper = mapper;
        this.whileRedisIsDown = new LocalFallbackMemory<>(clock, FALLBACK_MAX_ENTRIES);
        this.hits = meters.counter("schoolbus.cache.route_path.hit");
        this.misses = meters.counter("schoolbus.cache.route_path.miss");
    }

    /**
     * 캐시된 도로 경로와 그것을 계산한 시각(epoch millis).
     *
     * @param computedAtEpochMillis 지도에서 이 경로를 받아 온 시각 — 응답의 {@code computed_at}
     */
    public record CachedRoadRoute(RoadRoute route, long computedAtEpochMillis) {
    }

    /** 편성 id 와 지도에 보낼 지점열로 키를 만든다 — 지점열이 다르면 키가 다르다. */
    public String keyOf(Long routeId, List<GeoPoint> points) {
        String pointsText = points.stream()
                .map(point -> plain(point.lat()) + "," + plain(point.lng()))
                .collect(Collectors.joining(";"));
        return KEY_PREFIX + routeId + ":" + sha256Hex(pointsText);
    }

    /** 캐시된 경로 — Redis 에 없으면 이 인스턴스 안의 기억을 보고, 거기에도 없거나 값을 읽을 수 없으면 빈 값이다. */
    public Optional<CachedRoadRoute> find(String key) {
        Optional<CachedRoadRoute> found = readRedisThenMemory(key);
        (found.isPresent() ? hits : misses).increment();
        return found;
    }

    /**
     * 캐시에 있으면 그 경로를, 없으면 {@code computation} 을 돌려 그 결과를 돌려준다 — 같은 키를 동시에 부르면 하나만 계산하고 나머지는 그 결과(또는
     * 예외)를 기다려 받는다. 결과를 캐시에 둘지는 {@code computation} 이 정한다(직선 근사는 두지 않는다).
     *
     * <p>shortcut: 앞 계산이 끝나 키를 비운 바로 뒤에 들어온 요청은 다시 계산한다 — 그 사이에 경로가 캐시에 들어 있으므로 {@link #find} 가 먼저 막고,
     * 그 틈이 문제가 되면 계산 직전에 캐시를 한 번 더 읽는다.
     */
    public CachedRoadRoute findOrCompute(String key, Supplier<CachedRoadRoute> computation) {
        Optional<CachedRoadRoute> cached = find(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        CompletableFuture<CachedRoadRoute> mine = new CompletableFuture<>();
        CompletableFuture<CachedRoadRoute> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            return awaitOther(running);
        }
        try {
            CachedRoadRoute computed = computation.get();
            mine.complete(computed);
            return computed;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    /** 경로를 {@link #TTL} 동안 둔다 — Redis 쓰기 실패는 이 인스턴스 안의 기억이 받는다. */
    public void put(String key, CachedRoadRoute value) {
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(value), TTL);
            whileRedisIsDown.remove(key);
        } catch (RuntimeException e) {
            redisFailure.warn(e, "[routing] 도로 경로 캐시 쓰기 실패 — 이 인스턴스 안에 {} 동안 들고 있는다. key={}", FALLBACK_TTL, key);
            whileRedisIsDown.put(key, value, FALLBACK_TTL);
        }
    }

    private Optional<CachedRoadRoute> readRedisThenMemory(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json != null) {
                return Optional.of(mapper.readValue(json, CachedRoadRoute.class));
            }
        } catch (RuntimeException e) {
            redisFailure.warn(e, "[routing] 도로 경로 캐시 읽기 실패 — 이 인스턴스 안의 기억을 본다. key={}", key);
        }
        return whileRedisIsDown.get(key);
    }

    private static CachedRoadRoute awaitOther(CompletableFuture<CachedRoadRoute> running) {
        try {
            return running.join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException failure ? failure : e;
        }
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 은 모든 JVM 이 제공한다", e);
        }
    }
}
