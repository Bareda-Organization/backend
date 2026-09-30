package src.backend.student.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManagerFactory;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import testsupport.clock.SeedDateClockConfig;

/**
 * 학부모 {@code GET /students/{id}/runs} 의 SQL 수는 같은 학원·같은 날의 회차 수와 무관하다(R46 D #8) — 다른 학생의
 * 회차가 늘어도 이 학생의 호출이 회차 수만큼 질의하지 않는다. 시험 트랜잭션이 롤백해 심은 회차는 남지 않는다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Import(SeedDateClockConfig.class)
@Transactional
class StudentRunsSqlCountTest {

    private static final long ACADEMY_A = 1L;

    private static final long GUARDIAN_ACCOUNT = 5L;

    private static final long STUDENT_ON_RUN_3 = 2L;

    private static final int EXTRA_RUNS = 40;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName("같은 학원 같은 날 다른 회차가 40개 늘어도 학생 회차 목록의 SQL 수는 그대로다")
    void 학원_회차가_늘어도_SQL_수는_그대로다() throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        호출한다();
        long before = 호출하고_SQL_수를_센다(statistics);

        jdbc.update("""
                INSERT INTO run (academy_id, bus_id, schedule_id, service_date, direction, depart_time, confirm_at,
                                 status, origin_name, destination_name)
                SELECT academy_id, bus_id, schedule_id, service_date, direction,
                       depart_time + n * INTERVAL '1 minute', confirm_at + n * INTERVAL '1 minute',
                       'finished', origin_name, destination_name
                FROM run, generate_series(1, ?) n WHERE id = 3
                """, EXTRA_RUNS);
        long after = 호출하고_SQL_수를_센다(statistics);

        assertThat(before).as("기준 호출에서 SQL 이 실제로 세어져야 한다").isPositive();
        assertThat(after).as("회차 %d개 추가 뒤 SQL 수(전 %d)".formatted(EXTRA_RUNS, before)).isEqualTo(before);
    }

    private long 호출하고_SQL_수를_센다(Statistics statistics) throws Exception {
        long start = statistics.getPrepareStatementCount();
        호출한다();
        return statistics.getPrepareStatementCount() - start;
    }

    private void 호출한다() throws Exception {
        String token = "Bearer " + tokenProvider.createAccessToken(GUARDIAN_ACCOUNT, ACADEMY_A, Role.PARENT,
                AccountStatus.ACTIVE);
        mockMvc.perform(get("/api/v1/students/%d/runs".formatted(STUDENT_ON_RUN_3)).header("Authorization", token))
                .andExpect(status().isOk());
    }
}
