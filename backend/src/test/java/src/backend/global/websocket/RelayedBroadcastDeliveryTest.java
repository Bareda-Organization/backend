package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import src.backend.observability.metrics.WebSocketPublishMetrics;

import tools.jackson.databind.json.JsonMapper;

/**
 * S-2 · S-3 — 중계로 받은 이벤트는 자기 구독자가 있는 목적지에만 나가고(S-2), Redis 발행이 실패하면 자기 구독자에게 직접 나간다(S-3, Ruling 900 A · B).
 * 송신 템플릿과 구독 등록부만 가짜 · 진짜 조합이고, 실제 Redis 를 멈추지 않는다 — 발행 실패는 던지는 Redis 템플릿으로 만든다.
 */
class RelayedBroadcastDeliveryTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-18T09:00:00+09:00");

    private static final String WATCHED = "/topic/students/7/run";

    private final SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ThreadPoolTaskExecutor outbound = new ThreadPoolTaskExecutor();
    private WebSocketBroadcastGateway gateway;

    @BeforeEach
    void watchOneDestination() {
        outbound.initialize();
        gateway = new WebSocketBroadcastGateway(template, mock(WebSocketPublishMetrics.class), outbound, Clock.systemUTC(),
                meters);
        SimpleBrokerMessageHandler broker = new SimpleBrokerMessageHandler(mock(SubscribableChannel.class),
                mock(MessageChannel.class), mock(SubscribableChannel.class), List.of("/topic"));
        SimpMessageHeaderAccessor subscribe = SimpMessageHeaderAccessor.create(SimpMessageType.SUBSCRIBE);
        subscribe.setSessionId("s1");
        subscribe.setSubscriptionId("sub-0");
        subscribe.setDestination(WATCHED);
        ((DefaultSubscriptionRegistry) broker.getSubscriptionRegistry())
                .registerSubscription(MessageBuilder.createMessage(new byte[0], subscribe.getMessageHeaders()));
        gateway.setSimpleBrokerMessageHandler(broker);
    }

    @AfterEach
    void stopExecutor() {
        outbound.shutdown();
    }

    @Test
    @DisplayName("S-2 중계로 받은 위치는 이 인스턴스에 구독자가 있는 목적지에만 전송하고 나머지 21곳은 직렬화·전송하지 않는다")
    void 구독자_없는_목적지는_전송_0() {
        List<String> destinations = LongStream.rangeClosed(1, 22).mapToObj("/topic/students/%d/run"::formatted).toList();

        gateway.deliverLocal(new RelayedBroadcast("position", 10L, NOW,
                List.of(new BroadcastDelivery(new Object(), destinations))));

        verify(template, times(1)).convertAndSend(eq(WATCHED), any(WebSocketEnvelope.class));
        verify(template, never()).convertAndSend(eq("/topic/students/1/run"), any(Object.class));
        verify(template, times(1)).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("S-3 Redis 발행이 실패하면 이 인스턴스의 구독자에게는 직접 전송하고 실패 지표가 1 오른다")
    void 발행_실패는_직접_전송과_지표() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        willThrow(new RedisConnectionFailureException("Redis 연결 끊김")).given(redis).convertAndSend(anyString(), anyString());
        gateway.setRelay(new BroadcastRelay(redis, mock(RedisMessageListenerContainer.class), JsonMapper.builder().build(), meters, ""));

        gateway.send(WATCHED, "position", 10L, NOW, new Object());

        verify(template, times(1)).convertAndSend(eq(WATCHED), any(WebSocketEnvelope.class));
        assertThat(meters.get("schoolbus.ws.relay.publish.failed").tag("event", "position").counter().count())
                .as("발행 실패 1건이 지표에 반영돼야 한다").isEqualTo(1.0);
    }
}
