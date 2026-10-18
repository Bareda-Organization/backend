package src.backend.global.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

import src.backend.global.cache.CacheEvictionRelay;
import src.backend.global.websocket.BroadcastDelivery;
import src.backend.global.websocket.BroadcastRelay;
import testsupport.redis.Outage;
import testsupport.redis.OutageRedis;
import testsupport.redis.OutageRedisContext;

/**
 * Redis 가 멈춘 채 기동해 구독 재시도가 여러 번 돈 뒤 Redis 가 돌아오면 — 구독은 한 벌만 서고(두 벌이면 같은 메시지가 두 번 온다), 재시도 스레드는 하나이며 늘지 않고,
 * 방송 중계 · 캐시 비움 두 채널이 재시작 없이 15초 안에 돌아와 메시지를 정확히 한 번씩 받는다. 재시도 간격은 시험에서 0.1초로 줄여 60번 넘게 돌게 한다.
 */
class RedisSubscriptionRetrierTest {

    private static final String RELAY_CHANNEL = "schoolbus:relay:ws";

    private static final String EVICTION_CHANNEL = "schoolbus:relay:cache-evict";

    private static final int MIN_CYCLES = 5;

    private static final Duration RECOVERY_LIMIT = Duration.ofSeconds(15);

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
    @DisplayName("재시도가 5번 넘게 돈 뒤 Redis 가 돌아오면 두 채널 모두 구독 한 벌로 15초 안에 돌아와 메시지를 정확히 한 번씩 받는다")
    void 재시도_뒤_구독은_한_벌이고_메시지는_한_번(Outage outage) {
        // 같은 시험 JVM 에 캐시된 다른 스프링 컨텍스트도 같은 이름의 스레드를 가진다 — 이 시험이 만든 것만 세려고 시작 전 수를 뺀다
        int otherRetriers = threadCount("redis-subscription-retrier");
        int otherLettuce = threadCount("lettuce-");
        int otherReceivers = threadCount("relay-receive-");
        outage.begin(redis);
        try {
            OutageRedisContext.runner(redis).withPropertyValues("app.relay.subscription-retry-ms=100").run(context -> {
                RedisSubscriptionRetrier retrier = context.getBean(RedisSubscriptionRetrier.class);
                StringRedisTemplate template = context.getBean(StringRedisTemplate.class);
                RedisMessageListenerContainer container = context.getBean(RedisMessageListenerContainer.class);
                SimpleMeterRegistry meters = new SimpleMeterRegistry();
                AtomicInteger relayed = new AtomicInteger();
                BroadcastRelay relay = new BroadcastRelay(template, container, JsonMapper.builder().build(), meters, "");
                relay.subscribe(received -> relayed.incrementAndGet());
                AtomicInteger evicted = new AtomicInteger();
                CacheEvictionRelay receiver = new CacheEvictionRelay(template, container, meters, "");
                receiver.register(CacheEvictionRelay.ACCOUNT_STANDING, key -> evicted.incrementAndGet());
                CacheEvictionRelay sender = new CacheEvictionRelay(template, container, meters, "");

                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (retrier.retryAttempts() < MIN_CYCLES && System.nanoTime() < deadline) {
                    Thread.sleep(100);
                }
                assertThat(retrier.retryAttempts()).as("Redis 가 멈춘 동안 돈 재시도 횟수").isGreaterThanOrEqualTo(MIN_CYCLES);
                assertThat(retrierThreadCount() - otherRetriers).as("재시도 스레드").isEqualTo(1);
                // 재시도가 한 번에 스레드를 하나씩 새게 하면 60번 뒤 상한(Lettuce 풀은 코어 수까지만 늘어난다)을 넘는다 — 연결 시간 상한까지 기다리는 얼린 Redis 는 12초 안에 도는 만큼만 본다
                long manyCycles = MIN_CYCLES * 12;
                long cyclesDeadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
                while (retrier.retryAttempts() < manyCycles && System.nanoTime() < cyclesDeadline) {
                    Thread.sleep(100);
                }
                assertThat(retrier.retryAttempts()).as("스레드 수를 재기 전까지 돈 재시도").isGreaterThanOrEqualTo(MIN_CYCLES);
                assertThat(retrierThreadCount() - otherRetriers).as("재시도 스레드").isEqualTo(1);
                assertThat(threadCount("relay-receive-") - otherReceivers).as("방송 수신 실행기 스레드(메시지가 올 때 만들어진다)").isLessThanOrEqualTo(1);
                assertThat(threadCount("lettuce-") - otherLettuce).as("Lettuce 스레드(이벤트 루프 풀 둘은 코어 수까지만 늘고 타이머는 1개)")
                        .isLessThanOrEqualTo(2 * Runtime.getRuntime().availableProcessors() + 1);

                outage.end(redis);
                RedisClient client = RedisClient.create("redis://%s:%d".formatted(redis.host(), redis.port()));
                try {
                RedisCommands<String, String> observer = connectObserver(client);
                awaitSubscribers(observer, 1);

                assertThat(retrierThreadCount() - otherRetriers).as("복구 뒤 재시도 스레드").isEqualTo(1);

                relay.publish("run_started", 10L, java.time.OffsetDateTime.now(),
                        List.of(new BroadcastDelivery("ping", List.of("/topic/students/7/run"))));
                sender.publish(CacheEvictionRelay.ACCOUNT_STANDING, 5L);
                Thread.sleep(2000);

                assertThat(relayed.get()).as("방송 중계 메시지를 받은 횟수").isEqualTo(1);
                assertThat(evicted.get()).as("캐시 비움 메시지를 받은 횟수").isEqualTo(1);
                assertThat(numSubscribers(observer, RELAY_CHANNEL)).as("방송 중계 채널 구독자 수").isEqualTo(1);
                assertThat(numSubscribers(observer, EVICTION_CHANNEL)).as("캐시 비움 채널 구독자 수").isEqualTo(1);
                } finally {
                    client.shutdown();
                }
            });
        } finally {
            try {
                outage.end(redis);
            } catch (RuntimeException alreadyRestored) {
                // 이미 되돌려져 있다
            }
        }
    }

    /** Redis 가 돌아와 연결이 될 때까지 다시 시도해 관찰용 연결을 만든다. */
    private static RedisCommands<String, String> connectObserver(RedisClient client) throws InterruptedException {
        long deadline = System.nanoTime() + RECOVERY_LIMIT.toNanos();
        while (true) {
            try {
                RedisCommands<String, String> commands = client.connect().sync();
                commands.setTimeout(Duration.ofSeconds(1));
                return commands;
            } catch (RuntimeException notYet) {
                if (System.nanoTime() > deadline) {
                    throw notYet;
                }
                Thread.sleep(200);
            }
        }
    }

    private static void awaitSubscribers(RedisCommands<String, String> observer, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + RECOVERY_LIMIT.toNanos();
        String last = "응답 없음";
        while (System.nanoTime() < deadline) {
            try {
                long relayCount = numSubscribers(observer, RELAY_CHANNEL);
                long evictionCount = numSubscribers(observer, EVICTION_CHANNEL);
                last = "방송 중계 %d · 캐시 비움 %d".formatted(relayCount, evictionCount);
                if (relayCount == expected && evictionCount == expected) {
                    return;
                }
            } catch (RuntimeException stillDown) {
                last = stillDown.getClass().getSimpleName();
            }
            Thread.sleep(200);
        }
        assertThat(last).as("Redis 가 돌아온 뒤 %s 안의 채널 구독자 수 마지막 관측".formatted(RECOVERY_LIMIT)).isEqualTo("방송 중계 %d · 캐시 비움 %d".formatted(expected, expected));
    }

    /** {@code PUBSUB NUMSUB} 의 채널 구독 연결 수 — 구독이 두 벌이면 2 가 된다. 서킷을 거치지 않는 관찰용 연결로 읽는다. */
    private static long numSubscribers(RedisCommands<String, String> observer, String channel) {
        return observer.pubsubNumsub(channel).get(channel);
    }

    private static int retrierThreadCount() {
        return (int) Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().equals("redis-subscription-retrier") && t.isAlive()).count();
    }

    private static int threadCount(String namePrefix) {
        return (int) Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive() && t.getName().startsWith(namePrefix)).count();
    }
}
