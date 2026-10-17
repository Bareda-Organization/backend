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
 * S-1 · S-2 — 실제 Redis 를 사이에 둔 인스턴스 2대. A 가 낸 이벤트 1건은 Redis 메시지 1건이고, A · B 가 모두 그 메시지를 받아 각자 자기 구독자가 있는 목적지에만 보낸다.
 * 인스턴스 하나 = 게이트웨이 + 중계 + 자기 구독 등록부 + 자기 송신 템플릿(가짜), 실제 연결은 공용 Redis 컨테이너다.
 */
class BroadcastRelayRedisTest extends RedisTestContainerBase {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-18T09:00:00+09:00");

    private static final String ON_A = "/topic/students/1/run";
    private static final String ON_B = "/topic/students/2/run";
    private static final String ON_NEITHER = "/topic/students/3/run";
    private static final String ON_BOTH = "/topic/admin/live";

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
    @DisplayName("A 가 낸 위치 1건은 Redis 메시지 1건이고, A 와 B 는 각자 자기 구독자가 있는 목적지에만 보낸다")
    void 인스턴스_둘은_각자_자기_구독자에게만_보낸다() throws Exception {
        Node a = newNode(Set.of(ON_A, ON_BOTH));
        Node b = newNode(Set.of(ON_B, ON_BOTH));
        awaitSubscribed(a, b);

        a.gateway.broadcast("position", 10L, NOW, List.of(
                new BroadcastDelivery(new Payload(new BigDecimal("37.5000"), 7L, "정문"),
                        List.of(ON_A, ON_B, ON_NEITHER, ON_BOTH))));

        verify(a.template, timeout(5000)).convertAndSend(eq(ON_A), any(WebSocketEnvelope.class));
        verify(a.template, timeout(5000)).convertAndSend(eq(ON_BOTH), any(WebSocketEnvelope.class));
        verify(b.template, timeout(5000)).convertAndSend(eq(ON_B), any(WebSocketEnvelope.class));
        verify(b.template, timeout(5000)).convertAndSend(eq(ON_BOTH), any(WebSocketEnvelope.class));
        verify(a.template, never()).convertAndSend(eq(ON_B), any(Object.class));
        verify(a.template, never()).convertAndSend(eq(ON_NEITHER), any(Object.class));
        verify(b.template, never()).convertAndSend(eq(ON_A), any(Object.class));
        verify(b.template, never()).convertAndSend(eq(ON_NEITHER), any(Object.class));
        assertThat(redisMessages).as("이벤트 1건이 Redis 에 낸 메시지 수(준비용 탐침 제외)")
                .filteredOn(text -> text.contains("\"event\":\"position\"")).hasSize(1);
    }

    @Test
    @DisplayName("B 가 받은 봉투의 payload 는 A 가 직접 보냈을 때와 같은 JSON 이다 — 키 이름 · 식별자 문자열 · 소수 자릿수")
    void 중계를_거쳐도_선_위의_JSON_은_같다() throws Exception {
        Node a = newNode(Set.of(ON_A));
        Node b = newNode(Set.of(ON_B));
        awaitSubscribed(a, b);
        Payload payload = new Payload(new BigDecimal("37.5000"), 7L, "정문");

        a.gateway.send(ON_B, "position", 10L, NOW, payload);

        ArgumentCaptor<WebSocketEnvelope> sent = ArgumentCaptor.forClass(WebSocketEnvelope.class);
        verify(b.template, timeout(5000)).convertAndSend(eq(ON_B), sent.capture());
        assertThat(mapper.writeValueAsString(sent.getValue()))
                .isEqualTo(mapper.writeValueAsString(new WebSocketEnvelope("position", 10L, NOW, payload)));
    }

    /** 두 인스턴스가 서로의 발행을 받을 수 있는 상태가 될 때까지 탐침 이벤트를 반복해 보낸다 — 구독 성립 전의 메시지는 Redis 가 버린다. */
    private void awaitSubscribed(Node... all) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            all[0].gateway.send(ON_BOTH, "probe", 0L, NOW, new Object());
            for (Node node : all) {
                verify(node.template, timeout(200).atLeastOnce()).convertAndSend(eq(ON_BOTH), any(WebSocketEnvelope.class));
            }
        });
        for (Node node : all) {
            clearInvocations(node.template);
        }
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

    /** 인스턴스 한 대 — 자기 구독 등록부 · 송신 템플릿 · Redis 연결을 가진다. */
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
