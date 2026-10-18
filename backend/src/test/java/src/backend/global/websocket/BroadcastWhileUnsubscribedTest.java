package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.DefaultSubscriptionRegistry;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

import src.backend.global.redis.FailSoftRedisMessageListenerContainer;
import src.backend.global.redis.RedisSubscriptionRetrier;
import src.backend.observability.metrics.WebSocketPublishMetrics;
import testsupport.redis.OutageRedis;
import testsupport.redis.OutageRedisContext;

/**
 * Redis 가 돌아와 발행은 되는데 이 인스턴스의 중계 구독이 아직 서지 못한 틈(Ruling 900 B · 932) — 구독이 없으면 발행한 방송을 이 인스턴스가 받지 못하므로, 1대 운영에서는 구독자 모두가
 * 방송을 놓친다. 위치는 다음 송신이 메우지만 {@code run_started} · 비상 신고 같은 이벤트는 되풀이되지 않는다. 그래서 구독이 서지 않은 동안은 이 인스턴스의 구독자에게 직접 보내고 발행도 한다.
 * 이 코드가 낼 수 있는 쪽은 중복이다 — 구독이 다시 서는 바로 그 순간 직접 전송한 방송이 중계로도 한 번 더 오는 경우. 손실은 없다.
 */
class BroadcastWhileUnsubscribedTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-18T09:00:00+09:00");

    private static final String WATCHED = "/topic/students/7/run";

    private static OutageRedis redis;

    @BeforeAll
    static void startRedis() {
        redis = OutageRedis.start();
    }

    @AfterAll
    static void stopRedis() {
        redis.close();
    }

    @Test
    @DisplayName("구독이 서지 않은 동안의 방송은 이 인스턴스의 구독자에게 정확히 한 번 가고, 구독이 선 뒤의 방송도 한 번만 간다")
    void 구독이_없는_동안_방송은_직접_한_번_간다() {
        OutageRedisContext.runner(redis).run(context -> {
            // Redis 는 멀쩡하고 이 인스턴스의 구독만 없다 — 재시도를 멈추고 컨테이너를 멈춰 그 틈을 고정한다
            context.getBean(RedisSubscriptionRetrier.class).stop();
            FailSoftRedisMessageListenerContainer container = context.getBean(FailSoftRedisMessageListenerContainer.class);
            container.stop();
            SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
            SimpleMeterRegistry meters = new SimpleMeterRegistry();
            ThreadPoolTaskExecutor outbound = new ThreadPoolTaskExecutor();
            outbound.initialize();
            WebSocketBroadcastGateway gateway = new WebSocketBroadcastGateway(template, mock(WebSocketPublishMetrics.class),
                    outbound, Clock.systemUTC(), meters);
            watch(gateway, WATCHED);
            gateway.setRelay(new BroadcastRelay(context.getBean(StringRedisTemplate.class), container,
                    JsonMapper.builder().build(), meters, ""));
            try {
                assertThat(container.isSubscribed()).as("Redis 는 멀쩡하고 구독은 아직 없다").isFalse();

                gateway.send(WATCHED, "run_started", 10L, NOW, new Object());

                verify(template, timeout(3000).times(1)).convertAndSend(eq(WATCHED), any(WebSocketEnvelope.class));
                Thread.sleep(1500);
                verify(template, times(1)).convertAndSend(eq(WATCHED), any(WebSocketEnvelope.class));

                gateway.subscribeToRelay();
                long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                while (!container.isSubscribed() && System.nanoTime() < deadline) {
                    container.retrySubscription();
                    Thread.sleep(300);
                }
                assertThat(container.isSubscribed()).as("재시도 뒤 15초 안에 구독").isTrue();

                gateway.send(WATCHED, "run_started", 10L, NOW, new Object());

                verify(template, timeout(3000).times(2)).convertAndSend(eq(WATCHED), any(WebSocketEnvelope.class));
                Thread.sleep(1500);
                verify(template, times(2)).convertAndSend(eq(WATCHED), any(WebSocketEnvelope.class));
            } finally {
                outbound.shutdown();
            }
        });
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
