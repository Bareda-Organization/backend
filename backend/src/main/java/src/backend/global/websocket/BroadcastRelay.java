package src.backend.global.websocket;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import lombok.extern.slf4j.Slf4j;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * 인스턴스 사이의 방송 중계 — 이벤트 1건을 Redis Pub/Sub 메시지 1건(목적지 전부 포함)으로 내보내고, 모든 인스턴스(발행한 쪽 포함)가 받아
 * 자기에게 붙은 구독자에게만 보낸다(Ruling 900 A). 발행 경로와 수신 경로가 하나라 인스턴스가 1대여도 같은 길을 지난다.
 *
 * <p>payload 는 앱의 JSON 매퍼로 한 번 직렬화해 {@link JsonNode} 로 싣는다 — 받는 쪽이 그 트리를 그대로 봉투에 넣으면 선 위의 JSON 은 직접 보낼 때와 같다
 * (키 이름 규칙 · 식별자 문자열). 소수는 {@code BigDecimal} 로 읽어 자릿수를, 시각은 UTC 로 바꾸지 않고 읽어 오프셋을 지킨다.
 */
@Component
@Slf4j
public class BroadcastRelay {

    private static final String CHANNEL = "schoolbus:relay:ws";

    private record Wire(String event, Long runId, OffsetDateTime occurredAt, List<WireDelivery> deliveries) {
    }

    private record WireDelivery(JsonNode payload, List<String> destinations) {
    }

    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer container;
    private final JsonMapper mapper;
    private final ObjectReader reader;
    private final String channel;
    private final Counter published;
    private final Counter received;

    public BroadcastRelay(StringRedisTemplate redis, RedisMessageListenerContainer container, JsonMapper mapper,
            MeterRegistry meterRegistry, @Value("${app.relay.channel-suffix:}") String channelSuffix) {
        this.redis = redis;
        this.container = container;
        this.mapper = mapper;
        this.published = Counter.builder("schoolbus.ws.relay.published")
                .description("이 인스턴스가 Redis 로 낸 방송 중계 메시지 수 (이벤트 1건 = 1건)").register(meterRegistry);
        this.received = Counter.builder("schoolbus.ws.relay.received")
                .description("이 인스턴스가 Redis 에서 받아 처리를 마친 방송 중계 메시지 수 (자기가 낸 것 포함)").register(meterRegistry);
        this.reader = mapper.readerFor(Wire.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
        this.channel = CHANNEL + channelSuffix;
    }

    /** 이벤트 1건을 Redis 메시지 1건으로 낸다 — 발행이 실패하면 예외가 그대로 나가며, 부르는 쪽이 자기 구독자에게 직접 보내는 대체 경로를 탄다(Ruling 900 B). */
    public void publish(String event, Long runId, OffsetDateTime occurredAt, List<BroadcastDelivery> deliveries) {
        List<WireDelivery> wire = deliveries.stream()
                .map(delivery -> new WireDelivery(mapper.valueToTree(delivery.payload()), delivery.destinations()))
                .toList();
        redis.convertAndSend(channel, mapper.writeValueAsString(new Wire(event, runId, occurredAt, wire)));
        published.increment();
    }

    /**
     * 이 인스턴스가 낸 메시지 중 아직 받아서 처리하지 못한 수 — 인스턴스 1대만 쓰는 시험이 "낸 방송이 모두 나갔다" 를 기다리는 기준이다
     * (다른 인스턴스가 낸 메시지는 {@code received} 에만 들어가므로 0 미만이 될 수 있다).
     */
    public long inFlight() {
        return (long) (published.count() - received.count());
    }

    /** 이 인스턴스가 받은 중계 이벤트를 {@code consumer} 에 넘긴다 — 소비자가 던진 예외는 이 구독을 끊지 않게 여기서 기록만 한다. */
    public void subscribe(Consumer<RelayedBroadcast> consumer) {
        container.addMessageListener((Message message, byte[] pattern) -> {
            try {
                Wire wire = reader.readValue(new String(message.getBody(), StandardCharsets.UTF_8));
                consumer.accept(new RelayedBroadcast(wire.event(), wire.runId(), wire.occurredAt(),
                        wire.deliveries().stream()
                                .map(delivery -> new BroadcastDelivery(delivery.payload(), delivery.destinations()))
                                .toList()));
            } catch (RuntimeException e) {
                log.warn("방송 중계 메시지 처리 실패", e);
            } finally {
                received.increment();
            }
        }, new ChannelTopic(channel));
    }
}
