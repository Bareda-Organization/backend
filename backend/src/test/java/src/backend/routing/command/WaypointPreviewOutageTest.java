package src.backend.routing.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.core.StringRedisTemplate;

import tools.jackson.databind.json.JsonMapper;

import src.backend.global.location.GeoPoint;
import src.backend.routing.command.WaypointPreviewCache.WaypointPreview;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.pipeline.ComputationSnapshot;
import src.backend.routing.pipeline.RouteComputation;
import testsupport.redis.Outage;
import testsupport.redis.OutageRedis;
import testsupport.redis.OutageRedisContext;

/**
 * Redis 가 멈추거나 답하지 않는 동안 경유 지점 미리보기 — 미리보기를 만든 같은 인스턴스에서 바로 배포하면 배포가 {@code 409 PREVIEW_STALE} 이 되지 않고(이 인스턴스 안의
 * 기억이 대신 든다), 배포한 뒤에는 같은 토큰으로 다시 배포되지 않는다. Redis 가 읽는 길 위에서 있던 일을 못 하니 지도 API 로 미리보기를 다시 만들게 하는 반복을 피한다(Ruling 919 B ⑤).
 */
class WaypointPreviewOutageTest {

    private static final long RUN_ID = 52L;

    private static OutageRedis redis;

    @BeforeAll
    static void startRedis() {
        redis = OutageRedis.start();
    }

    @AfterAll
    static void stopRedis() {
        redis.close();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Outage.class)
    @DisplayName("Redis 장애 중 만든 경유 지점 미리보기를 같은 인스턴스가 읽고, 지우면 다시 읽히지 않는다")
    void 장애_중에도_만든_미리보기를_읽고_지운다(Outage outage) {
        OutageRedisContext.runner(redis).run(context -> {
            RedisWaypointPreviewCache cache = new RedisWaypointPreviewCache(context.getBean(StringRedisTemplate.class),
                    JsonMapper.builder().build(), Clock.systemDefaultZone());
            WaypointPreview preview = new WaypointPreview("token-1", 12L, false, 2, "fp-1", 3L, computation());
            outage.begin(redis);
            try {
                for (int i = 0; i < 8; i++) {
                    cache.put(RUN_ID, LocalDate.now(), preview);
                }
                assertThat(cache.find(RUN_ID)).as("장애 중 저장한 미리보기").contains(preview);

                cache.evict(RUN_ID);
                assertThat(cache.find(RUN_ID)).as("배포 뒤 지운 미리보기").isEmpty();
            } finally {
                outage.end(redis);
            }
        });
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
