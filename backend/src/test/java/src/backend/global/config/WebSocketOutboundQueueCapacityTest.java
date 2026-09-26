package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ThreadPoolExecutor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 팬아웃 실행기 큐에 상한이 있고, 큐가 가득 차도 메시지를 잃지 않는다는 것을 고정한다(BR-170).
 *
 * <p>고치기 전에는 {@code queueCapacity} 를 지정하지 않아 {@code Integer.MAX_VALUE}(사실상 무한)였다
 * — 이 시험이 없으면 그 값이 조용히 되돌아가도 컴파일도 나머지 시험도 걸리지 않는다.
 */
@SpringBootTest(properties = "app.ws.outbound.queue-capacity=123")
class WebSocketOutboundQueueCapacityTest {

    @Autowired
    @Qualifier("outboundTaskExecutor")
    private ThreadPoolTaskExecutor outboundExecutor;

    @Test
    void 팬아웃_실행기_큐는_속성으로_정한_만큼만_받는다() {
        ThreadPoolExecutor delegate = outboundExecutor.getThreadPoolExecutor();
        assertThat(delegate.getQueue().remainingCapacity())
                .as("큐 상한이 없으면 무한 큐라 힙이 느린 구독자만큼 계속 자란다(2026-09-09 부하 한계 측정)")
                .isEqualTo(123);
    }

    @Test
    void 큐가_가득_차도_거부되지_않고_호출_스레드가_대신_보낸다() {
        ThreadPoolExecutor delegate = outboundExecutor.getThreadPoolExecutor();
        assertThat(delegate.getRejectedExecutionHandler())
                .as("거부 정책이 기본(AbortPolicy)이면 큐 포화 때 비상·승하차 방송까지 예외로 유실된다")
                .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }
}
