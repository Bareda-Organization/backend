package src.backend.global.websocket;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.AbstractBrokerMessageHandler;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import src.backend.observability.metrics.WebSocketPublishMetrics;

/**
 * WebSocket 방송 송신을 한 곳으로 모은다 — 리스너마다 {@code SimpMessagingTemplate}
 * 을 직접 들고 목적지 문자열을 조립하면, 채널 4종 중 하나를 빠뜨려도 컴파일도 테스트도 걸리지 않는
 * 조용한 누락이 된다. {@code run_started}·{@code run_ended}·{@code stop_arrived} 는 감사 대상(§7)이
 * 완전히 같은 4채널(회차 배치 학생 전원의 개인 채널 + 매니저 + 학원 + 관리자)이라 그 팬아웃을
 * {@link #broadcastToRunChannels} 하나로 묶는다 — 리스너 3개가 각자 다시 구현하면 그중 하나만 고쳐
 * 채널이 갈리는 사고가 난다.
 *
 * <p><b>이벤트 1건은 Redis 중계({@link BroadcastRelay}) 메시지 1건이다</b>(Ruling 900 A) — 목적지 전부를 한 메시지에 담아 내보내고, 모든 인스턴스(발행한 쪽 포함)가
 * 받아 {@link #deliverLocal} 로 자기에게 붙은 구독자에게만 보낸다. 그래서 위치 · 큐 포화 판정은 받는 쪽에서 한다.
 *
 * <p>발행 지연 계측이 이 게이트에 붙는 이유도 같다 — 리스너 8곳이 이미 넘기는 {@code occurredAt}
 * (원본 도메인 이벤트 시각)을 여기서 한 번만 현재 시각과 비교하면, 리스너가 늘어도 계측이 따라온다.
 *
 * <p><b>과부하 때 이벤트 종류별로 버림 여부가 갈리는 자리도 여기다</b>(BR-170, Ruling 349) —
 * 리스너를 고치지 않고 이 송신 채널에서 가른다. 위치({@code position})는 2~5초 뒤 새 값이
 * 덮어쓰는 데이터라 팬아웃 실행기 큐가 가득 차면 버리고, 나머지(비상·승하차 등)는 큐가 가득
 * 차도 {@code CallerRunsPolicy}(호출 스레드가 대신 보냄, {@link src.backend.global.config.WebSocketConfig})
 * 로 유실 없이 전달된다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketBroadcastGateway {

    /** 과부하 때 버려도 되는 유일한 이벤트 종류(Ruling 349). */
    private static final String DROPPABLE_EVENT = "position";

    private static final String DROPPED_METRIC = "schoolbus.ws.outbound.dropped";

    private static final String PUBLISH_FAILED_METRIC = "schoolbus.ws.relay.publish.failed";

    /** 중계 구독이 서 있지 않아 직접 보낸 방송 수(event 태그) — Redis 가 돌아온 직후 구독이 다시 서기까지의 틈을 센다. */
    private static final String UNSUBSCRIBED_DIRECT_METRIC = "schoolbus.ws.relay.unsubscribed.direct";

    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketPublishMetrics publishMetrics;
    @Qualifier("outboundTaskExecutor")
    private final ThreadPoolTaskExecutor outboundTaskExecutor;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    /**
     * 구독 여부를 묻는 심플 브로커 — 시험이 게이트웨이를 직접 만드는 곳이 있어 생성자가 아니라 세터로 받는다. 없으면(null)
     * 걸러내지 않고 그대로 보낸다.
     */
    private SimpleBrokerMessageHandler simpleBroker;

    @Autowired(required = false)
    void setSimpleBrokerMessageHandler(@Qualifier("simpleBrokerMessageHandler") AbstractBrokerMessageHandler broker) {
        this.simpleBroker = broker instanceof SimpleBrokerMessageHandler simple ? simple : null;
    }

    /** 인스턴스 사이 중계 — 시험이 게이트웨이를 직접 만드는 곳이 있어 세터로 받는다. 없으면(null) 중계 없이 이 인스턴스의 구독자에게만 보낸다. */
    private BroadcastRelay relay;

    @Autowired(required = false)
    void setRelay(BroadcastRelay relay) {
        this.relay = relay;
    }

    /**
     * 이벤트 1건을 목적지 전부와 함께 Redis 메시지 1건으로 내보낸다(Ruling 900 A) — 이 인스턴스도 다른 인스턴스와 똑같이 Redis 에서 받아
     * {@link #deliverLocal} 로 자기 구독자에게 보낸다. 발행이 실패하면 이 인스턴스의 구독자에게만 직접 보내고 실패를 센다(Ruling 900 B) —
     * 인스턴스 1대 운영은 영향이 없고, 2대 운영에서 그동안 다른 인스턴스로 가는 방송은 잃으며 클라이언트가 재접속 · 화면 복귀 때 REST 로 다시 받는다.
     */
    public void broadcast(String event, Long runId, OffsetDateTime occurredAt, List<BroadcastDelivery> deliveries) {
        if (relay == null) {
            deliverLocal(new RelayedBroadcast(event, runId, occurredAt, deliveries));
            return;
        }
        // 이 인스턴스의 구독이 서 있지 않으면 발행한 방송이 이 인스턴스로 돌아오지 않는다 — 먼저 직접 보내고 발행은 그대로 한다(다른 인스턴스용).
        // 만들 수 있는 것은 중복뿐이다: 구독이 다시 서는 바로 그 순간에 직접 보낸 방송이 중계로도 한 번 더 온다. 손실은 없다.
        boolean subscribed = relay.isSubscribed();
        if (!subscribed) {
            meterRegistry.counter(UNSUBSCRIBED_DIRECT_METRIC, "event", event).increment();
            deliverLocal(new RelayedBroadcast(event, runId, occurredAt, deliveries));
        }
        try {
            relay.publish(event, runId, occurredAt, deliveries);
        } catch (RuntimeException e) {
            meterRegistry.counter(PUBLISH_FAILED_METRIC, "event", event).increment();
            log.warn("{} 방송 중계 발행 실패 — 이 인스턴스의 구독자에게만 직접 보낸다. runId={}", event, runId, e);
            if (subscribed) {
                deliverLocal(new RelayedBroadcast(event, runId, occurredAt, deliveries));
            }
        }
    }

    /** 같은 payload 를 목적지 여러 곳에 — 발행은 1건이다. */
    public void sendToAll(List<String> destinations, String event, Long runId, OffsetDateTime occurredAt,
            Object payload) {
        broadcast(event, runId, occurredAt, List.of(new BroadcastDelivery(payload, destinations)));
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

    /** 팬아웃 실행기 큐에 남은 자리가 없는지 본다(BR-170) — 큐 상한은 {@code WebSocketConfig} 가 정한다. */
    private boolean isOutboundQueueFull() {
        ThreadPoolExecutor delegate = outboundTaskExecutor.getThreadPoolExecutor();
        return delegate.getQueue().remainingCapacity() == 0;
    }

    /**
     * {@code run_started}·{@code run_ended}·{@code stop_arrived} 공통 팬아웃(API_SPEC §7 채널 표) —
     * 학생 채널은 그 회차 명단에 오른 학생마다 각자의 {@code /topic/students/{id}/run} 으로 따로
     * 보낸다(같은 페이로드를 studentId 수만큼 반복 전송) — STOMP 심플 브로커에 학생별 목적지를 한 번에
     * 묶어 보내는 수단이 없어서다.
     *
     * <p>CODE_CONVENTIONS §20.2 파라미터 신호 — 6개를 파라미터 객체로 묶지 않는다. 리스너 9곳이 이미 각자의 이벤트
     * 레코드에서 값을 꺼내 이 순서 그대로 넘기는 관례라, 객체 하나로 싸면 그 변환 보일러플레이트가
     * 호출부마다 새로 생긴다.
     */
    public void broadcastToRunChannels(Long runId, Long academyId, List<Long> studentIds, String event,
            OffsetDateTime occurredAt, Object payload) {
        broadcastToRunChannels(runId, academyId, studentIds, event, occurredAt, payload, payload);
    }

    /**
     * 학생 채널에만 다른 payload 를 싣는 형태 — 학부모·학생 앱에 인원수를 보내지 않기 위함이다(C-08,
     * Ruling 335). 매니저·관제·관리자 채널은 {@code payload} 를 그대로 받는다.
     */
    public void broadcastToRunChannels(Long runId, Long academyId, List<Long> studentIds, String event,
            OffsetDateTime occurredAt, Object studentPayload, Object payload) {
        List<String> controlChannels = List.of(WebSocketDestinations.managerRun(runId),
                WebSocketDestinations.academyLive(academyId), WebSocketDestinations.ADMIN_LIVE);
        List<String> studentChannels = studentIds.stream().map(WebSocketDestinations::studentRun).toList();
        List<BroadcastDelivery> deliveries = new ArrayList<>();
        if (studentPayload == payload) {
            deliveries.add(new BroadcastDelivery(payload,
                    Stream.concat(studentChannels.stream(), controlChannels.stream()).toList()));
        } else {
            if (!studentChannels.isEmpty()) {
                deliveries.add(new BroadcastDelivery(studentPayload, studentChannels));
            }
            deliveries.add(new BroadcastDelivery(payload, controlChannels));
        }
        broadcast(event, runId, occurredAt, deliveries);
    }

    /** 목적지 1곳에 보낸다 — 발행은 1건이다. */
    public void send(String destination, String event, Long runId, OffsetDateTime occurredAt, Object payload) {
        sendToAll(List.of(destination), event, runId, occurredAt, payload);
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

    /** 중계로 오는 이벤트를 이 인스턴스의 구독자에게 보내도록 등록한다. */
    @PostConstruct
    void subscribeToRelay() {
        if (relay != null) {
            relay.subscribe(this::deliverLocal);
        }
    }

    /** 버려진 방송이 한 번도 없어도 이름이 노출되도록 미리 등록한다(Ruling 349 로 버려지는 종류는 {@code position} 뿐이다). */
    @PostConstruct
    void registerMetrics() {
        Counter.builder(DROPPED_METRIC)
                .description("팬아웃 실행기 큐가 가득 차 버려진 방송 건수 (event 태그, Ruling 349 로 position 만 버려짐)")
                .tag("event", DROPPABLE_EVENT).register(meterRegistry);
    }
}
