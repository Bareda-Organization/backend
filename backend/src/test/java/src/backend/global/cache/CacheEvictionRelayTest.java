package src.backend.global.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * R59 S-6 — 인스턴스 A 에서 계정이 차단되면 B 의 계정 상태 캐시도 1초 안에 비워져 다음 요청이 거절된다(Ruling 900 D). 신호를 놓쳐도 만료(5초)가 상한이다.
 * 인스턴스 하나 = 계정 상태 캐시 + 비움 중계 + 자기 Redis 연결. 두 인스턴스가 같은 가짜 계정 저장소(차단 목록)와 같은 Redis 를 본다.
 */
class CacheEvictionRelayTest extends RedisTestContainerBase {

    private static final long ACCOUNT = 4_242L;

    private final String channelSuffix = ":" + UUID.randomUUID();
    private final List<Instance> instances = new CopyOnWriteArrayList<>();
    private final Set<Long> blocked = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AccountRepository accounts = Mockito.mock(AccountRepository.class);
    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void blockedAccountsAreWhatTheFakeDatabaseSays() {
        Mockito.when(accounts.findIdsWithLostStanding(Mockito.anyCollection()))
                .thenAnswer(call -> ((java.util.Collection<Long>) call.getArgument(0)).stream().filter(blocked::contains).toList());
    }

    @AfterEach
    void closeAll() throws Exception {
        for (Instance instance : instances) {
            instance.close();
        }
    }

    @Test
    @DisplayName("S-6 A 에서 차단하고 비움 신호를 내면 B 의 다음 확인이 1초 안에 '권한 잃음' 으로 바뀐다")
    void 차단_신호는_다른_인스턴스에_1초_안에_닿는다() {
        Instance a = newInstance(true);
        Instance b = newInstance(true);
        assertThat(b.standing.isLost(ACCOUNT)).as("차단 전 B 가 캐시에 담은 값").isFalse();

        blocked.add(ACCOUNT);
        a.standing.evict(ACCOUNT);
        a.relay.publish(CacheEvictionRelay.ACCOUNT_STANDING, ACCOUNT);

        await().atMost(Duration.ofSeconds(1)).pollInterval(Duration.ofMillis(20))
                .untilAsserted(() -> assertThat(b.standing.isLost(ACCOUNT)).as("B 의 다음 확인").isTrue());
    }

    @Test
    @DisplayName("S-6 신호를 놓쳐도 B 는 만료(5초) 뒤 '권한 잃음' 으로 바꾼다")
    void 신호를_놓쳐도_만료가_상한이다() {
        Instance b = newInstance(true);
        assertThat(b.standing.isLost(ACCOUNT)).isFalse();

        blocked.add(ACCOUNT); // A 가 차단했지만 신호는 오지 않았다
        assertThat(b.standing.isLost(ACCOUNT)).as("만료 전에는 옛 값").isFalse();

        clock.advance(Duration.ofSeconds(5));
        assertThat(b.standing.isLost(ACCOUNT)).as("만료 5초 뒤").isTrue();
    }

    @Test
    @DisplayName("자기가 낸 신호는 자기에게 되돌아와도 한 번 더 비우지 않는다 — 다른 인스턴스 것만 처리")
    void 자기_신호는_건너뛴다() {
        Instance a = newInstance(false);
        Instance b = newInstance(false);
        AtomicLong evicted = new AtomicLong();
        a.relay.register(CacheEvictionRelay.ACCOUNT_STANDING, key -> evicted.incrementAndGet());
        AtomicLong evictedOnB = new AtomicLong();
        b.relay.register(CacheEvictionRelay.ACCOUNT_STANDING, key -> evictedOnB.incrementAndGet());

        a.relay.publish(CacheEvictionRelay.ACCOUNT_STANDING, ACCOUNT);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(evictedOnB.get()).isEqualTo(1));
        assertThat(evicted.get()).as("발행한 A 가 처리한 횟수").isZero();
    }

    /** 두 인스턴스가 서로의 신호를 받는 상태가 될 때까지 탐침을 주고받는다 — 구독이 성립하기 전의 메시지는 Redis 가 버린다. */
    private void awaitSubscribed(Instance a, Instance b) {
        AtomicLong probesOnA = new AtomicLong();
        AtomicLong probesOnB = new AtomicLong();
        a.relay.register("probe", key -> probesOnA.incrementAndGet());
        b.relay.register("probe", key -> probesOnB.incrementAndGet());
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).until(() -> {
            a.relay.publish("probe", 0);
            b.relay.publish("probe", 0);
            return probesOnA.get() > 0 && probesOnB.get() > 0;
        });
    }

    private Instance newInstance(boolean wireStanding) {
        Instance instance = new Instance(wireStanding);
        instances.add(instance);
        if (instances.size() > 1) {
            awaitSubscribed(instances.get(0), instance);
        }
        return instance;
    }

    private final class Instance {
        final LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        final RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        final CacheEvictionRelay relay;
        final AccountStanding standing = new AccountStanding(accounts, clock, Duration.ofSeconds(5));

        Instance(boolean wireStanding) {
            factory.afterPropertiesSet();
            container.setConnectionFactory(factory);
            container.afterPropertiesSet();
            container.start();
            relay = new CacheEvictionRelay(new StringRedisTemplate(factory), container, new SimpleMeterRegistry(), channelSuffix);
            if (wireStanding) {
                relay.register(CacheEvictionRelay.ACCOUNT_STANDING, standing::evict);
            }
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
