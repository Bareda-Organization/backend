package src.backend.student.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.persistence.EntityManagerFactory;

import src.backend.boarding.entity.RunRider;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.security.AuthUser;
import src.backend.run.command.RunRosterFixtures;
import src.backend.student.dto.WithdrawalPreviewResponse;
import testsupport.clock.FixedClock20300401Config;
import testsupport.db.ConnectionCountingConfig;

/**
 * 퇴원 미리보기가 확정된 회차에서 읽는 승차 명단 행 수 — 학원의 학생 수가 아니라 그 학생 몫만큼이다.
 * 회차 명단 전체를 읽어 한 학생을 골라내면 학생이 많은 학원일수록 요청 하나가 읽는 행이 늘어난다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import({FixedClock20300401Config.class, ConnectionCountingConfig.class})
class WithdrawalPreviewRiderLoadTest {

    private static final LocalDate TOMORROW = LocalDate.parse("2030-04-02");

    @Autowired
    private WithdrawalPreviewQueryService previewService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext context;

    private long academyId;

    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM run_rider WHERE run_id IN (SELECT id FROM run WHERE academy_id = ?)", academyId);
        jdbcTemplate.update("DELETE FROM run WHERE academy_id = ?", academyId);
        jdbcTemplate.update("DELETE FROM weekly_address WHERE student_id IN (SELECT id FROM student WHERE academy_id = ?)", academyId);
        jdbcTemplate.update("DELETE FROM route_stop WHERE route_id IN (SELECT id FROM route WHERE academy_id = ?)", academyId);
        jdbcTemplate.update("DELETE FROM route WHERE academy_id = ?", academyId);
        jdbcTemplate.update("DELETE FROM student WHERE academy_id = ?", academyId);
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id = ?", academyId);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id = ?", academyId);
        jdbcTemplate.update("DELETE FROM academy WHERE id = ?", academyId);
    }

    @Test
    void 학원의_학생_수와_무관하게_그_학생의_승차_행만_읽는다() {
        RunRosterFixtures fx = RunRosterFixtures.of(context);
        academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.TUE, Direction.TO_ACADEMY, stopId);
        long target = 0;
        for (int i = 0; i < 25; i++) {
            long studentId = fx.student(academyId, "명단학생" + i);
            fx.verifiedAddress(studentId, stopId, Weekday.TUE, Direction.TO_ACADEMY, "37.500000", "127.000000");
            target = i == 0 ? studentId : target;
        }
        OffsetDateTime departTime = OffsetDateTime.of(TOMORROW, LocalTime.of(7, 0), ZoneOffset.ofHours(9));
        fx.confirmedRun(academyId, busId, TOMORROW, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        AuthUser staff = new AuthUser(1L, academyId, Role.STAFF, AccountStatus.ACTIVE, false);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        ConnectionCountingConfig.reset();

        WithdrawalPreviewResponse response = previewService.preview(staff, target);

        assertThat(response.tomorrowRuns()).as("확정된 회차에 이 학생이 탄다").hasSize(1);
        assertThat(statistics.getEntityStatistics(RunRider.class.getName()).getLoadCount())
                .as("명단 25행 중 이 학생의 1행만 읽어야 한다").isLessThanOrEqualTo(1);
        assertThat(ConnectionCountingConfig.preparedStatements())
                .filteredOn(sql -> sql.contains("from run_rider"))
                .as("승차 명단을 읽는 SQL 은 학생으로 좁혀야 한다 — 엔티티로 읽지 않아도 회차 명단 전체를 가져오면 행 수는 같다")
                .isNotEmpty().allSatisfy(sql -> assertThat(sql).contains("student_id"));
    }
}
