package src.backend.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승차 취소 · 하차 취소 알림 종류는 출발 시점 발송으로 바뀌며 폐기됐다 — 열거값 · 설정 판정 · DB 체크 제약 어디에도 남아 있지 않다.
 * 한 곳에만 남으면 아무 코드도 만들지 않는 값을 DB 가 받아 주거나, DB 가 거절하는 값을 코드가 만든다.
 */
@SpringBootTest
@Transactional
class RetiredNotificationTypeTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 열거값에_폐기된_취소_종류가_없다() {
        assertThat(Arrays.stream(NotificationType.values()).map(Enum::name))
                .noneMatch(name -> name.equals("BOARDING_CANCELED") || name.equals("ALIGHTING_CANCELED"));
    }

    @Test
    void 승차_취소_종류는_DB_가_거절한다() {
        assertThatThrownBy(() -> 알림을_넣는다("boarding_canceled")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 하차_취소_종류는_DB_가_거절한다() {
        assertThatThrownBy(() -> 알림을_넣는다("alighting_canceled")).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void 알림을_넣는다(String type) {
        jdbcTemplate.update("""
                INSERT INTO notification_log (academy_id, recipient_account_id, recipient_name, recipient_role, type,
                        title, body, dedup_key)
                VALUES (1, 5, '수신자', 'parent', ?, '제목', '본문', ?)
                """, type, "retired-" + type);
    }
}
