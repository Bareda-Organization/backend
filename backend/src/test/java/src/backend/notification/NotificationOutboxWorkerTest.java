package src.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import src.backend.notification.scheduler.NotificationOutboxWorker;

/**
 * Phase 4 목표 3 — 시드의 미발송 행을 워커가 회수해 재발송하고, <b>이미 끝난 행은 건드리지 않는지</b>
 * 본다(ERD §5 의 부분 인덱스 {@code WHERE push_state='pending'} 가 가리키는 술어).
 *
 * <p>두 단언 중 뒤쪽이 이 클래스의 이유다. 회수 조건에서 {@code push_state='pending'} 이 빠진 워커도
 * "미발송분이 sent 로 바뀐다" 는 그대로 통과하는데, 그 워커는 <b>이미 보낸 알림 8건과 포기한 1건을
 * 함께 재발송</b>한다. 학부모는 어제 받은 하차 알림을 오늘 다시 받는다.
 *
 * <p>{@code @Transactional} 이 부재하다 — 발송 상태 전이가 {@code REQUIRES_NEW} 라 테스트 트랜잭션
 * 안에서는 관측되지 않는다. 대신 시드 행을 {@link #시드_알림을_되돌린다()} 가 앞뒤로 되돌린다.
 */
@SpringBootTest
class NotificationOutboxWorkerTest {

    /** 시드의 유일한 미발송 행({@code V2__seed_data.sql} — {@code no_show}, 학부모 계정 6). */
    private static final long 시드_미발송_행 = 4L;

    /** 시드의 재시도 상한 초과 행 — 워커의 회수 대상 밖이다. */
    private static final long 시드_포기_행 = 9L;

    /** 시드에서 이미 발송이 끝난 행 8건 — 회수 대상 밖이다. */
    private static final List<Long> 시드_발송완료_행 = List.of(1L, 2L, 3L, 5L, 6L, 7L, 8L, 10L);

    @Autowired
    private NotificationOutboxWorker notificationOutboxWorker;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ScheduledAnnotationBeanPostProcessor scheduledPostProcessor;

    @BeforeEach
    @AfterEach
    void 시드_알림을_되돌린다() {
        jdbcTemplate.update("""
                UPDATE notification_log
                   SET push_state = 'pending', push_attempts = 0, last_attempt_at = NULL,
                       sent_at = NULL, fail_reason = NULL
                 WHERE id = ?
                """, 시드_미발송_행);
    }

    /**
     * {@code @Scheduled} 가 실제로 <b>등록</b>됐는지 본다 — 애너테이션을 지워도 위 두 테스트는
     * {@code sweep()} 을 직접 부르므로 그대로 통과하고, 그때 안전망은 운영에서 영영 돌지 않는다.
     *
     * <p>실행 시각까지 기다리지 않는 이유는 테스트 실행 동안 최초 지연이 하루로 미뤄져 있기
     * 때문이다({@code build.gradle}) — 기다리면 배경 워커가 다른 테스트의 행을 집는다.
     */
    @Test
    void 아웃박스_워커가_스케줄러에_등록된다() {
        assertThat(scheduledPostProcessor.getScheduledTasks())
                .as("@Scheduled 가 등록되지 않으면 즉시 발송이 실패한 알림을 아무도 회수하지 않는다")
                .anyMatch(task -> task.getTask().toString().contains("NotificationOutboxWorker"));
    }

    @Test
    void 워커가_pending_행을_회수해_sent_로_전이시킨다() {
        notificationOutboxWorker.sweep();

        Map<String, Object> row = 알림_행(시드_미발송_행);
        assertThat(row.get("push_state"))
                .as("회수하지 못하면 즉시 발송이 실패한 알림은 영영 발송되지 않는다")
                .isEqualTo("sent");
        assertThat(row.get("sent_at"))
                .as("sent 인데 sent_at 이 비면 재발송 시점을 되짚을 수단이 부재하다")
                .isNotNull();
        assertThat((Integer) row.get("push_attempts"))
                .as("시도 횟수가 늘지 않으면 재시도 상한 판정이 영영 성립하지 않아 포기할 수단이 부재하다")
                .isEqualTo(1);
    }

    @Test
    void 워커는_sent_와_failed_행의_push_attempts_를_바꾸지_않는다() {
        Map<Long, Map<String, Object>> 이전 = 끝난_행들();

        notificationOutboxWorker.sweep();

        Map<Long, Map<String, Object>> 이후 = 끝난_행들();
        assertThat(이후)
                .as("회수 조건에서 push_state='pending' 이 빠지면 이미 보낸 알림 8건과 포기한 1건이 다시 나간다")
                .isEqualTo(이전);
    }

    /** 발송이 끝난 시드 행 9건의 시도 횟수·발송 시각 — 워커 전후로 같아야 한다. */
    private Map<Long, Map<String, Object>> 끝난_행들() {
        java.util.LinkedHashMap<Long, Map<String, Object>> snapshot = new java.util.LinkedHashMap<>();
        java.util.stream.Stream.concat(시드_발송완료_행.stream(), java.util.stream.Stream.of(시드_포기_행))
                .forEach(id -> snapshot.put(id, jdbcTemplate.queryForMap("""
                        SELECT push_state, push_attempts, sent_at FROM notification_log WHERE id = ?
                        """, id)));
        return snapshot;
    }

    private Map<String, Object> 알림_행(long id) {
        return jdbcTemplate.queryForMap("""
                SELECT push_state, push_attempts, sent_at FROM notification_log WHERE id = ?
                """, id);
    }
}
