package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
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

import src.backend.boarding.command.RiderChangedBroadcastListener;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.event.RiderMarkedNoShowEvent;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.exception.command.EmergencyBroadcastListener;
import src.backend.exception.entity.EmergencyType;
import src.backend.exception.event.EmergencyAckedEvent;
import src.backend.exception.event.EmergencyCanceledEvent;
import src.backend.exception.event.EmergencyRaisedEvent;
import src.backend.global.location.RunRiderDirectory;
import src.backend.location.command.PositionBroadcastListener;
import src.backend.location.event.RunPositionReceivedEvent;
import src.backend.location.infrastructure.RunBroadcastCache;
import src.backend.observability.metrics.WebSocketPublishMetrics;
import src.backend.run.command.RunEndedBroadcastListener;
import src.backend.run.command.RunRouteChangedBroadcastListener;
import src.backend.run.command.RunStartedBroadcastListener;
import src.backend.run.command.StopArrivedBroadcastListener;
import src.backend.run.event.RunEndedEvent;
import src.backend.run.event.RunRouteConfirmedEvent;
import src.backend.run.event.RunStartedEvent;
import src.backend.run.event.StopArrivedEvent;
import src.backend.request.command.ApprovalRequestedBroadcastListener;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.event.ApprovalRequestedEvent;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.student.entity.Stop;
import src.backend.student.entity.Student;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import testsupport.redis.RedisTestContainerBase;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * 게이트웨이를 지나는 방송 종류마다 인스턴스가 낸 이벤트가 실제 Redis 를 거쳐 자기에게 붙은 구독자의 송신 템플릿에 닿는다 — 실제 리스너가 게이트웨이로 내고, 발행한 인스턴스도 Redis 를 거쳐 받는다.
 * 종류별 목적지와 payload 는 각 리스너 시험이 따로 본다. 여기서 보는 것은 "낸 것이 중계를 거쳐 닿는다" 하나다(Ruling 900 A).
 */
class BroadcastRelayEveryEventTest extends RedisTestContainerBase {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-18T09:00:00+09:00");
    private static final long RUN = 10L;
    private static final long ACADEMY = 1L;
    private static final String STUDENT_CHANNEL = "/topic/students/100/run";
    private static final String MANAGER_CHANNEL = "/topic/manager/runs/10";
    private static final String ACADEMY_CHANNEL = "/topic/academy/1/live";
    private static final String ADMIN_CHANNEL = "/topic/admin/live";
    private static final List<String> ALL_CHANNELS = List.of(STUDENT_CHANNEL, MANAGER_CHANNEL, ACADEMY_CHANNEL, ADMIN_CHANNEL);

    private final String suffix = ":" + UUID.randomUUID();
    private final JsonMapper mapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    private final List<Node> nodes = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeAll() throws Exception {
        for (Node node : nodes) {
            node.close();
        }
    }

    @Test
    @DisplayName("모든 방송 종류(run_started · run_ended · stop_arrived · route_changed · approval_requested · rider_changed · emergency 3종 · position) — 낸 것이 Redis 를 거쳐 구독자에게 닿는다")
    void 모든_방송_종류가_중계를_거쳐_닿는다() {
        Node node = newNode(ALL_CHANNELS);
        awaitSubscribed(node);
        RunRiderRepository riders = mock(RunRiderRepository.class);
        given(riders.findAllByRunId(RUN)).willReturn(List.of(RunRider.uponConfirmation(RUN, 100L, 1L)));
        RunRiderDirectory directory = mock(RunRiderDirectory.class);
        given(directory.studentIdsExcludingAbsent(RUN)).willReturn(List.of(100L));

        new RunStartedBroadcastListener(riders, node.gateway, Runnable::run).broadcast(new RunStartedEvent(RUN, ACADEMY, NOW, 0));
        expectDelivered(node, "run_started", ALL_CHANNELS);

        new RunEndedBroadcastListener(riders, node.gateway, Runnable::run).broadcast(new RunEndedEvent(RUN, ACADEMY, NOW, 0L));
        expectDelivered(node, "run_ended", ALL_CHANNELS);

        new StopArrivedBroadcastListener(riders, node.gateway, Runnable::run)
                .broadcast(new StopArrivedEvent(RUN, ACADEMY, 7L, 2, "정문 앞", NOW, 8L));
        expectDelivered(node, "stop_arrived", ALL_CHANNELS);

        new RunRouteChangedBroadcastListener(node.gateway, Runnable::run).broadcast(new RunRouteConfirmedEvent(RUN, ACADEMY, 3L, NOW));
        expectDelivered(node, "route_changed", List.of(MANAGER_CHANNEL));

        new PositionBroadcastListener(directory, new RunBroadcastCache(Clock.systemUTC()), node.gateway).broadcast(
                new RunPositionReceivedEvent(RUN, new BigDecimal("37.5"), new BigDecimal("127.0"), NOW, NOW, ACADEMY, "정문", NOW));
        expectDelivered(node, "position", List.of(STUDENT_CHANNEL, ACADEMY_CHANNEL, ADMIN_CHANNEL));

        RiderChangedBroadcastListener riderChanged = new RiderChangedBroadcastListener(mock(StudentRepository.class),
                mock(RunRiderRepository.class), node.gateway, Runnable::run);
        riderChanged.broadcast(new RiderMarkedNoShowEvent(RUN, ACADEMY, 100L, 7L, 8L, 9L, false, NOW));
        expectDelivered(node, "rider_changed", List.of(MANAGER_CHANNEL, ACADEMY_CHANNEL, ADMIN_CHANNEL));

        StudentRepository students = mock(StudentRepository.class);
        Student student = mock(Student.class);
        given(student.getName()).willReturn("이서연");
        given(students.findById(100L)).willReturn(Optional.of(student));
        RunRiderRepository approvalRiders = mock(RunRiderRepository.class);
        given(approvalRiders.findByRunIdAndStudentId(RUN, 100L)).willReturn(Optional.of(RunRider.uponConfirmation(RUN, 100L, 7L)));
        StopRepository stops = mock(StopRepository.class);
        Stop stop = mock(Stop.class);
        given(stop.getName()).willReturn("정문 앞");
        given(stops.findById(7L)).willReturn(Optional.of(stop));
        ChangeRequestRepository changeRequests = mock(ChangeRequestRepository.class);
        ChangeRequest changeRequest = mock(ChangeRequest.class);
        given(changeRequest.getDeadlineAt()).willReturn(NOW.plusHours(1));
        given(changeRequests.findById(1L)).willReturn(Optional.of(changeRequest));
        new ApprovalRequestedBroadcastListener(students, approvalRiders, stops, changeRequests, node.gateway, Runnable::run)
                .broadcast(new ApprovalRequestedEvent(1L, ACADEMY, RUN, 100L, NOW));
        expectDelivered(node, "approval_requested", List.of(ACADEMY_CHANNEL));

        EmergencyBroadcastListener emergency = new EmergencyBroadcastListener(node.gateway, Runnable::run);
        emergency.broadcastRaised(new EmergencyRaisedEvent(5L, ACADEMY, "A학원", RUN, "12가3456", EmergencyType.ACCIDENT,
                new EmergencyRaisedEvent.RaisedBy("기사", "driver", "010"),
                new EmergencyRaisedEvent.Position(new BigDecimal("37.5"), new BigDecimal("127.0")), 3, NOW));
        expectDelivered(node, "emergency_raised", List.of(ACADEMY_CHANNEL, ADMIN_CHANNEL));
        emergency.broadcastAcked(new EmergencyAckedEvent(5L, ACADEMY, RUN, "관계자", NOW));
        expectDelivered(node, "emergency_acked", List.of(MANAGER_CHANNEL));
        emergency.broadcastCanceled(new EmergencyCanceledEvent(5L, ACADEMY, RUN, "12가3456", NOW));
        expectDelivered(node, "emergency_canceled", List.of(ACADEMY_CHANNEL, ADMIN_CHANNEL));
    }

    /** 인스턴스의 송신 템플릿이 그 종류의 방송을 기대한 목적지마다 받았는지 본다. */
    private void expectDelivered(Node node, String event, List<String> destinations) {
        for (String destination : destinations) {
            verify(node.template, timeout(5000)).convertAndSend(eq(destination),
                    org.mockito.ArgumentMatchers.<Object>argThat(envelope ->
                            envelope instanceof WebSocketEnvelope e && event.equals(e.event())));
        }
        clearInvocations(node.template);
    }

    /** 인스턴스가 자기 발행을 Redis 에서 받을 수 있는 상태가 될 때까지 탐침 방송을 반복해 보낸다. */
    private void awaitSubscribed(Node node) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            node.gateway.send(ADMIN_CHANNEL, "probe", 0L, NOW, new Object());
            verify(node.template, timeout(200).atLeastOnce()).convertAndSend(eq(ADMIN_CHANNEL), any(WebSocketEnvelope.class));
        });
        clearInvocations(node.template);
    }

    private Node newNode(java.util.Collection<String> watched) {
        Node node = new Node(Set.copyOf(watched));
        nodes.add(node);
        return node;
    }

    private final class Node {
        final SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        final ThreadPoolTaskExecutor outbound = new ThreadPoolTaskExecutor();
        final LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        final RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        final WebSocketBroadcastGateway gateway;

        Node(Set<String> watched) {
            outbound.initialize();
            factory.afterPropertiesSet();
            container.setConnectionFactory(factory);
            container.afterPropertiesSet();
            container.start();
            SimpleMeterRegistry meters = new SimpleMeterRegistry();
            gateway = new WebSocketBroadcastGateway(template, mock(WebSocketPublishMetrics.class), outbound, Clock.systemUTC(), meters);
            gateway.setSimpleBrokerMessageHandler(brokerWatching(watched));
            gateway.setRelay(new BroadcastRelay(new StringRedisTemplate(factory), container, mapper, meters, suffix));
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
