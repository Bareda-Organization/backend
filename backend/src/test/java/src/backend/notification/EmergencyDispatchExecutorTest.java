package src.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.global.common.enums.Role;
import src.backend.notification.command.NotificationDraft;
import src.backend.notification.command.NotificationOutbox;
import src.backend.notification.entity.NotificationType;

/**
 * 비상 알림의 즉시 발송은 <b>전용 실행기</b>(스레드 2)에서 돈다(R46 S-2 ③) — 일반 발송 실행기(8스레드·큐 1,000)와 같은 줄에 서면
 * FCM 이 응답하지 않을 때 큐 앞의 일반 알림 100건 뒤에서 비상 알림이 몇 분 늦게 첫 시도를 한다. 반대로 일반 알림이 전용
 * 실행기를 차지해서도 안 된다.
 */
@SpringBootTest
class EmergencyDispatchExecutorTest {

    private static final String KEY_PREFIX = "r46emergencyexec:";

    @MockitoSpyBean(name = "notificationDispatchExecutor")
    private ThreadPoolTaskExecutor generalExecutor;

    @MockitoSpyBean(name = "emergencyDispatchExecutor")
    private ThreadPoolTaskExecutor emergencyExecutor;

    @Autowired
    private NotificationOutbox notificationOutbox;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void 적재한_행을_지운다() {
        jdbcTemplate.update("DELETE FROM notification_log WHERE dedup_key LIKE ?", KEY_PREFIX + "%");
    }

    @Test
    @DisplayName("비상·비상 취소 알림은 전용 실행기로, 그 밖의 알림은 일반 실행기로 보낸다")
    void 비상_알림은_전용_실행기로_간다() {
        commit(List.of(draft("emergency", NotificationType.EMERGENCY)));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> verify(emergencyExecutor, atLeastOnce()).execute(any()));
        verify(generalExecutor, never()).execute(any());
        Mockito.clearInvocations(generalExecutor, emergencyExecutor);

        commit(List.of(draft("delay", NotificationType.DELAY)));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> verify(generalExecutor, atLeastOnce()).execute(any()));
        verify(emergencyExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("전용 실행기는 스레드 2개다")
    void 전용_실행기는_스레드_2개다(@Qualifier("emergencyDispatchExecutor") ThreadPoolTaskExecutor executor) {
        assertThat(executor.getCorePoolSize()).isEqualTo(2);
        assertThat(executor.getMaxPoolSize()).isEqualTo(2);
    }

    private void commit(List<NotificationDraft> drafts) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> notificationOutbox.appendAll(drafts));
    }

    private NotificationDraft draft(String suffix, NotificationType type) {
        return new NotificationDraft(777_002L, 7_002L, "수신자", Role.STAFF, type, "제목", "본문", KEY_PREFIX + suffix);
    }
}
