package src.backend.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 학생 목록의 L3 감사가 <b>실린 학생마다 1행</b>(Ruling 333)이되 <b>트랜잭션은 한 번</b>인지(BR-213).
 *
 * <p>행 수는 Ruling 333 이 요구하지만 행마다 새 트랜잭션·계정 조회를 여는 것은 요구가 아니다 — 페이지 100건이면
 * 트랜잭션 100개가 되고, 바깥 읽기 트랜잭션이 커넥션을 쥔 채 그 수만큼 두 번째 커넥션을 요구한다.
 * 감사 행을 시험이 남기므로 클래스는 롤백되지 않고, 남긴 행은 직접 지운다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
class StudentListAuditBatchTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void 목록은_보호자_연락처가_실린_학생마다_감사_1행을_남기되_트랜잭션은_감사_한_번이다() throws Exception {
        long lastAuditId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM audit_log", Long.class);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        try {
            statistics.clear();
            String body = mockMvc.perform(get("/api/v1/staff/students?size=100")
                            .header("Authorization", "Bearer " + tokenProvider.createAccessToken(
                                    1L, Long.valueOf(SeedFixtures.ACADEMY_A_ID), Role.STAFF, AccountStatus.ACTIVE)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            long transactions = statistics.getTransactionCount();

            List<Object> withPhone = JsonPath.read(body, "$.data.items[?(@.guardian_phone != null)].student_id");
            Long rows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM audit_log WHERE id > ? AND action = 'read' AND target_type = 'student'",
                    Long.class, lastAuditId);

            assertThat(withPhone.size()).as("시드에 보호자 연락처가 실린 학생이 여럿이어야 시험이 성립한다").isGreaterThan(1);
            assertThat(rows).as("Ruling 333 — 실린 학생마다 read 1행").isEqualTo(withPhone.size());
            assertThat(transactions).as("바깥 읽기 트랜잭션 1 + 감사 트랜잭션 1 — 학생마다 열지 않는다")
                    .isLessThanOrEqualTo(2);
        } finally {
            jdbcTemplate.update("DELETE FROM audit_log WHERE id > ?", lastAuditId);
        }
    }
}
