package src.backend.notification.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import src.backend.global.common.enums.ManagerRole;
import src.backend.manager.event.AssignmentChangedEvent;

/**
 * {@code assignment_changed} 알림 행에 배치된 회차의 식별자가 실린다(API_SPEC §3.12 {@code run_id}, Ruling 542) —
 * 매니저 앱이 알림을 눌러 그 회차로 가는 근거다.
 *
 * <p>리스너가 커밋 후 새 트랜잭션에서 적재하므로 {@code @Transactional} 시험으로는 행이 보이지 않는다. 이벤트를 직접 먹여
 * 커밋된 행을 읽고, 그 행만 지운다. 회차 식별자는 존재하지 않는 값을 쓴다 — {@code notification_log.run_id} 는 논리적
 * 부모라 FK 가 없다(ERD §4.2).
 */
@SpringBootTest
class AssignmentChangedNotificationRunIdTest {

    /** 시드 매니저 1(학원 1 · 계정 연결됨) — 다른 시험이 쓰지 않는 회차 식별자로 행을 구분한다. */
    private static final long SEED_MANAGER_ID = 1L;

    private static final long SEED_ACADEMY_ID = 1L;

    private static final long UNUSED_RUN_ID = 9_876_543_210L;

    @Autowired
    private AssignmentChangedNotificationListener listener;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void 적재한_행을_지운다() {
        jdbcTemplate.update("DELETE FROM notification_log WHERE dedup_key LIKE ?",
                "assignment_changed:" + UNUSED_RUN_ID + ":%");
    }

    @Test
    void assignment_changed_알림에는_배치된_회차의_식별자가_실린다() {
        listener.appendAssignmentChanged(new AssignmentChangedEvent(UNUSED_RUN_ID, SEED_ACADEMY_ID, SEED_MANAGER_ID,
                ManagerRole.DRIVER, OffsetDateTime.now()));

        assertThat(jdbcTemplate.queryForList(
                "SELECT run_id FROM notification_log WHERE type = 'assignment_changed' AND dedup_key LIKE ?",
                Long.class, "assignment_changed:" + UNUSED_RUN_ID + ":%"))
                .containsExactly(UNUSED_RUN_ID);
    }
}
