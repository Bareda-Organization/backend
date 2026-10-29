package src.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import src.backend.notification.command.NotificationDispatcher;
import src.backend.notification.push.spec.PushMessage;
import src.backend.notification.push.spec.PushSender;
import src.backend.notification.scheduler.NotificationOutboxWorker;

/**
 * 아웃박스 워커의 한 틱과 발송 재시도 — 한 행의 예외가 다른 행을 막지 않고, 비상 알림은 만든 지 10분이 지나기 전에는 시도 상한 때문에
 * 포기되지 않으며, 실패한 비상 발송은 운영 지표로 센다.
 *
 * <p>발송기는 목으로 바꿔 성공·실패를 시험이 정한다. 발송 상태 전이가 {@code REQUIRES_NEW} 라 트랜잭션 없이 실제 커밋을 남기고, 심은 행과
 * 시드 미발송 행(id 4)의 상태는 앞뒤로 되돌린다.
 */
@SpringBootTest
class NotificationOutboxSweepTest {

    private static final String KEY_PREFIX = "sweep-test:";

    private static final long RECIPIENT = 4006L;

    private static final String EMERGENCY_FAILURES = "schoolbus.notification.push.emergency.failures";

    @Autowired
    private NotificationOutboxWorker worker;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private PushSender pushSender;

    @MockitoSpyBean
    private NotificationDispatcher dispatcher;

    @BeforeEach
    @AfterEach
    void 심은_행과_시드_행을_되돌린다() {
        jdbcTemplate.update("DELETE FROM notification_log WHERE dedup_key LIKE ?", KEY_PREFIX + "%");
        jdbcTemplate.update("""
                UPDATE notification_log
                   SET push_state = 'pending', push_attempts = 0, last_attempt_at = NULL, sent_at = NULL, fail_reason = NULL
                 WHERE id = 4
                """);
        reset(pushSender);
    }

    @Test
    void 한_행의_예외가_같은_틱의_다른_행_발송을_막지_않는다() {
        long poison = 행("poison", "delay", "pending", 0, 120, null);
        long healthy = 행("healthy", "delay", "pending", 0, 60, null);
        doThrow(new IllegalStateException("한 행만 망가졌다")).when(dispatcher).dispatch(eq(poison));

        worker.sweep();

        assertThat(상태(healthy)).as("앞 행이 예외를 던져도 뒤 행은 발송돼야 한다").isEqualTo("sent");
    }

    @Test
    void 비상_알림은_10분_안에는_시도_상한에_닿아도_pending_으로_남고_실패를_센다() {
        long id = 행("emergency-in-window", "emergency", "pending", 2, 5, 2);
        doThrow(new IllegalStateException("fcm down")).when(pushSender).send(any(PushMessage.class));
        double before = 비상_실패_수();

        dispatcher.dispatch(id);

        assertThat(상태(id)).as("만든 지 5분이라 아직 포기하면 안 된다").isEqualTo("pending");
        assertThat(비상_실패_수() - before).as("실패한 비상 발송은 지표로 센다").isEqualTo(1.0);
    }

    @Test
    void 비상_취소_알림도_같은_창을_쓴다() {
        long id = 행("emergency-canceled-in-window", "emergency_canceled", "pending", 2, 5, 2);
        doThrow(new IllegalStateException("fcm down")).when(pushSender).send(any(PushMessage.class));
        double before = 비상_실패_수();

        dispatcher.dispatch(id);

        assertThat(상태(id)).isEqualTo("pending");
        assertThat(비상_실패_수() - before).isEqualTo(1.0);
    }

    @Test
    void 비상_알림도_10분이_지난_뒤_상한에_닿으면_failed_로_굳는다() {
        long id = 행("emergency-past-window", "emergency", "pending", 2, 11, 2);
        doThrow(new IllegalStateException("fcm down")).when(pushSender).send(any(PushMessage.class));

        dispatcher.dispatch(id);

        assertThat(상태(id)).isEqualTo("failed");
    }

    @Test
    void 비상이_아닌_알림은_상한에_닿으면_창과_무관하게_failed_이고_비상_지표를_올리지_않는다() {
        long id = 행("normal-in-window", "delay", "pending", 2, 5, 2);
        doThrow(new IllegalStateException("fcm down")).when(pushSender).send(any(PushMessage.class));
        double before = 비상_실패_수();

        dispatcher.dispatch(id);

        assertThat(상태(id)).isEqualTo("failed");
        assertThat(비상_실패_수() - before).isEqualTo(0.0);
    }

    @Test
    void 상한을_넘긴_비상_알림도_10분_안이면_워커가_다시_집어_보낸다() {
        long id = 행("emergency-over-cap", "emergency", "pending", 3, 5, 2);

        worker.sweep();

        assertThat(상태(id)).as("시도 3회를 채웠어도 창 안의 비상 알림은 포기하지 않는다").isEqualTo("sent");
    }

    private double 비상_실패_수() {
        return meterRegistry.find(EMERGENCY_FAILURES).counters().stream().mapToDouble(Counter::count).sum();
    }

    private String 상태(long id) {
        return jdbcTemplate.queryForObject("SELECT push_state FROM notification_log WHERE id = ?", String.class, id);
    }

    /** 만든 지 {@code createdMinutesAgo} 분, 마지막 시도가 {@code lastAttemptMinutesAgo} 분 전(없으면 null)인 행을 심고 id 를 준다. */
    private long 행(String key, String type, String state, int attempts, int createdMinutesAgo, Integer lastAttemptMinutesAgo) {
        jdbcTemplate.update("""
                INSERT INTO notification_log (academy_id, recipient_account_id, recipient_name, recipient_role, type,
                        title, body, push_state, push_attempts, last_attempt_at, dedup_key, created_at)
                VALUES (1, ?, '수신자', 'staff', ?, '제목', '본문', ?, ?,
                        CASE WHEN ?::int IS NULL THEN NULL ELSE now() - make_interval(mins => ?::int) END, ?,
                        now() - make_interval(mins => ?))
                """, RECIPIENT, type, state, attempts, lastAttemptMinutesAgo, lastAttemptMinutesAgo, KEY_PREFIX + key,
                createdMinutesAgo);
        return jdbcTemplate.queryForObject("SELECT id FROM notification_log WHERE dedup_key = ?", Long.class,
                KEY_PREFIX + key);
    }
}
