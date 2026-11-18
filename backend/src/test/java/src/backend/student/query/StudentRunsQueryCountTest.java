package src.backend.student.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.persistence.EntityManagerFactory;

import src.backend.boarding.entity.RiderStatus;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.run.controller.DriverRunFixtures;
import src.backend.student.repository.StudentRepository;

/**
 * 학생 당일 회차 목록(API_SPEC §3.5)의 SQL 수는 학생이 탄 회차 수와 무관하다 — 차량 · 탑승 의사 · 탑승자 · 승하차지를 회차마다 따로 읽지 않고
 * 한꺼번에 읽는다. 회차가 하나일 때와 넷일 때 Hibernate 가 준비한 문장 수가 같아야 한다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class StudentRunsQueryCountTest {

    private static final String DAY = "2030-04-01";

    @Autowired private ApplicationContext applicationContext;
    @Autowired private StudentRunsQueryService studentRunsQueryService;
    @Autowired private StudentRepository studentRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> academyIds = new ArrayList<>();

    @AfterEach
    void 뒷정리한다() {
        for (Long academyId : academyIds) {
            jdbcTemplate.update("DELETE FROM run_rider WHERE run_id IN (SELECT id FROM run WHERE academy_id = ?)", academyId);
            jdbcTemplate.update("DELETE FROM run WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM student WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM account WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM stop WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM bus WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM academy WHERE id = ?", academyId);
        }
        academyIds.clear();
    }

    @Test
    void 학생이_탄_회차가_하나에서_넷으로_늘어도_SQL_수는_그대로다() {
        DriverRunFixtures fx = DriverRunFixtures.of(applicationContext);
        long academyId = fx.academy();
        academyIds.add(academyId);
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.560000", "126.970000");
        long studentId = fx.studentWithAccount(academyId, "탑승학생");
        AuthUser requester = new AuthUser(studentRepository.findById(studentId).orElseThrow().getAccountId(), academyId,
                Role.STUDENT, AccountStatus.ACTIVE, false);
        OffsetDateTime first = OffsetDateTime.parse("2030-04-01T08:00:00+09:00");
        탄_회차를_더한다(fx, academyId, busId, stopId, studentId, first);

        long one = 호출하고_SQL_수를_센다(requester, studentId);
        for (int i = 1; i < 4; i++) {
            탄_회차를_더한다(fx, academyId, busId, stopId, studentId, first.plusHours(i));
        }
        long four = 호출하고_SQL_수를_센다(requester, studentId);

        assertThat(one).as("기준 호출에서 SQL 이 실제로 세어져야 한다").isPositive();
        assertThat(four).as("회차 4개일 때 SQL 수(회차 1개일 때 %d)", one).isEqualTo(one);
    }

    private void 탄_회차를_더한다(DriverRunFixtures fx, long academyId, long busId, long stopId, long studentId,
            OffsetDateTime departTime) {
        long runId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        fx.rider(runId, studentId, stopId, RiderStatus.WAITING, departTime);
    }

    private long 호출하고_SQL_수를_센다(AuthUser requester, long studentId) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        studentRunsQueryService.runs(requester, studentId, DAY);
        long before = statistics.getPrepareStatementCount();
        assertThat(studentRunsQueryService.runs(requester, studentId, DAY).items()).isNotEmpty();
        return statistics.getPrepareStatementCount() - before;
    }
}
