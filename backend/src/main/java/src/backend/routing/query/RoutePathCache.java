package src.backend.routing.query;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import tools.jackson.databind.json.JsonMapper;

import src.backend.global.common.logging.RateLimitedWarn;
import src.backend.global.location.GeoPoint;
import src.backend.routing.map.spec.RoadRoute;

/**
 * 지도 API 가 돌려준 편성 도로 경로의 Redis 캐시(API_SPEC §5.9 {@code GET /staff/routes/{id}/path}, Ruling 937 L) — 화면을 열 때마다 구간별로
 * 지도 호출이 나가 일일 한도를 쓰고 07:30 확정 배치와 같은 서킷을 쓰는 것을 막는다.
 *
 * <p>키는 편성 id 와 지도에 보낸 <b>지점열(정차 순서·좌표·학원 기준점·방향이 만든 순서 있는 좌표)의 해시</b>다. 정차지가 바뀌거나 순서·좌표·
 * 방향이 바뀌면 키가 달라져 저절로 새로 계산하므로 지우는 곳이 따로 없고, 유효 시간({@link #TTL})이 지나면 도로 변경도 반영된다.
 * <b>Redis 오류로 조회가 5xx 가 되면 안 된다</b> — 읽기 실패는 캐시 미스로, 쓰기 실패는 건너뜀으로 보고 지도 호출 결과를 그대로 답한다.
 */
@Component
@RequiredArgsConstructor
public class RoutePathCache {

    /** 도로 경로는 도로망이 바뀌지 않는 한 같다 — 한 번 연 편성 화면을 하루 안에 다시 열어도 지도를 부르지 않을 만큼 길게 둔다. */
    static final Duration TTL = Duration.ofHours(6);

    private static final String KEY_PREFIX = "schoolbus:route-path:";

    private final StringRedisTemplate redis;

    private final JsonMapper mapper;

    private final RateLimitedWarn redisFailure = RateLimitedWarn.perMinute(
            org.slf4j.LoggerFactory.getLogger(RoutePathCache.class));

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

    /** 캐시된 경로 — 없거나 Redis 가 안 되거나 값을 읽을 수 없으면 빈 값이다. */
    public Optional<CachedRoadRoute> find(String key) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? Optional.empty() : Optional.of(mapper.readValue(json, CachedRoadRoute.class));
        } catch (RuntimeException e) {
            redisFailure.warn(e, "[routing] 도로 경로 캐시 읽기 실패 — 지도를 불러 계산한다. key={}", key);
            return Optional.empty();
        }
    }

    /** 경로를 {@link #TTL} 동안 둔다 — 실패는 삼킨다(다음 조회가 다시 계산한다). */
    public void put(String key, CachedRoadRoute value) {
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(value), TTL);
        } catch (RuntimeException e) {
            redisFailure.warn(e, "[routing] 도로 경로 캐시 쓰기 실패 — 다음 조회가 다시 계산한다. key={}", key);
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
