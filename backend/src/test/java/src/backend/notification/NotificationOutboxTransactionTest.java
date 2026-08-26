package src.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.global.common.enums.Role;
import src.backend.notification.command.NotificationDraft;
import src.backend.notification.command.NotificationOutbox;
import src.backend.notification.entity.NotificationType;

/**
 * 아웃박스 적재가 <b>호출자의 트랜잭션 안에서</b> 일어나는지 고정한다(TECH_DECISIONS §7.2 "상태
 * 변경과 원자적 — 둘 다 커밋되거나 둘 다 롤백").
 *
 * <p>목표 2 가 보는 것과 반대 방향이다 — 그쪽은 <b>커밋된</b> 상태 변경에 알림 행이 남는지를 보고,
 * 여기는 <b>롤백된</b> 상태 변경에 알림 행이 남지 <b>않는지</b>를 본다. 적재가 자기 트랜잭션을 열면
 * (예: {@code REQUIRES_NEW}) 목표 1·2 는 그대로 통과하는데 이 축만 깨진다 — 일어나지 않은 승인이
 * 통지되고, 사용자는 승인됐다고 믿은 채 로그인이 막힌다.
 */
@SpringBootTest
class NotificationOutboxTransactionTest {

    private static final String DEDUP_KEY_PREFIX = "p4t1tx:";

    @Autowired
    private NotificationOutbox notificationOutbox;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM notification_log WHERE dedup_key LIKE ?", DEDUP_KEY_PREFIX + "%");
    }

    @Test
    void 적재를_감싼_트랜잭션이_롤백되면_알림_행도_남지_않는다() {
        String dedupKey = DEDUP_KEY_PREFIX + "rollback";

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            notificationOutbox.append(초안(dedupKey));
            throw new IllegalStateException("상태 변경이 실패한 상황을 대신한다");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(적재된_행_수(dedupKey))
                .as("호출자의 트랜잭션이 되돌아갔는데 알림만 남으면 일어나지 않은 일을 통지한다")
                .isZero();
    }

    @Test
    void 트랜잭션_없이_적재하면_거부된다() {
        assertThatThrownBy(() -> notificationOutbox.append(초안(DEDUP_KEY_PREFIX + "notx")))
                .as("자기 트랜잭션을 조용히 여는 순간 상태 변경과의 원자성이 사라진다")
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private NotificationDraft 초안(String dedupKey) {
        return new NotificationDraft(1L, 8L, "조대기", Role.PARENT, NotificationType.SIGNUP_DECIDED,
                "가입 심사 안내", "가입 심사 결과가 나왔습니다.", dedupKey);
    }

    private int 적재된_행_수(String dedupKey) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM notification_log WHERE dedup_key = ?",
                Integer.class, dedupKey);
    }
}
