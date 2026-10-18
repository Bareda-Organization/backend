package src.backend.global.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import testsupport.redis.Outage;
import testsupport.redis.OutageRedis;
import testsupport.redis.OutageRedisContext;

/**
 * 캐시 비움 신호 발행은 Redis 가 멈추거나 답하지 않아도 예외 없이 끝나고 실패를 센다 — 신호를 못 받은 다른 인스턴스의 캐시는 각자의 만료가 낡은 값의 상한이다(Ruling 916 A).
 * 서킷이 열린 뒤에는 Redis 를 기다리지 않으므로 상태 변경 트랜잭션의 커밋 뒤 처리가 신호 발행에 지연되지 않는다(Ruling 919).
 */
class CacheEvictionRelayOutageTest {

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
    @DisplayName("Redis 장애 중 비움 신호 발행은 예외 없이 시간 상한 안에 끝나고 실패 지표가 오른다")
    void 장애_중_비움_신호_발행은_조용히_센다(Outage outage) {
        OutageRedisContext.runner(redis).run(context -> {
            MeterRegistry meters = new SimpleMeterRegistry();
            CacheEvictionRelay relay = new CacheEvictionRelay(context.getBean(StringRedisTemplate.class),
                    context.getBean(RedisMessageListenerContainer.class), meters, "");
            outage.begin(redis);
            try {
                int publishes = OutageRedisContext.CALLS_TO_OPEN + 3;
                for (int i = 1; i <= publishes; i++) {
                    long key = i;
                    Duration elapsed = OutageRedisContext.timeOf(() -> relay.publish(CacheEvictionRelay.ACCOUNT_STANDING, key));

                    assertThat(elapsed).as("%d번째 발행 시간".formatted(i))
                            .isLessThan(i <= OutageRedisContext.CALLS_TO_OPEN ? OutageRedisContext.BOUNDED_CALL
                                    : OutageRedisContext.SKIPPED_CALL);
                }

                assertThat(meters.get("schoolbus.cache.evict.relay.failed").tag("cache", CacheEvictionRelay.ACCOUNT_STANDING)
                        .counter().count()).as("실패한 발행 수").isEqualTo(publishes);
            } finally {
                outage.end(redis);
            }
        });
    }
}
