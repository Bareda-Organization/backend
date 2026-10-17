package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManagerFactory;

import src.backend.boarding.command.RiderChangedBroadcastListener;
import src.backend.boarding.event.RiderStatusChangedEvent;
import src.backend.exception.entity.EmergencyType;
import src.backend.exception.event.EmergencyRaisedEvent;
import src.backend.notification.command.EmergencyNotificationListener;
import src.backend.run.event.RunStartedEvent;

/**
 * 커밋 뒤 방송 리스너는 요청 스레드의 트랜잭션 · 커넥션을 쥐고 돌지 않는다(R57 RT-4 · RT-10 · RT-9).
 *
 * <p>{@code AFTER_COMMIT} 콜백은 커밋 직후에도 요청 트랜잭션이 활성이고 커넥션이 반납되기 전이라(2026-10-17 실측: 활성 트랜잭션 참 · 풀 사용 중 1),
 * 리스너가 그 안에서 조회 · 방송하면 방송 시간만큼 커넥션을 더 쥔다. 그래서 콜백은 방송 작업을 전용 실행기에 넘기기만 한다. 승하차 방송의 상태별 인원
 * 조회는 5번에서 1번으로 줄였다. 비상 방송은 트랜잭션 없이 발행돼도 나간다. 학생 채널 하나의 전송 예외가 나머지 채널 방송을 막지 않는다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BroadcastListenerOffRequestThreadTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2030-05-06T07:30:00+09:00");
    private static final long AWAIT_SECONDS = 5;

    @MockitoSpyBean
    private WebSocketBroadcastGateway gateway;

    /** 비상 알림 적재는 트랜잭션 안에서만 부를 수 있다(MANDATORY) — 방송 리스너만 보려고 이 시험에서는 끈다. */
    @MockitoBean
    private EmergencyNotificationListener emergencyNotificationListener;

    @Autowired
    private RiderChangedBroadcastListener riderChangedListener;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void 방송_리스너는_요청_스레드가_아닌_트랜잭션_밖에서_돈다() throws Exception {
        CountDownLatch broadcast = new CountDownLatch(1);
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            seen.add("tx=" + TransactionSynchronizationManager.isActualTransactionActive()
                    + " thread=" + Thread.currentThread().getName());
            broadcast.countDown();
            return null;
        }).when(gateway).broadcastToRunChannels(any(), any(), any(), any(), any(), any(), any());
        String requestThread = Thread.currentThread().getName();

        transactionTemplate.executeWithoutResult(status -> publisher.publishEvent(new RunStartedEvent(1L, 1L, AT, 0)));

        assertThat(broadcast.await(AWAIT_SECONDS, TimeUnit.SECONDS)).as("방송이 나가야 한다").isTrue();
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)).as("요청 트랜잭션이 아직 활성인 채 방송하면 방송 시간만큼 커넥션을 더 쥔다").startsWith("tx=false")
                .doesNotContain("thread=" + requestThread);
    }

    /** 먼저 돈다 — Hibernate 통계는 JVM 전체 값이라, 앞 시험이 낸 이벤트의 뒤늦은 비동기 질의(알림 발송 등)가 셈에 섞이지 않게 한다. */
    @Test
    @Order(1)
    void 승하차_방송은_상태별_인원을_한_번에_센다() throws Exception {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        Statistics statistics = sessionFactory.getStatistics();
        CountDownLatch broadcast = new CountDownLatch(3); // 매니저 · 학원 · 관리자 채널
        doAnswer(invocation -> {
            broadcast.countDown();
            return null;
        }).when(gateway).send(any(), eq("rider_changed"), any(), any(), any());
        long before = statistics.getPrepareStatementCount();

        // 이벤트 발행이 아니라 이 리스너만 직접 부른다 — 같은 이벤트를 받는 다른 리스너(알림 등)의 질의가 셈에 섞이지 않게
        riderChangedListener.broadcast(new RiderStatusChangedEvent(2L, 1L, 1L, 1L, "boarded", AT, false));

        assertThat(broadcast.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(statistics.getPrepareStatementCount() - before)
                .as("학생 이름 1 + 정차지 1 + 상태별 인원 1 — 인원을 상태마다 따로 세면 7이 된다").isLessThanOrEqualTo(3);
    }

    @Test
    void 비상_방송_리스너는_트랜잭션_없이_발행돼도_보낸다() throws Exception {
        CountDownLatch broadcast = new CountDownLatch(2); // 학원 · 관리자 채널
        doAnswer(invocation -> {
            broadcast.countDown();
            return null;
        }).when(gateway).send(any(), eq("emergency_raised"), any(), any(), any());

        publisher.publishEvent(new EmergencyRaisedEvent(1L, 1L, "학원", 1L, "1호차", EmergencyType.ACCIDENT,
                new EmergencyRaisedEvent.RaisedBy("기사", "driver", "010-0000-0000"),
                new EmergencyRaisedEvent.Position(null, null), 3, AT));

        assertThat(broadcast.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                .as("트랜잭션 밖 발행이 조용히 버려지면 안전 사안인 비상 방송이 사라진다").isTrue();
    }

    @Test
    void 학생_채널_하나의_전송_예외가_나머지_채널_방송을_막지_않고_실패로_센다() {
        doThrow(new IllegalStateException("전송 실패")).when(gateway).send(eq(WebSocketDestinations.studentRun(10L)), any(), any(), any(), any());
        List<String> sent = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return null;
        }).when(gateway).send(eq(WebSocketDestinations.studentRun(11L)), any(), any(), any(), any());
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return null;
        }).when(gateway).send(eq(WebSocketDestinations.managerRun(1L)), any(), any(), any(), any());

        gateway.broadcastToRunChannels(1L, 1L, List.of(10L, 11L), "stop_arrived", AT, "payload", "payload");

        assertThat(sent).as("앞 학생 채널이 실패해도 뒤 학생 채널과 매니저 채널은 나간다")
                .contains(WebSocketDestinations.studentRun(11L), WebSocketDestinations.managerRun(1L));
    }
}
