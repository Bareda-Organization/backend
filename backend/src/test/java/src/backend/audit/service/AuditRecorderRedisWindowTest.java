package src.backend.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;


/**
 * 같은 행위자가 같은 학생을 10분 안에 다시 봐도 감사 행은 1개이고, 다른 학생은 각자 기록된다(Ruling 900 E). 묶기 기록은 {@link AuditRecorder} 객체의 메모리가 아니라 Redis 에 있다.
 */
@SpringBootTest
class AuditRecorderRedisWindowTest {

    private static final AtomicLong ACTOR_SEQ = new AtomicLong(8_100_000_000L + System.nanoTime() % 1_000_000L * 10);

    private final long actor = ACTOR_SEQ.addAndGet(2);

    @Autowired
    private AuditRecorder recorder;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from audit_log where actor_account_id = ?", actor);
        redis.delete(redis.keys("schoolbus:audit-dedup:" + actor + ":*"));
    }

    @Test
    void 같은_행위자가_같은_학생을_10분_안에_두_번_조회하면_감사_행은_1개다() {
        view("1");
        view("1");

        assertThat(rowCount()).as("같은 학생을 두 번 조회한 감사 행").isEqualTo(1);
    }

    @Test
    void 다른_학생은_각자_기록된다() {
        view("1");
        view("2");

        assertThat(rowCount()).as("서로 다른 학생 둘").isEqualTo(2);
    }

    private void view(String studentId) {
        recorder.recordDataAccessRead(1L, actor, "run_roster", 77L,
                Map.of("student_ids", List.of(studentId), "fields", List.of("note")));
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject("select count(*) from audit_log where actor_account_id = ?", Integer.class, actor);
    }
}
