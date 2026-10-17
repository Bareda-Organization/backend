package src.backend.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import src.backend.account.repository.AccountRepository;
import src.backend.audit.repository.AuditLogRepository;

/**
 * S-8 — 같은 행위자가 같은 학생을 인스턴스 A 에서 보고 10분 안에 B 에서 다시 봐도 감사 행은 1개다(Ruling 900 E). 묶기 기록이 인스턴스 메모리에 있으면 B 가 "처음 본다" 고
 * 판단해 행이 2개가 된다. 인스턴스 하나 = {@link AuditRecorder} 객체 하나이고, 둘이 같은 Redis · 같은 DB 를 본다.
 */
@SpringBootTest
class AuditRecorderTwoInstanceDedupTest {

    private static final AtomicLong ACTOR_SEQ = new AtomicLong(8_100_000_000L + System.nanoTime() % 1_000_000L * 10);

    private final long actor = ACTOR_SEQ.addAndGet(2);

    @Autowired
    private AuditRecorder instanceA;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
    void 같은_행위자가_같은_학생을_A_와_B_에서_10분_안에_조회하면_감사_행은_1개다() {
        AuditRecorder instanceB = new AuditRecorder(auditLogRepository, accountRepository, clock, transactionManager, redis);

        view(instanceA, "1");
        view(instanceB, "1");

        assertThat(rowCount()).as("A 에서 한 번 · B 에서 한 번 조회한 감사 행").isEqualTo(1);
    }

    @Test
    void 다른_학생은_다른_인스턴스에서_조회해도_각자_기록된다() {
        AuditRecorder instanceB = new AuditRecorder(auditLogRepository, accountRepository, clock, transactionManager, redis);

        view(instanceA, "1");
        view(instanceB, "2");

        assertThat(rowCount()).as("서로 다른 학생 둘").isEqualTo(2);
    }

    private void view(AuditRecorder recorder, String studentId) {
        recorder.recordDataAccessRead(1L, actor, "run_roster", 77L,
                Map.of("student_ids", List.of(studentId), "fields", List.of("note")));
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject("select count(*) from audit_log where actor_account_id = ?", Integer.class, actor);
    }
}
