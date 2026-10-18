package src.backend.global.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import src.backend.account.repository.AccountRepository;
import src.backend.global.security.AccountStanding;
import testsupport.redis.RedisTestContainerBase;

/**
 * 계정 상태 캐시({@link AccountStanding})의 낡은 값은 신호를 놓쳐도 만료(5초)가 상한이다. 비움 중계({@link CacheEvictionRelay})는 다른 인스턴스가 Redis 에 낸 신호는 처리하고
 * 자기가 낸 신호는 되돌아와도 처리하지 않는다(Ruling 900 D). 인스턴스 하나 = 계정 상태 캐시 + 비움 중계 + 자기 Redis 연결이고, 다른 인스턴스가 낸 신호는 같은 채널에 직접 낸 메시지로 대신한다.
 */
class CacheEvictionRelayTest extends RedisTestContainerBase {

    private static final long ACCOUNT = 4_242L;

    private static final long OWN_KEY = 7_001L;

    private static final long FOREIGN_KEY = 7_002L;

    private static final String FOREIGN_ORIGIN = "other-instance";

    private final String channelSuffix = ":" + UUID.randomUUID();
    private final Set<Long> blocked = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AccountRepository accounts = Mockito.mock(AccountRepository.class);
    private final MutableClock clock = new MutableClock();
    private Instance instance;

    @BeforeEach
    void blockedAccountsAreWhatTheFakeDatabaseSays() {
        Mockito.when(accounts.findIdsWithLostStanding(Mockito.anyCollection()))
                .thenAnswer(call -> ((java.util.Collection<Long>) call.getArgument(0)).stream().filter(blocked::contains).toList());
        instance = new Instance();
    }

    @AfterEach
    void close() throws Exception {
        instance.close();
    }

    @Test
    @DisplayName("신호를 놓쳐도 계정 상태 캐시는 만료(5초) 뒤 '권한 잃음' 으로 바꾼다")
    void 신호를_놓쳐도_만료가_상한이다() {
        instance.relay.register(CacheEvictionRelay.ACCOUNT_STANDING, instance.standing::evict);
        assertThat(instance.standing.isLost(ACCOUNT)).isFalse();

        blocked.add(ACCOUNT); // 다른 인스턴스가 차단했지만 신호는 오지 않았다
        assertThat(instance.standing.isLost(ACCOUNT)).as("만료 전에는 옛 값").isFalse();

        clock.advance(Duration.ofSeconds(5));
        assertThat(instance.standing.isLost(ACCOUNT)).as("만료 5초 뒤").isTrue();
    }

    @Test
    @DisplayName("다른 인스턴스가 낸 신호는 처리하고, 자기가 낸 신호는 Redis 를 돌아와도 처리하지 않는다")
    void 자기_신호는_건너뛴다() {
        Set<Long> evictedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();
        AtomicLong probes = new AtomicLong();
        instance.relay.register(CacheEvictionRelay.ACCOUNT_STANDING, evictedKeys::add);
        instance.relay.register("probe", key -> probes.incrementAndGet());
        awaitSubscribed(probes);

        instance.relay.publish(CacheEvictionRelay.ACCOUNT_STANDING, OWN_KEY);
        instance.foreignSignal(CacheEvictionRelay.ACCOUNT_STANDING, FOREIGN_KEY);

        // 같은 채널의 메시지는 발행 순서대로 오므로, 다른 인스턴스 신호(FOREIGN_KEY)가 처리됐을 때는 앞서 낸 자기 신호(OWN_KEY)도 이미 도착한 뒤다.
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(evictedKeys).as("다른 인스턴스 신호 처리").contains(FOREIGN_KEY));
        assertThat(evictedKeys).as("자기 신호는 처리하지 않는다").doesNotContain(OWN_KEY);
    }

    /** 이 인스턴스의 구독이 성립할 때까지 다른 인스턴스 이름의 탐침을 보낸다 — 구독이 성립하기 전의 메시지는 Redis 가 버린다. */
    private void awaitSubscribed(AtomicLong probes) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).until(() -> {
            instance.foreignSignal("probe", 0);
            return probes.get() > 0;
        });
    }

    private final class Instance {
        final LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        final RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        final StringRedisTemplate redis;
        final CacheEvictionRelay relay;
        final AccountStanding standing = new AccountStanding(accounts, clock, Duration.ofSeconds(5));

        Instance() {
            factory.afterPropertiesSet();
            container.setConnectionFactory(factory);
            container.afterPropertiesSet();
            container.start();
            redis = new StringRedisTemplate(factory);
            relay = new CacheEvictionRelay(redis, container, new SimpleMeterRegistry(), channelSuffix);
        }

        /** 다른 인스턴스가 낸 것처럼 발행자 이름이 다른 신호를 같은 채널에 낸다. */
        void foreignSignal(String cache, long key) {
            redis.convertAndSend("schoolbus:relay:cache-evict" + channelSuffix, FOREIGN_ORIGIN + "|" + cache + "|" + key);
        }

        void close() throws Exception {
            container.destroy();
            factory.destroy();
        }
    }

    /** 시험이 시각을 앞으로 돌리는 시계. */
    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2030-04-01T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
