package src.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.global.common.enums.Role;
import src.backend.notification.command.NotificationDispatcher;
import src.backend.notification.command.NotificationDraft;
import src.backend.notification.command.NotificationOutbox;
import src.backend.notification.entity.NotificationType;

/**
 * BR-069 — 커밋 직후 즉시 발송은 요청 스레드가 아니라 전용 실행기에서 돈다. 요청 스레드에서 수신자 수만큼
 * 순차 발송하면 응답이 그만큼 늦고, 원 연결을 쥔 채 발송 전이 트랜잭션({@code REQUIRES_NEW})이 두 번째 연결을
 * 요구해 동시 요청이 풀 크기를 넘으면 서로의 반납을 기다린다.
 */
@SpringBootTest
class NotificationDispatchAsyncTest {

    private static final String DEDUP_KEY = "br069-async";

    @Autowired
    private NotificationOutbox notificationOutbox;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private NotificationDispatcher notificationDispatcher;

    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM notification_log WHERE dedup_key = ?", DEDUP_KEY);
    }

    @Test
    void 즉시_발송은_커밋한_스레드가_아닌_곳에서_돈다() {
        AtomicReference<Thread> dispatchThread = new AtomicReference<>();
        doAnswer(invocation -> {
            dispatchThread.set(Thread.currentThread());
            return invocation.callRealMethod();
        }).when(notificationDispatcher).dispatch(anyLong());

        transactionTemplate.executeWithoutResult(status -> notificationOutbox.append(new NotificationDraft(1L, 1L,
                "수신자", Role.STAFF, NotificationType.RUN_ENDED, "제목", "본문", DEDUP_KEY)));

        verify(notificationDispatcher, timeout(5000)).dispatch(anyLong());
        assertThat(dispatchThread.get()).isNotSameAs(Thread.currentThread());
    }
}
