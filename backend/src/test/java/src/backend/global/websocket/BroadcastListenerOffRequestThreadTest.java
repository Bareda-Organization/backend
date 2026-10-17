package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManagerFactory;

import io.micrometer.core.instrument.MeterRegistry;

import src.backend.boarding.command.RiderChangedBroadcastListener;
import src.backend.boarding.event.RiderStatusChangedEvent;
import src.backend.exception.command.EmergencyBroadcastListener;
import src.backend.exception.entity.EmergencyType;
import src.backend.exception.event.EmergencyRaisedEvent;
import src.backend.notification.command.EmergencyNotificationListener;
import src.backend.request.command.ApprovalRequestedBroadcastListener;
import src.backend.request.event.ApprovalRequestedEvent;
import src.backend.run.command.RunEndedBroadcastListener;
import src.backend.run.command.RunRouteChangedBroadcastListener;
import src.backend.run.command.RunStartedBroadcastListener;
import src.backend.run.command.StopArrivedBroadcastListener;
import src.backend.run.event.RunEndedEvent;
import src.backend.run.event.RunRouteConfirmedEvent;
import src.backend.run.event.RunStartedEvent;
import src.backend.run.event.StopArrivedEvent;

/**
 * 커밋 뒤 방송 리스너는 요청 스레드의 트랜잭션 · 커넥션을 쥐고 돌지 않는다.
 *
 * <p>{@code AFTER_COMMIT} 콜백은 커밋 직후에도 요청 트랜잭션이 활성이고 커넥션이 반납되기 전이라(실측: 활성 트랜잭션 참 · 풀 사용 중 1),
 * 리스너가 그 안에서 조회 · 방송하면 방송 시간만큼 커넥션을 더 쥔다. 그래서 콜백은 방송 작업을 전용 실행기에 넘기기만 한다. 승하차 방송의 상태별 인원
 * 조회는 1번이다. 비상 방송은 트랜잭션 없이 발행돼도 나간다. 학생 채널 하나의 전송 예외가 나머지 채널 방송을 막지 않는다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class BroadcastListenerOffRequestThreadTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2030-05-06T07:30:00+09:00");
    private static final long AWAIT_SECONDS = 5;

    @MockitoSpyBean
    private WebSocketBroadcastGateway gateway;

    @MockitoSpyBean
    private SimpMessagingTemplate messagingTemplate;

    /** 비상 알림 적재는 트랜잭션 안에서만 부를 수 있다(MANDATORY) — 방송 리스너만 보려고 이 시험에서는 끈다. */
    @MockitoBean
    private EmergencyNotificationListener emergencyNotificationListener;

    @Autowired
    private RiderChangedBroadcastListener riderChangedListener;

    @Autowired
    private RunStartedBroadcastListener runStartedListener;

    @Autowired
    private RunEndedBroadcastListener runEndedListener;

    @Autowired
    private StopArrivedBroadcastListener stopArrivedListener;

    @Autowired
    private RunRouteChangedBroadcastListener runRouteChangedListener;

    @Autowired
    private ApprovalRequestedBroadcastListener approvalRequestedListener;

    @Autowired
    private EmergencyBroadcastListener emergencyListener;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /**
     * 옮긴 리스너 7개 전부 — 리스너 메서드를 트랜잭션 안에서 부르면 방송(게이트웨이 호출)은 그 트랜잭션 밖 · 다른 스레드에서 나가야 한다. 하나라도 다시 제자리(인라인)로
     * 돌아오면 그 이름이 실패 메시지에 나온다. 이벤트를 발행하지 않고 리스너만 부르므로 같은 이벤트를 받는 다른 리스너(알림 등)가 섞이지 않는다.
     */
    @Test
    void 방송_리스너_7개는_모두_요청_스레드가_아닌_트랜잭션_밖에서_돈다() throws Exception {
        BlockingQueue<String> seen = new LinkedBlockingQueue<>();
        Answer<Object> record = invocation -> {
            seen.add("tx=" + TransactionSynchronizationManager.isActualTransactionActive()
                    + " thread=" + Thread.currentThread().getName());
            return null;
        };
        doAnswer(record).when(gateway).send(any(), any(), any(), any(), any());
        doAnswer(record).when(gateway).sendToAll(any(), any(), any(), any(), any());
        doAnswer(record).when(gateway).broadcast(any(), any(), any(), any());
        doAnswer(record).when(gateway).broadcastToRunChannels(any(), any(), any(), any(), any(), any());
        doAnswer(record).when(gateway).broadcastToRunChannels(any(), any(), any(), any(), any(), any(), any());
        String requestThread = Thread.currentThread().getName();

        Map<String, Runnable> listeners = new LinkedHashMap<>();
        listeners.put("RunStarted", () -> runStartedListener.broadcast(new RunStartedEvent(1L, 1L, AT, 0)));
        listeners.put("RunEnded", () -> runEndedListener.broadcast(new RunEndedEvent(1L, 1L, AT, 0)));
        listeners.put("StopArrived", () -> stopArrivedListener.broadcast(new StopArrivedEvent(1L, 1L, 1L, 1, "정차지", AT, null)));
        listeners.put("RunRouteChanged", () -> runRouteChangedListener.broadcast(new RunRouteConfirmedEvent(1L, 1L, 1L, AT)));
        listeners.put("RiderChanged", () -> riderChangedListener.broadcast(new RiderStatusChangedEvent(2L, 1L, 1L, 1L, "boarded", AT, false)));
        listeners.put("ApprovalRequested", () -> approvalRequestedListener.broadcast(new ApprovalRequestedEvent(1L, 1L, 1L, 1L, AT)));
        listeners.put("Emergency", () -> emergencyListener.broadcastRaised(new EmergencyRaisedEvent(1L, 1L, "학원", 1L, "1호차",
                EmergencyType.ACCIDENT, new EmergencyRaisedEvent.RaisedBy("기사", "driver", "010-0000-0000"),
                new EmergencyRaisedEvent.Position(null, null), 3, AT)));
        assertThat(listeners).hasSize(7);

        for (Map.Entry<String, Runnable> listener : listeners.entrySet()) {
            seen.clear();
            transactionTemplate.executeWithoutResult(status -> listener.getValue().run());

            String first = seen.poll(AWAIT_SECONDS, TimeUnit.SECONDS);
            assertThat(first).as("%s 리스너가 방송해야 한다", listener.getKey()).isNotNull();
            assertThat(first).as("%s — 요청 트랜잭션이 활성인 채 방송하면 방송 시간만큼 커넥션을 더 쥔다", listener.getKey())
                    .startsWith("tx=false").doesNotContain("thread=" + requestThread);
        }
    }

    @Test
    void 승하차_방송은_상태별_인원을_한_번에_센다() throws Exception {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        Statistics statistics = sessionFactory.getStatistics();
        CountDownLatch broadcast = new CountDownLatch(1); // 매니저 · 학원 · 관리자 채널을 한 번에 보낸다
        doAnswer(invocation -> {
            broadcast.countDown();
            return null;
        }).when(gateway).sendToAll(any(), eq("rider_changed"), any(), any(), any());
        awaitAsyncWorkDrained(); // 앞 시험이 남긴 비동기 일(알림 발송 등)이 통계에 섞이지 않게 한다
        long before = statistics.getPrepareStatementCount();

        // 이벤트 발행이 아니라 이 리스너만 직접 부른다 — 같은 이벤트를 받는 다른 리스너(알림 등)의 질의가 셈에 섞이지 않게
        riderChangedListener.broadcast(new RiderStatusChangedEvent(2L, 1L, 1L, 1L, "boarded", AT, false));

        assertThat(broadcast.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(statistics.getPrepareStatementCount() - before)
                .as("학생 이름 1 + 정차지 1 + 상태별 인원 1 — 인원을 상태마다 따로 세면 7이 된다").isLessThanOrEqualTo(3);
    }

    /** 컨텍스트의 모든 스레드 풀 실행기가 일을 끝내고 큐가 빌 때까지 기다린다 — 통계 측정 앞의 기준선을 고정한다. */
    private void awaitAsyncWorkDrained() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            boolean busy = applicationContext.getBeansOfType(ThreadPoolTaskExecutor.class).values().stream()
                    .map(ThreadPoolTaskExecutor::getThreadPoolExecutor)
                    .anyMatch(pool -> pool.getActiveCount() > 0 || !pool.getQueue().isEmpty());
            if (!busy) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("비동기 실행기가 " + AWAIT_SECONDS + "초 안에 비지 않았다");
    }

    @Test
    void 비상_방송_리스너는_트랜잭션_없이_발행돼도_보낸다() throws Exception {
        CountDownLatch broadcast = new CountDownLatch(1); // 학원 · 관리자 채널을 한 번에 보낸다
        doAnswer(invocation -> {
            broadcast.countDown();
            return null;
        }).when(gateway).sendToAll(any(), eq("emergency_raised"), any(), any(), any());

        publisher.publishEvent(new EmergencyRaisedEvent(1L, 1L, "학원", 1L, "1호차", EmergencyType.ACCIDENT,
                new EmergencyRaisedEvent.RaisedBy("기사", "driver", "010-0000-0000"),
                new EmergencyRaisedEvent.Position(null, null), 3, AT));

        assertThat(broadcast.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                .as("트랜잭션 밖 발행이 조용히 버려지면 안전 사안인 비상 방송이 사라진다").isTrue();
    }

    @Test
    void 학생_채널_하나의_전송_예외가_나머지_채널_방송을_막지_않고_실패로_센다() {
        doThrow(new IllegalStateException("전송 실패")).when(messagingTemplate)
                .convertAndSend(eq(WebSocketDestinations.studentRun(10L)), any(Object.class));
        double failedBefore = meterRegistry.counter("schoolbus.ws.broadcast.failed", "event", "stop_arrived").count();

        gateway.broadcastToRunChannels(1L, 1L, List.of(10L, 11L), "stop_arrived", AT, "payload", "payload");

        // 방송은 Redis 중계를 거쳐 받는 스레드에서 나가므로 도착을 기다린다
        verify(messagingTemplate, timeout(AWAIT_SECONDS * 1000)).convertAndSend(eq(WebSocketDestinations.studentRun(11L)),
                any(Object.class));
        verify(messagingTemplate, timeout(AWAIT_SECONDS * 1000)).convertAndSend(eq(WebSocketDestinations.managerRun(1L)),
                any(Object.class));
        await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(meterRegistry.counter("schoolbus.ws.broadcast.failed", "event", "stop_arrived").count())
                        .as("삼킨 전송 실패는 실패 카운터를 정확히 1 올린다").isEqualTo(failedBefore + 1));
    }
}
