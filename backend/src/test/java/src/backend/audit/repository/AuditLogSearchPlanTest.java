package src.backend.audit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 필터 없는 감사·접속 이력 첫 화면(§6.13)이 {@code category} + {@code occurred_at} 순서 인덱스를 탄다(BR-089).
 *
 * <p>{@code audit_log} 는 무기한 보존(Ruling 243)이라 이 조회가 전 표를 정렬하면 운영 기간에 비례해 느려진다.
 * 시험 DB 는 작아서 플래너가 순차 스캔을 고르므로 그 선택지를 끄고 쓸 수 있는 인덱스가 있는지를 본다.
 */
@SpringBootTest
@Transactional
class AuditLogSearchPlanTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 필터_없는_첫_화면_조회가_category_occurred_at_인덱스를_쓴다() {
        jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
        jdbcTemplate.execute("SET LOCAL enable_sort = off");

        List<String> plan = jdbcTemplate.queryForList("""
                EXPLAIN SELECT * FROM audit_log
                WHERE category = 'data_access'
                  AND occurred_at BETWEEN '0001-01-01T00:00:00Z' AND '9999-12-31T23:59:59Z'
                ORDER BY occurred_at DESC LIMIT 20
                """, String.class);

        assertThat(String.join("\n", plan)).contains("ix_audit_log_category_occurred");
    }
}
