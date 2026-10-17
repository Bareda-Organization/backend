package src.backend.routing.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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

import src.backend.global.location.GeoPoint;
import src.backend.routing.command.WaypointPreviewCache.WaypointPreview;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.pipeline.ComputationSnapshot;
import src.backend.routing.pipeline.RouteComputation;
import testsupport.redis.RedisTestContainerBase;

import tools.jackson.databind.json.JsonMapper;

/**
 * S-5 — 경유 지점 미리보기도 Redis 에 있어 만든 인스턴스와 배포하는 인스턴스가 달라도 된다(Ruling 900 C). 만료는 그 회차의 운행일이 끝나는 시각이다.
 * Redis 오류는 없음으로 읽어 배포가 기존 409 PREVIEW_STALE 로 답하게 한다(K4).
 */
class RedisWaypointPreviewCacheTest extends RedisTestContainerBase {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 고정 시각 — KST 오전 10시. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T01:00:00Z"), SEOUL);

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final long runId = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L);
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private RedisWaypointPreviewCache instanceA;
    private RedisWaypointPreviewCache instanceB;

    @BeforeEach
    void connect() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        instanceA = new RedisWaypointPreviewCache(redis, mapper, CLOCK);
        instanceB = new RedisWaypointPreviewCache(new StringRedisTemplate(factory), mapper, CLOCK);
    }

    @AfterEach
    void close() {
        instanceA.evict(runId);
        factory.destroy();
    }

    @Test
    @DisplayName("A 가 만든 미리보기를 B 가 같은 내용으로 읽고, 배포한 B 가 지우면 A 에서도 사라진다")
    void 한_인스턴스가_만들고_다른_인스턴스가_배포한다() {
        WaypointPreview preview = previewOf("token-1");

        instanceA.put(runId, TODAY, preview);

        assertThat(instanceB.find(runId)).contains(preview);
        instanceB.evict(runId);
        assertThat(instanceA.find(runId)).isEmpty();
    }

    @Test
    @DisplayName("만료는 운행일이 끝나는 시각(다음 날 0시 KST) — 10:00 에 넣으면 14시간")
    void 운행일이_끝나면_만료된다() {
        instanceA.put(runId, TODAY, previewOf("token-1"));

        Long seconds = redis.getExpire("schoolbus:waypoint-preview:" + runId);

        assertThat(seconds).isBetween(14L * 3600 - 60, 14L * 3600);
    }

    @Test
    @DisplayName("이미 운행일이 끝난 회차의 미리보기는 저장하지 않는다")
    void 지난_운행일은_저장하지_않는다() {
        instanceA.put(runId, TODAY.minusDays(1), previewOf("old"));

        assertThat(instanceB.find(runId)).isEmpty();
    }

    @Test
    @DisplayName("새 미리보기는 같은 회차의 앞 미리보기를 덮어쓴다 — 회차당 1건")
    void 새_미리보기가_앞_것을_덮어쓴다() {
        instanceA.put(runId, TODAY, previewOf("first"));
        instanceA.put(runId, TODAY, previewOf("second"));

        assertThat(instanceB.find(runId)).map(WaypointPreview::token).contains("second");
    }

    @Test
    @DisplayName("Redis 가 끊기면 읽기는 없음이고 쓰기·지우기는 예외 없이 지나간다(K4)")
    void Redis_오류는_없음으로_읽는다() {
        StringRedisTemplate broken = org.mockito.Mockito.mock(StringRedisTemplate.class);
        org.mockito.BDDMockito.given(broken.opsForValue()).willThrow(new RedisConnectionFailureException("끊김"));
        org.mockito.BDDMockito.given(broken.delete(org.mockito.ArgumentMatchers.anyString()))
                .willThrow(new RedisConnectionFailureException("끊김"));
        RedisWaypointPreviewCache cache = new RedisWaypointPreviewCache(broken, mapper, CLOCK);

        assertThat(cache.find(runId)).isEmpty();
        cache.put(runId, TODAY, previewOf("x"));
        cache.evict(runId);
    }

    private static WaypointPreview previewOf(String token) {
        GeoPoint point = new GeoPoint(new BigDecimal("37.501234"), new BigDecimal("127.039876"));
        RouteComputation computation = new RouteComputation(List.of(OrderedStop.ofStop(11L, 1, point)), 25,
                new BigDecimal("12.34"), List.of(OffsetDateTime.parse("2026-09-30T08:00:00+09:00")), List.of(),
                List.of(point), new ComputationSnapshot("naver-directions", Map.of("early_minutes", 5),
                        RouteVersionSource.WAYPOINT, false));
        return new WaypointPreview(token, 5L, false, 2, "fp", 7L, computation);
    }
}
