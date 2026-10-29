package src.backend.notification.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.health.NotificationBacklog;

/**
 * 관제 헬스(API_SPEC §6.18)가 알림 모듈을 건드리지 않고 읽는 발송 대기 적체 수 — 기준 시각 이전에 적재되고 아직 {@code pending} 인 행만 센다. 다른 시험이 남긴 행이 섞일 수 있어
 * 심기 전후의 증가분으로 본다.
 */
@SpringBootTest
@Transactional
class NotificationBacklogQueryServiceTest {

    @Autowired
    private NotificationBacklog backlog;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @Test
    @DisplayName("기준 시각 이전에 적재된 pending 행만 센다 — 기준 이후 행과 이미 발송된 행은 세지 않는다")
    void 기준_이전의_pending_행만_센다() {
        OffsetDateTime threshold = OffsetDateTime.now(clock).minusMinutes(5);
        long before = backlog.countPendingCreatedBefore(threshold);

        행을_심는다("backlog-old-pending", "pending", threshold.minusMinutes(1));
        행을_심는다("backlog-old-sent", "sent", threshold.minusMinutes(1));
        행을_심는다("backlog-new-pending", "pending", threshold.plusMinutes(1));

        assertThat(backlog.countPendingCreatedBefore(threshold)).isEqualTo(before + 1);
    }

    private void 행을_심는다(String dedupKey, String pushState, OffsetDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO notification_log (academy_id, recipient_account_id, recipient_name, recipient_role, type,
                        title, body, push_state, dedup_key, created_at)
                VALUES (1, 5, '적체', 'parent', 'signup_decided', '가입 심사 안내', '결과가 나왔습니다.', ?, ?, ?)
                """, pushState, dedupKey + "-" + System.nanoTime(), createdAt);
    }
}
