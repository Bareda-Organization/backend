package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ThreadPoolExecutor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.broker.DefaultSubscriptionRegistry;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 실시간 경로 설정 고정 —기본값이 조용히 되돌아가도 컴파일도 다른 시험도 걸리지 않는 값들이다. 메시지 순서 보장이 켜져 있고, 수신 채널
 * 실행기는 스레드 수와 큐 상한이 명시돼 실제 수신 채널이 그것을 쓰며, 하트비트 스케줄러는 2개 이상이고, 구독 조회 보관 상한은 8,192 이상이다.
 */
@SpringBootTest(properties = {"app.ws.inbound.core-pool-size=6", "app.ws.inbound.queue-capacity=321"})
class WebSocketRealtimeConfigTest {

    @Autowired
    private SimpleBrokerMessageHandler simpleBroker;

    @Autowired
    @Qualifier("clientInboundChannel")
    private ExecutorSubscribableChannel inboundChannel;

    @Autowired
    @Qualifier("inboundTaskExecutor")
    private ThreadPoolTaskExecutor inboundExecutor;

    @Autowired
    @Qualifier("heartbeatScheduler")
    private TaskScheduler heartbeatScheduler;

    @Test
    void 메시지_순서_보장이_켜져_있다() {
        assertThat(simpleBroker.isPreservePublishOrder())
                .as("꺼지면 같은 세션의 연속 메시지가 실행기 스레드 경쟁으로 뒤집혀 도착할 수 있다").isTrue();
    }

    @Test
    void 수신_채널은_스레드_수와_큐_상한이_명시된_실행기를_쓴다() {
        assertThat(inboundChannel.getExecutor()).as("수신 채널이 기본 실행기(큐 무한)를 그대로 쓰면 안 된다").isSameAs(inboundExecutor);
        ThreadPoolExecutor delegate = inboundExecutor.getThreadPoolExecutor();
        assertThat(delegate.getCorePoolSize()).isEqualTo(6);
        assertThat(delegate.getMaximumPoolSize()).isEqualTo(6);
        assertThat(delegate.getQueue().remainingCapacity()).isEqualTo(321);
        assertThat(delegate.getRejectedExecutionHandler()).isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }

    @Test
    void 하트비트_스케줄러는_스레드가_2개_이상이다() {
        ThreadPoolTaskScheduler scheduler = (ThreadPoolTaskScheduler) heartbeatScheduler;
        assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void 구독_조회_보관_상한은_8192_이상이다() {
        DefaultSubscriptionRegistry registry = (DefaultSubscriptionRegistry) simpleBroker.getSubscriptionRegistry();
        assertThat(registry.getCacheLimit()).isGreaterThanOrEqualTo(8192);
    }
}
