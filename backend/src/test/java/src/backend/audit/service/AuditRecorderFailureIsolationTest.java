package src.backend.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 감사 적재가 실패해도 조회 트랜잭션은 성공한다(Ruling 242 · BR-038) — {@link AuditRecorder} 자바독의 약속.
 *
 * <p>실패를 가짜 응답 객체로 흉내 내지 않고 <b>실제 INSERT 를 실패</b>시킨다({@code target_type} 은
 * {@code varchar(50)}). 저장소의 트랜잭션 경계 안에서 난 예외만 트랜잭션에 롤백 표시를 남기므로, 가짜로
 * 던지면 결함 경로를 지나지 않는다.
 *
 * <p>{@code @Transactional} 이 부재하다 — 조회 서비스처럼 읽기 전용 트랜잭션을 직접 열어 그 커밋을 본다. 감사 행은
 * {@code REQUIRES_NEW} 로 커밋되므로 시험 끝에 행위자 기준으로 직접 지운다.
 */
@SpringBootTest
class AuditRecorderFailureIsolationTest {

    @Autowired
    private AuditRecorder auditRecorder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 다른 감사 시험과 겹치지 않는 가짜 행위자 — 묶기 키가 행위자·학생·필드라 시험마다 달라야 한다. */
    private static final long ACTOR = 7_470_000_001L;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from audit_log where actor_account_id = ?", ACTOR);
    }

    @Test
    void 감사_적재가_실패해도_조회_트랜잭션은_예외_없이_끝난다() {
        TransactionTemplate readOnlyQuery = new TransactionTemplate(transactionManager);
        readOnlyQuery.setReadOnly(true);
        String tooLongTargetType = "t".repeat(51);

        assertThatCode(() -> readOnlyQuery.executeWithoutResult(status ->
                auditRecorder.recordDataAccessRead(1L, 2L, tooLongTargetType, 1L, Map.of())))
                .doesNotThrowAnyException();
    }

    /**
     * 적재가 실패하면 그 조회가 잡아 둔 묶기 기록을 되돌린다 — 되돌리지 않으면 같은 조회를 다시 해도 "이미 기록함" 으로 건너뛰어
     * 기록이 10분 동안 영영 빠진다. 학생 목록이 실린 조회로 실패시키고(학생 목록이 없으면 묶기 기록 자체가 생기지 않는다), 같은
     * 조회를 다시 해 행이 생기는지 본다.
     */
    @Test
    void 적재가_실패한_조회는_묶기_기록을_되돌려_다음_조회가_행을_남긴다() {
        Map<String, Object> detail = Map.of("student_ids", List.of("1"), "fields", List.of("guardian_phone"));

        auditRecorder.recordDataAccessRead(1L, ACTOR, "t".repeat(51), 1L, detail);
        auditRecorder.recordDataAccessRead(1L, ACTOR, "student", 1L, detail);

        assertThat(jdbcTemplate.queryForObject("select count(*) from audit_log where actor_account_id = ?",
                Integer.class, ACTOR)).as("첫 호출은 실패했으므로 두 번째 호출이 새 행을 남긴다").isEqualTo(1);
    }
}
