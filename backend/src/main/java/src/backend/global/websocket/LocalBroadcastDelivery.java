package src.backend.global.websocket;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import src.backend.observability.metrics.WebSocketPublishMetrics;

/**
 * 중계로 받은(또는 발행 실패로 직접 넘긴) 방송을 이 인스턴스의 구독자에게 실제로 쓰는 쪽 — {@link WebSocketBroadcastGateway} 가 발행과 전달을 나누려고 둔 협력 객체다.
 * 목적지마다 구독 여부 · 팬아웃 큐 포화(BR-170)를 판정하고, 목적지 하나의 전송 예외는 그 목적지에서 끝낸다. 위치({@code position})만 과부하 때 버린다(Ruling 349).
 */
final class LocalBroadcastDelivery {

    /** 로거 이름은 게이트웨이로 둔다 — 전달을 나눠도 로그 범주가 그대로다. */
    private static final Logger log = LoggerFactory.getLogger(WebSocketBroadcastGateway.class);

    /** 과부하 때 버려도 되는 유일한 이벤트 종류(Ruling 349). */
    private static final String DROPPABLE_EVENT = "position";

    private static final String DROPPED_METRIC = "schoolbus.ws.outbound.dropped";

    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketPublishMetrics publishMetrics;
    private final ThreadPoolTaskExecutor outboundTaskExecutor;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    /** 구독 여부를 묻는 심플 브로커 — 없으면(null) 걸러내지 않고 그대로 보낸다. */
    private volatile SimpleBrokerMessageHandler simpleBroker;

    LocalBroadcastDelivery(SimpMessagingTemplate messagingTemplate, WebSocketPublishMetrics publishMetrics,
            ThreadPoolTaskExecutor outboundTaskExecutor, Clock clock, MeterRegistry meterRegistry) {
        this.messagingTemplate = messagingTemplate;
        this.publishMetrics = publishMetrics;
        this.outboundTaskExecutor = outboundTaskExecutor;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /** 구독 여부를 물을 브로커를 정한다. */
    void useBroker(SimpleBrokerMessageHandler broker) {
        this.simpleBroker = broker;
    }

    /**
     * 중계로 받은(또는 발행 실패로 직접 넘긴) 이벤트를 이 인스턴스의 구독자에게 보낸다 — 목적지마다 구독 여부 · 큐 포화를 여기서 판정한다.
     * 목적지 하나의 전송 예외는 그 목적지에서 끝낸다.
     */
    void deliverLocal(RelayedBroadcast broadcast) {
        for (BroadcastDelivery delivery : broadcast.deliveries()) {
            for (String destination : delivery.destinations()) {
                deliverQuietly(destination, broadcast.event(), broadcast.runId(), broadcast.occurredAt(),
                        delivery.payload());
            }
        }
    }

    /**
     * 목적지 1곳에 봉투를 실어 보내고 발행 지연을 계측한다. 큐 포화 때 {@code position} 은 버린다. {@code position} 은
     * 구독자가 없는 목적지에는 직렬화하지 않고 건너뛴다 — 위치 1건이 22채널로 나가는데 대부분 아무도 안 본다.
     */
    private void deliver(String destination, String event, Long runId, OffsetDateTime occurredAt, Object payload) {
        if (DROPPABLE_EVENT.equals(event) && !hasSubscriber(destination)) {
            return;
        }
        if (DROPPABLE_EVENT.equals(event) && isOutboundQueueFull()) {
            meterRegistry.counter(DROPPED_METRIC, "event", DROPPABLE_EVENT).increment();
            log.warn("팬아웃 실행기 큐 포화로 위치 방송 버림 — destination={}, runId={}", destination, runId);
            return;
        }
        messagingTemplate.convertAndSend(destination, new WebSocketEnvelope(event, runId, occurredAt, payload));
        publishMetrics.recordLatency(Duration.between(occurredAt, OffsetDateTime.now(clock)));
    }

    /**
     * 브로커가 실제로 쓰는 구독 등록부에 그 목적지 구독이 있는지 본다 — 브로커가 배달 대상을 찾는 것과 같은 조회라 "구독자가
     * 있는데 걸러지는" 경우가 없다(구독 등록 직전 1건은 걸러지지만 그 1건은 원래도 등록 전에 지나간다). 브로커를 모르면 보낸다.
     */
    private boolean hasSubscriber(String destination) {
        if (simpleBroker == null) {
            return true;
        }
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination(destination);
        return !simpleBroker.getSubscriptionRegistry()
                .findSubscriptions(MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders())).isEmpty();
    }

    /** 팬아웃 실행기 큐에 남은 자리가 없는지 본다(BR-170) — 큐 상한은 {@code WebSocketExecutorConfig} 가 정한다. */
    private boolean isOutboundQueueFull() {
        ThreadPoolExecutor delegate = outboundTaskExecutor.getThreadPoolExecutor();
        return delegate.getQueue().remainingCapacity() == 0;
    }

    /**
     * 목적지 하나의 전송 예외를 그 목적지에서 끝낸다 — 학생 채널 하나가 실패해도 뒤 학생 채널과 매니저 · 학원 · 관리자 채널의 방송이 이어진다.
     * 삼킨 실패는 {@code schoolbus.ws.broadcast.failed}(event 태그)로 센다. 앞에서 예외가 번지면 관제 화면이 그 이벤트를 통째로 못 받는다.
     */
    private void deliverQuietly(String destination, String event, Long runId, OffsetDateTime occurredAt, Object payload) {
        try {
            deliver(destination, event, runId, occurredAt, payload);
        } catch (RuntimeException e) {
            meterRegistry.counter("schoolbus.ws.broadcast.failed", "event", event).increment();
            log.warn("{} 방송 실패 — runId={} destination={}", event, runId, destination, e);
        }
    }

    /** 버려진 방송이 한 번도 없어도 이름이 노출되도록 미리 등록한다(Ruling 349 로 버려지는 종류는 {@code position} 뿐이다). */
    void registerMetrics() {
        Counter.builder(DROPPED_METRIC)
                .description("팬아웃 실행기 큐가 가득 차 버려진 방송 건수 (event 태그, Ruling 349 로 position 만 버려짐)")
                .tag("event", DROPPABLE_EVENT).register(meterRegistry);
    }
}
