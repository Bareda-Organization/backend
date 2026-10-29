package src.backend.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 개인정보(L3) 조회 감사 행의 적재가 실패해도 응답은 그대로지만 실패 건수가 지표
 * {@code schoolbus.audit.read.record.failures}(Prometheus {@code schoolbus_audit_read_record_failures_total})에 오른다 —
 * 로그만 남기면 감사 누락이 계속돼도 알림이 울릴 수단이 없다. 적재가 성공하면 지표는 오르지 않는다.
 *
 * <p>묶기 기록(행위자·학생·필드)이 10분간 Redis 에 남으므로 시험마다 다른 학생 id 를 쓴다 — 같은 학생을 쓰면 앞선 시험의 묶기 기록이
 * 적재 자체를 건너뛰게 해 실행 순서에 따라 결과가 갈린다.
 */
@SpringBootTest
class AuditReadRecordFailureMetricTest {

    private static final String METRIC = "schoolbus.audit.read.record.failures";

    /** 다른 감사 시험과 겹치지 않는 가짜 행위자 — 묶기 키가 행위자·학생·필드라 시험마다 달라야 한다. */
    private static final long ACTOR = 7_470_000_002L;

    @Autowired
    private AuditRecorder auditRecorder;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from audit_log where actor_account_id = ?", ACTOR);
    }

    @Test
    void 단건_적재가_실패하면_실패_지표가_하나_오른다() {
        double before = 실패_건수();

        auditRecorder.recordDataAccessRead(1L, ACTOR, "t".repeat(51), 1L,
                Map.of("student_ids", List.of("11"), "fields", List.of("guardian_phone")));

        assertThat(실패_건수() - before).isEqualTo(1.0);
    }

    @Test
    void 목록_적재가_실패해도_지표는_한_번만_오른다() {
        double before = 실패_건수();

        auditRecorder.recordDataAccessReads(1L, ACTOR, "t".repeat(51), Map.of(
                1L, Map.of("student_ids", List.of("21"), "fields", List.of("guardian_phone")),
                2L, Map.of("student_ids", List.of("22"), "fields", List.of("guardian_phone"))));

        assertThat(실패_건수() - before).isEqualTo(1.0);
    }

    @Test
    void 적재가_성공하면_지표는_오르지_않는다() {
        double before = 실패_건수();

        auditRecorder.recordDataAccessRead(1L, ACTOR, "student", 1L,
                Map.of("student_ids", List.of("31"), "fields", List.of("guardian_phone")));

        assertThat(실패_건수() - before).isZero();
    }

    private double 실패_건수() {
        return meterRegistry.counter(METRIC).count();
    }
}
