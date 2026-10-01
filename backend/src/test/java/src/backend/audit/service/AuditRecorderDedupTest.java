package src.backend.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import src.backend.audit.entity.AuditAction;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;
import testsupport.clock.AdvanceableClock;
import testsupport.clock.AdvanceableClockConfig;

/**
 * R46 감사 A(Ruling 445) — 같은 행위자가 같은 학생의 L3 를 10분 안에 다시 조회하면 새 행을 쓰지 않는다.
 *
 * <p>묶기 키는 <b>행위자·학생</b>이다. 명단·목록 단위로 잡으면 두 번째 조회에 새로 실린 학생의 기록이 빠진다
 * ("누구를 봤나" 가 사라진다) — 그래서 그 경우를 따로 검사한다. {@code @Transactional} 이 부재하다 — 감사 행은
 * {@code REQUIRES_NEW} 로 커밋되므로 시험 끝에 행위자 기준으로 직접 지운다.
 */
@SpringBootTest
@Import(AdvanceableClockConfig.class)
class AuditRecorderDedupTest {

    private static final AtomicLong ACTOR_SEQ = new AtomicLong(8_000_000_000L + System.nanoTime() % 1_000_000L * 10);

    private static final long RUN_ID = 77L;

    private final long actor = ACTOR_SEQ.addAndGet(2);

    private final long otherActor = actor + 1;

    @Autowired
    private AuditRecorder auditRecorder;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AdvanceableClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        clock.reset();
        jdbcTemplate.update("delete from audit_log where actor_account_id in (?, ?)", actor, otherActor);
    }

    @Test
    void 같은_학생을_10분_안에_다시_조회하면_행이_늘지_않고_10분이_지나면_새_행이_생긴다() {
        roster(actor, "1", "2");
        assertThat(rowCount(actor)).isEqualTo(1);

        clock.advance(Duration.ofMinutes(9).plusSeconds(59));
        roster(actor, "1", "2");
        assertThat(rowCount(actor)).as("9분 59초 뒤 — 창 안").isEqualTo(1);

        clock.advance(Duration.ofSeconds(2));
        roster(actor, "1", "2");
        assertThat(rowCount(actor)).as("첫 기록으로부터 10분 1초 뒤 — 창 밖").isEqualTo(2);
    }

    @Test
    void 두_번째_조회에_새_학생이_실리면_그_학생만_기록된다() {
        roster(actor, "1", "2");

        roster(actor, "1", "2", "3");

        // 전체 실행에서는 앞선 시험이 actor 없는 행(login_fail 등)을 남긴다 — 언박싱 비교(==)는 그 행에서 NPE
        List<AuditLog> rows = auditLogRepository.findAll().stream()
                .filter(log -> Objects.equals(actor, log.getActorAccountId()))
                .sorted(Comparator.comparing(AuditLog::getId)).toList();
        assertThat(rows).hasSize(2);
        assertThat(studentIdsOf(rows.get(1))).as("이미 기록한 1·2 는 빼고 새로 실린 3 만").containsExactly("3");
    }

    @Test
    void 다른_행위자는_같은_학생을_조회해도_묶이지_않는다() {
        roster(actor, "1");
        roster(otherActor, "1");

        assertThat(rowCount(actor)).isEqualTo(1);
        assertThat(rowCount(otherActor)).isEqualTo(1);
    }

    @Test
    void 수정_삭제_로그인_기록은_조회_기록과_같은_창_안에서도_묶이지_않는다() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        roster(actor, "1");

        auditLogRepository.save(AuditLog.forDataAccessChange(AuditAction.UPDATE, 1L, actor, "x", "student", 1L,
                Map.of("fields", List.of("note")), now));
        auditLogRepository.save(AuditLog.forDataAccessChange(AuditAction.DELETE, 1L, actor, "x", "student", 1L,
                Map.of(), now));
        auditLogRepository.save(AuditLog.forLoginSuccess(1L, actor, "x", "203.0.113.7", now));

        assertThat(jdbcTemplate.queryForList(
                "select action from audit_log where actor_account_id = ? order by id", String.class, actor))
                .containsExactly("read", "update", "delete", "login_success");
    }

    private void roster(long actorAccountId, String... studentIds) {
        auditRecorder.recordDataAccessRead(1L, actorAccountId, "run_roster", RUN_ID,
                Map.of("student_ids", List.of(studentIds), "fields", List.of("note")));
    }

    private int rowCount(long actorAccountId) {
        return jdbcTemplate.queryForObject("select count(*) from audit_log where actor_account_id = ?",
                Integer.class, actorAccountId);
    }

    @SuppressWarnings("unchecked")
    private static List<String> studentIdsOf(AuditLog row) {
        return (List<String>) row.getDetail().get("student_ids");
    }
}
