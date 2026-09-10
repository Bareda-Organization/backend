package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 팬아웃 실행기의 스레드 수를 속성으로 정할 수 있다는 것을 고정한다
 * ({@link WebSocketConfig#configureClientOutboundChannel}).
 *
 * <p>이 시험이 없으면 {@code configureClientOutboundChannel} 재정의가 통째로 지워져도 컴파일과 나머지
 * 시험이 전부 통과한다 — 그러면 팬아웃 용량이 조용히 <b>기계의 코어 수 × 2</b> 로 돌아가고, 그 사실은
 * 부하가 걸린 운영 환경에서 방송 지연으로만 드러난다(2026-09-09 부하 한계 측정에서 이 값이 붕괴의
 * 1차 지점).
 */
@SpringBootTest(properties = "app.ws.outbound.core-pool-size=7")
class WebSocketOutboundPoolSizeTest {

    @Autowired
    @Qualifier("clientOutboundChannelExecutor")
    private ThreadPoolTaskExecutor outboundExecutor;

    /** 단언 — 준 값이 실제 실행기에 반영된다. 설정 메서드 호출 여부가 아니라 결과 빈을 본다. */
    @Test
    void 팬아웃_실행기_스레드_수는_속성으로_정해진다() {
        assertThat(outboundExecutor.getCorePoolSize())
                .as("이 값이 초당 방송 건수의 천장이다")
                .isEqualTo(7);
    }
}
