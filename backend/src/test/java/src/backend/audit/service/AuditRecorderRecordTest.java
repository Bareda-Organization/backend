package src.backend.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.audit.entity.AuditAction;
import src.backend.audit.entity.AuditLog;

/**
 * 도메인 모듈이 상태 전이와 같은 트랜잭션에서 남기는 감사 행의 유일한 쓰기 경로({@link AuditRecorder#record}) — 만든 행을 그대로(행동 · 분류 · 대상 · 행위자 · IP · detail 키) 적재하고,
 * 호출자 트랜잭션에 합류해 롤백되면 함께 사라진다.
 */
@SpringBootTest
class AuditRecorderRecordTest {

    @Autowired
    private AuditRecorder recorder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @Test
    @Transactional
    @DisplayName("강제 확정 감사 행은 update · data_access · run 대상과 detail 키를 그대로 적재한다")
    void 만든_행을_그대로_적재한다() {
        recorder.record(AuditLog.forRunForceConfirm(1L, 1L, "admin", 770_001L, "기사 무응답", true, 55L, "203.0.113.5",
                OffsetDateTime.now(clock)));
        recorder.record(AuditLog.forDataAccessChange(AuditAction.DELETE, 1L, 1L, "admin", "student", 770_002L,
                Map.of("fields", "name"), "203.0.113.5", OffsetDateTime.now(clock)));

        Map<String, Object> confirm = jdbcTemplate.queryForMap("""
                SELECT action, category, target_type, actor_account_id, host(ip) AS ip,
                       detail->>'action' AS detail_action, detail->>'reason' AS reason,
                       (detail->>'fallback_used')::boolean AS fallback_used, detail->>'route_version_id' AS version
                FROM audit_log WHERE target_type = 'run' AND target_id = 770001""");
        assertThat(confirm).containsEntry("action", "update").containsEntry("category", "data_access")
                .containsEntry("actor_account_id", 1L).containsEntry("ip", "203.0.113.5")
                .containsEntry("detail_action", "run.force_confirm").containsEntry("reason", "기사 무응답")
                .containsEntry("fallback_used", true).containsEntry("version", "55");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT action FROM audit_log WHERE target_type = 'student' AND target_id = 770002", String.class))
                .isEqualTo("delete");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("호출자 트랜잭션이 롤백되면 감사 행도 함께 사라진다")
    void 호출자_트랜잭션에_합류한다() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            recorder.record(AuditLog.forRunForceFinish(1L, 1L, "admin", 770_003L, "사유", 2L, "203.0.113.5",
                    OffsetDateTime.now(clock)));
            status.setRollbackOnly();
        });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE target_type = 'run' AND target_id = 770003", Long.class)).isZero();
    }
}
