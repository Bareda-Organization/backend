package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 알림 발송 실행기의 스레드 수를 속성으로 정할 수 있다(R46 D #15) — 코드 상수 2 를 다시 박으면 이 시험이 실패한다.
 * 속성이 없을 때의 기본값 8 은 {@link NotificationDispatchDefaultPoolSizeTest} 가 고정한다.
 */
@SpringBootTest(properties = "app.notification.dispatch.core-pool-size=5")
class NotificationDispatchPoolSizeTest {

    @Autowired
    @Qualifier("notificationDispatchExecutor")
    private ThreadPoolTaskExecutor executor;

    @Test
    void 알림_발송_실행기_스레드_수는_속성으로_정해진다() {
        assertThat(executor.getCorePoolSize()).isEqualTo(5);
        assertThat(executor.getMaxPoolSize()).isEqualTo(5);
    }
}
