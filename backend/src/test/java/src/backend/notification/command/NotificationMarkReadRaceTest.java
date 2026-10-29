package src.backend.notification.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import testsupport.concurrency.RepositoryReadHooks;

/**
 * 읽음 처리가 발송 중인 알림의 발송 상태를 건드리지 않는다 — 읽음 처리가 알림을 읽은 뒤 쓰기 전에 발송 쪽이 {@code sent} 로 확정해도,
 * 읽음 처리의 쓰기는 읽음 시각과 확인 표시만 바꾼다. 행 전체를 다시 쓰면 {@code pending} · 시도 0 · 발송 시각 없음으로 되돌아가
 * 같은 푸시가 한 번 더 나간다. 트랜잭션 없이 실제 커밋을 남기므로 만든 행은 직접 지운다.
 */
@SpringBootTest
@Import(RepositoryReadHooks.class)
class NotificationMarkReadRaceTest {

    private static final String DEDUP_KEY = "mark-read-race";

    private static final long PARENT_ACCOUNT = 5L;

    @Autowired
    private NotificationReadCommandService readService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long notificationId;

    @BeforeEach
    void 아직_발송_전인_알림을_만든다() {
        뒷정리한다();
        jdbcTemplate.update("""
                INSERT INTO notification_log (academy_id, recipient_account_id, recipient_name, recipient_role, type,
                        title, body, push_state, dedup_key)
                VALUES (1, ?, '수신자', 'parent', 'delay', '제목', '본문', 'pending', ?)
                """, PARENT_ACCOUNT, DEDUP_KEY);
        notificationId = jdbcTemplate.queryForObject("SELECT id FROM notification_log WHERE dedup_key = ?", Long.class,
                DEDUP_KEY);
    }

    @AfterEach
    void 뒷정리한다() {
        RepositoryReadHooks.clear();
        jdbcTemplate.update("DELETE FROM notification_log WHERE dedup_key = ?", DEDUP_KEY);
    }

    @Test
    void 읽음_처리가_읽고_쓰는_사이에_발송이_확정돼도_발송_상태는_그대로다() throws Exception {
        RepositoryReadHooks.afterRead("findById", () -> 다른_연결에서_발송을_확정한다());

        readService.markRead(new AuthUser(PARENT_ACCOUNT, Long.valueOf(SeedFixtures.ACADEMY_A_ID), Role.PARENT,
                AccountStatus.ACTIVE, false), notificationId);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT push_state, push_attempts, sent_at, read_at, acked, acked_at FROM notification_log WHERE id = ?",
                notificationId);
        assertThat(row.get("push_state")).as("발송 확정이 되돌려졌다").isEqualTo("sent");
        assertThat(row.get("push_attempts")).isEqualTo(1);
        assertThat(row.get("sent_at")).isNotNull();
        assertThat(row.get("read_at")).as("읽음 처리 자체는 반영된다").isNotNull();
        assertThat(row.get("acked")).isEqualTo(true);
        assertThat(row.get("acked_at")).isNotNull();
    }

    /** 읽음 처리의 트랜잭션과 별개의 연결에서 커밋한다 — 발송 쪽 전이가 별도 트랜잭션인 것과 같다. */
    private void 다른_연결에서_발송을_확정한다() {
        Thread dispatcher = new Thread(() -> jdbcTemplate.update("""
                UPDATE notification_log SET push_state = 'sent', push_attempts = 1, last_attempt_at = now(),
                       sent_at = now() WHERE id = ?
                """, notificationId));
        dispatcher.start();
        try {
            dispatcher.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
