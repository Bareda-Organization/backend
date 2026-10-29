package src.backend.exception.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.auth.TestTokens.토큰;

import java.time.OffsetDateTime;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.boarding.entity.RunRider;
import src.backend.exception.controller.ExceptionReportFixtures;
import src.backend.exception.entity.ExceptionReportType;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.student.entity.Student;

/**
 * 예외 보고 목록(§5.20)이 읽는 탑승자·학생 행 수 — 목록에 나온 보호자 부재 보고의 탑승자만 읽어야 한다. 회차 명단 전체를 읽으면 행 수가 명단 크기에 비례한다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Transactional
class ExceptionReportRiderLoadTest {

    private static final int ROSTER_SIZE = 25;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private ApplicationContext context;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("보호자 부재 보고 1건의 목록은 25명 명단에서 그 탑승자·학생 1명만 읽는다")
    void 목록은_보고에_나온_탑승자만_읽는다() throws Exception {
        ExceptionReportFixtures fixtures = ExceptionReportFixtures.of(context);
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime base = OffsetDateTime.parse("2030-04-01T09:00:00+09:00");
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, base, base.minusMinutes(30));
        long reporter = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", base);
        long staffAccountId = fixtures.staffAccount(academyId, "관계자");
        long stopId = fixtures.stop(academyId, "37.500000", "127.000000");
        long reportedRider = 0;
        for (int i = 0; i < ROSTER_SIZE; i++) {
            long riderId = fixtures.rider(runId, fixtures.student(academyId, "학생" + i), stopId);
            reportedRider = i == 0 ? riderId : reportedRider;
        }
        fixtures.exceptionReport(academyId, runId, ExceptionReportType.GUARDIAN_ABSENT, "보호자 없음", reporter, base,
                reportedRider);
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        mockMvc.perform(get("/api/v1/staff/reports").header("Authorization",
                토큰(tokenProvider, staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].student_name").value("학생0"));

        assertThat(statistics.getEntityStatistics(RunRider.class.getName()).getLoadCount())
                .as("명단 25명 중 보고에 나온 탑승자만 읽어야 한다").isEqualTo(1);
        assertThat(statistics.getEntityStatistics(Student.class.getName()).getLoadCount()).isEqualTo(1);
    }
}
