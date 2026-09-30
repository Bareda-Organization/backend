package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 속성을 주지 않으면 알림 발송 실행기는 스레드 8개다(R46 D #15 — 근거는 {@link NotificationDispatchConfig}). */
@SpringBootTest
class NotificationDispatchDefaultPoolSizeTest {

    @Autowired
    @Qualifier("notificationDispatchExecutor")
    private ThreadPoolTaskExecutor executor;

    @Test
    void 알림_발송_실행기_기본_스레드_수는_8이다() {
        assertThat(executor.getCorePoolSize()).isEqualTo(8);
    }
}
