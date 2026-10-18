package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.DefaultSubscriptionRegistry;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

import src.backend.observability.metrics.WebSocketPublishMetrics;
import testsupport.redis.Outage;
import testsupport.redis.OutageRedis;
import testsupport.redis.OutageRedisContext;

/**
 * 방송 중계(Redis Pub/Sub)가 멈추거나 답하지 않는 동안 — 이 인스턴스의 구독자에게는 직접 전송되고(Ruling 900 B), 서킷이 열린 뒤에는 Redis 를 기다리지 않아 방송이
 * 지연되지 않으며(Ruling 919), Redis 가 돌아오면 구독이 10초 안에 다시 서서 중계 경로로 돌아온다.
 */
class BroadcastRelayOutageTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-18T09:00:00+09:00");

    private static final String WATCHED = "/topic/students/7/run";

    /** 구독이 다시 서기까지 허용하는 시간 — 연결 복구 주기(5초)에 재구독 시간을 더한 상한. */
    private static final Duration SUBSCRIPTION_RECOVERY_LIMIT = Duration.ofSeconds(10);

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
    @DisplayName("Redis 장애 중에도 이 인스턴스의 구독자에게 방송이 직접 전송되고, 서킷이 열린 뒤에는 방송 지연이 없다")
    void 장애_중에도_구독자에게_직접_전송된다(Outage outage) {
        OutageRedisContext.runner(redis).run(context -> {
            SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
            MeterRegistry meters = new SimpleMeterRegistry();
            ThreadPoolTaskExecutor outbound = new ThreadPoolTaskExecutor();
            outbound.initialize();
            WebSocketBroadcastGateway gateway = new WebSocketBroadcastGateway(template, mock(WebSocketPublishMetrics.class),
                    outbound, Clock.systemUTC(), meters);
            watch(gateway, WATCHED);
            gateway.setRelay(new BroadcastRelay(context.getBean(StringRedisTemplate.class),
                    context.getBean(RedisMessageListenerContainer.class), JsonMapper.builder().build(), meters, ""));
            outage.begin(redis);
            try {
                int sends = OutageRedisContext.CALLS_TO_OPEN + 3;
                for (int i = 1; i <= sends; i++) {
                    Duration elapsed = OutageRedisContext.timeOf(() -> gateway.send(WATCHED, "position", 10L, NOW, new Object()));

                    assertThat(elapsed).as("%d번째 방송 시간".formatted(i))
                            .isLessThan(i <= OutageRedisContext.CALLS_TO_OPEN ? OutageRedisContext.BOUNDED_CALL
                                    : OutageRedisContext.SKIPPED_CALL);
                }

                verify(template, times(sends)).convertAndSend(eq(WATCHED), any(WebSocketEnvelope.class));
                assertThat(meters.get("schoolbus.ws.relay.publish.failed").tag("event", "position").counter().count())
                        .as("실패한 발행 수").isEqualTo(sends);
            } finally {
                outage.end(redis);
                outbound.shutdown();
            }
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Outage.class)
    @DisplayName("Redis 가 돌아오면 중계 구독이 10초 안에 다시 서서 방송이 Redis 를 거쳐 다시 도착한다")
    void 구독은_Redis_가_돌아온_뒤_10초_안에_복구된다(Outage outage) throws InterruptedException {
        OutageRedisContext.runner(redis).run(context -> {
            MeterRegistry meters = new SimpleMeterRegistry();
            BroadcastRelay relay = new BroadcastRelay(context.getBean(StringRedisTemplate.class),
                    context.getBean(RedisMessageListenerContainer.class), JsonMapper.builder().build(), meters, "");
            List<RelayedBroadcast> received = new CopyOnWriteArrayList<>();
            relay.subscribe(received::add);
            relay.publish("position", 10L, NOW, List.of(new BroadcastDelivery("ping", List.of(WATCHED))));
            awaitReceived(received, 1, Duration.ofSeconds(3));
            received.clear();

            outage.begin(redis);
            sleepQuietly(Duration.ofSeconds(2));
            outage.end(redis);

            long restoredAt = System.nanoTime();
            long deadline = restoredAt + SUBSCRIPTION_RECOVERY_LIMIT.toNanos();
            while (received.isEmpty() && System.nanoTime() < deadline) {
                OutageRedisContext.timeOf(() -> relay.publish("position", 10L, NOW,
                        List.of(new BroadcastDelivery("ping", List.of(WATCHED)))));
                sleepQuietly(Duration.ofMillis(300));
            }

            assertThat(received).as("Redis 가 돌아온 뒤 %s 안에 구독으로 받은 방송".formatted(SUBSCRIPTION_RECOVERY_LIMIT)).isNotEmpty();
        });
    }

    private static void awaitReceived(List<RelayedBroadcast> received, int count, Duration limit) {
        long deadline = System.nanoTime() + limit.toNanos();
        while (received.size() < count && System.nanoTime() < deadline) {
            sleepQuietly(Duration.ofMillis(50));
        }
        assertThat(received).as("정상 상태에서 구독으로 받은 방송").hasSizeGreaterThanOrEqualTo(count);
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void watch(WebSocketBroadcastGateway gateway, String destination) {
        SimpleBrokerMessageHandler broker = new SimpleBrokerMessageHandler(mock(SubscribableChannel.class),
                mock(MessageChannel.class), mock(SubscribableChannel.class), List.of("/topic"));
        SimpMessageHeaderAccessor subscribe = SimpMessageHeaderAccessor.create(SimpMessageType.SUBSCRIBE);
        subscribe.setSessionId("s1");
        subscribe.setSubscriptionId("sub-0");
        subscribe.setDestination(destination);
        ((DefaultSubscriptionRegistry) broker.getSubscriptionRegistry())
                .registerSubscription(MessageBuilder.createMessage(new byte[0], subscribe.getMessageHeaders()));
        gateway.setSimpleBrokerMessageHandler(broker);
    }
}
