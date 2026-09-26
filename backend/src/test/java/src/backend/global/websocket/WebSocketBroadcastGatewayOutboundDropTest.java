package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import src.backend.observability.metrics.WebSocketOutboundDropMetrics;
import src.backend.observability.metrics.WebSocketPublishMetrics;

/**
 * BR-170 — 팬아웃 실행기 큐가 가득 찼을 때 위치({@code position})만 버려지고, 비상·승하차 등
 * 나머지 이벤트는 전달된다는 것을 고정한다(Ruling 349). 버리는 쪽만 시험하면 "전부 버림" 결함이
 * 통과하므로 두 경우를 함께 확인한다.
 *
 * <p>Spring 전체 컨텍스트 없이 {@link WebSocketBroadcastGateway} 를 직접 생성한다 — 큐 포화는
 * 작은 용량의 실제 {@link ThreadPoolTaskExecutor} 를 코어 스레드까지 채워 재현한다.
 */
class WebSocketBroadcastGatewayOutboundDropTest {

    private ThreadPoolTaskExecutor outboundExecutor;
    private SimpMessagingTemplate messagingTemplate;
    private SimpleMeterRegistry meterRegistry;
    private WebSocketBroadcastGateway gateway;
    private CountDownLatch blockCoreThread;

    @BeforeEach
    void setUp() {
        outboundExecutor = new ThreadPoolTaskExecutor();
        outboundExecutor.setCorePoolSize(1);
        outboundExecutor.setMaxPoolSize(1);
        outboundExecutor.setQueueCapacity(1);
        outboundExecutor.initialize();

        messagingTemplate = mock(SimpMessagingTemplate.class);
        meterRegistry = new SimpleMeterRegistry();
        gateway = new WebSocketBroadcastGateway(messagingTemplate, new WebSocketPublishMetrics(meterRegistry),
                new WebSocketOutboundDropMetrics(meterRegistry), outboundExecutor, Clock.systemUTC());

        // 코어 스레드 1개를 오래 붙들어 두고 큐(용량 1)를 채운다 — 이후 제출은 "큐 가득 참" 상태를 본다.
        blockCoreThread = new CountDownLatch(1);
        outboundExecutor.execute(() -> awaitUninterruptibly(blockCoreThread));
        outboundExecutor.execute(() -> {
        });
        assertThat(outboundExecutor.getThreadPoolExecutor().getQueue().remainingCapacity())
                .as("사전 조건 — 큐가 가득 차 있어야 이 시험이 의미가 있다")
                .isEqualTo(0);
    }

    @AfterEach
    void tearDown() {
        blockCoreThread.countDown();
        outboundExecutor.shutdown();
    }

    @Test
    void 위치_방송은_팬아웃_큐가_가득_차면_버려진다() {
        gateway.send("/topic/students/1/run", "position", 10L, OffsetDateTime.now(), new Object());

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(WebSocketEnvelope.class));
        assertThat(meterRegistry.get("schoolbus.ws.outbound.dropped").tag("event", "position").counter().count())
                .as("버려진 위치 방송 1건이 지표에 반영돼야 한다")
                .isEqualTo(1.0);
    }

    @Test
    void 비상_이벤트는_팬아웃_큐가_가득_차도_버려지지_않는다() {
        gateway.send("/topic/academies/1/live", "emergency_raised", 10L, OffsetDateTime.now(), new Object());

        verify(messagingTemplate, times(1)).convertAndSend(anyString(), any(WebSocketEnvelope.class));
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                }
                catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
