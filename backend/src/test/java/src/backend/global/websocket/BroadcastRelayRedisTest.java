package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
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
import testsupport.redis.RedisTestContainerBase;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 Redis 를 사이에 둔 인스턴스 하나. 이벤트 1건은 Redis 메시지 1건이고, 인스턴스는 그 메시지를 받아 자기 구독자가 있는 목적지에만 보낸다.
 * 인스턴스 = 게이트웨이 + 중계 + 자기 구독 등록부 + 자기 송신 템플릿(가짜), 실제 연결은 공용 Redis 컨테이너다.
 */
class BroadcastRelayRedisTest extends RedisTestContainerBase {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-18T09:00:00+09:00");

    private static final String SUBSCRIBED = "/topic/students/1/run";
    private static final String ALSO_SUBSCRIBED = "/topic/admin/live";
    private static final String UNSUBSCRIBED = "/topic/students/2/run";
    private static final String ALSO_UNSUBSCRIBED = "/topic/students/3/run";

    private final String suffix = ":" + UUID.randomUUID();
    private final JsonMapper mapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    private final List<Node> nodes = new CopyOnWriteArrayList<>();
    private final List<String> redisMessages = new CopyOnWriteArrayList<>();
    private LettuceConnectionFactory spyFactory;
    private RedisMessageListenerContainer spyContainer;

    private record Payload(BigDecimal lat, Long studentId, String currentStopName) {
    }

    @BeforeEach
    void openSpyOnChannel() {
        spyFactory = newConnectionFactory();
        spyContainer = new RedisMessageListenerContainer();
        spyContainer.setConnectionFactory(spyFactory);
        spyContainer.addMessageListener((message, pattern) -> redisMessages.add(new String(message.getBody())),
                new ChannelTopic("schoolbus:relay:ws" + suffix));
        spyContainer.afterPropertiesSet();
        spyContainer.start();
    }

    @AfterEach
    void closeAll() throws Exception {
        for (Node node : nodes) {
            node.close();
        }
        spyContainer.destroy();
        spyFactory.destroy();
    }

    @Test
    @DisplayName("위치 1건은 Redis 메시지 1건이고, 인스턴스는 자기 구독자가 있는 목적지에만 보낸다")
    void 인스턴스는_자기_구독자에게만_보낸다() throws Exception {
        Node node = newNode(Set.of(SUBSCRIBED, ALSO_SUBSCRIBED));
        awaitSubscribed(node);

        node.gateway.broadcast("position", 10L, NOW, List.of(
                new BroadcastDelivery(new Payload(new BigDecimal("37.5000"), 7L, "정문"),
                        List.of(SUBSCRIBED, UNSUBSCRIBED, ALSO_UNSUBSCRIBED, ALSO_SUBSCRIBED))));

        verify(node.template, timeout(5000)).convertAndSend(eq(SUBSCRIBED), any(WebSocketEnvelope.class));
        verify(node.template, timeout(5000)).convertAndSend(eq(ALSO_SUBSCRIBED), any(WebSocketEnvelope.class));
        verify(node.template, never()).convertAndSend(eq(UNSUBSCRIBED), any(Object.class));
        verify(node.template, never()).convertAndSend(eq(ALSO_UNSUBSCRIBED), any(Object.class));
        assertThat(redisMessages).as("이벤트 1건이 Redis 에 낸 메시지 수(준비용 탐침 제외)")
                .filteredOn(text -> text.contains("\"event\":\"position\"")).hasSize(1);
    }

    @Test
    @DisplayName("중계를 거친 봉투의 payload 는 직접 보냈을 때와 같은 JSON 이다 — 키 이름 · 식별자 문자열 · 소수 자릿수")
    void 중계를_거쳐도_선_위의_JSON_은_같다() throws Exception {
        Node node = newNode(Set.of(SUBSCRIBED));
        awaitSubscribed(node);
        Payload payload = new Payload(new BigDecimal("37.5000"), 7L, "정문");

        node.gateway.send(SUBSCRIBED, "position", 10L, NOW, payload);

        ArgumentCaptor<WebSocketEnvelope> sent = ArgumentCaptor.forClass(WebSocketEnvelope.class);
        verify(node.template, timeout(5000)).convertAndSend(eq(SUBSCRIBED), sent.capture());
        assertThat(mapper.writeValueAsString(sent.getValue()))
                .isEqualTo(mapper.writeValueAsString(new WebSocketEnvelope("position", 10L, NOW, payload)));
    }

    /** 인스턴스가 자기 발행을 Redis 에서 받을 수 있는 상태가 될 때까지 탐침 이벤트를 반복해 보낸다 — 구독 성립 전의 메시지는 Redis 가 버린다. */
    private void awaitSubscribed(Node node) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            node.gateway.send(ALSO_SUBSCRIBED, "probe", 0L, NOW, new Object());
            verify(node.template, timeout(200).atLeastOnce()).convertAndSend(eq(ALSO_SUBSCRIBED), any(WebSocketEnvelope.class));
        });
        clearInvocations(node.template);
        redisMessages.clear();
    }

    private Node newNode(Set<String> watched) {
        Node node = new Node(watched);
        nodes.add(node);
        return node;
    }

    private LettuceConnectionFactory newConnectionFactory() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        return factory;
    }

    /** 인스턴스 — 자기 구독 등록부 · 송신 템플릿 · Redis 연결을 가진다. */
    private final class Node {
        final SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        final ThreadPoolTaskExecutor outbound = new ThreadPoolTaskExecutor();
        final LettuceConnectionFactory factory = newConnectionFactory();
        final RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        final WebSocketBroadcastGateway gateway;

        Node(Set<String> watched) {
            outbound.initialize();
            StringRedisTemplate redis = new StringRedisTemplate(factory);
            container.setConnectionFactory(factory);
            container.afterPropertiesSet();
            container.start();
            SimpleMeterRegistry meters = new SimpleMeterRegistry();
            gateway = new WebSocketBroadcastGateway(template, mock(WebSocketPublishMetrics.class), outbound,
                    Clock.systemUTC(), meters);
            gateway.setSimpleBrokerMessageHandler(brokerWatching(watched));
            gateway.setRelay(new BroadcastRelay(redis, container, mapper, meters, suffix));
            gateway.subscribeToRelay();
        }

        void close() throws Exception {
            container.destroy();
            factory.destroy();
            outbound.shutdown();
        }
    }

    private static SimpleBrokerMessageHandler brokerWatching(Set<String> destinations) {
        SimpleBrokerMessageHandler broker = new SimpleBrokerMessageHandler(mock(SubscribableChannel.class),
                mock(MessageChannel.class), mock(SubscribableChannel.class), List.of("/topic"));
        int sub = 0;
        for (String destination : destinations) {
            SimpMessageHeaderAccessor subscribe = SimpMessageHeaderAccessor.create(SimpMessageType.SUBSCRIBE);
            subscribe.setSessionId("s-" + sub);
            subscribe.setSubscriptionId("sub-" + sub++);
            subscribe.setDestination(destination);
            ((DefaultSubscriptionRegistry) broker.getSubscriptionRegistry())
                    .registerSubscription(MessageBuilder.createMessage(new byte[0], subscribe.getMessageHeaders()));
        }
        return broker;
    }
}
