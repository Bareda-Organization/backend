package src.backend.student.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.persistence.EntityManagerFactory;

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.security.AuthUser;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.Phase9RosterFixtures;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.command.RunConfirmationService;
import src.backend.run.repository.RunRepository;
import src.backend.student.dto.WithdrawalPreviewResponse;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;
import testsupport.clock.FixedClock20300401Config;

/**
 * DA-7(Ruling 899) — 퇴원 미리보기의 SQL 수는 확정 전(idle) 회차가 4개든 40개든 같다. 회차마다 예정 명단 계산(8개 안팎의 조회)을 따로 돌리면
 * 학원의 회차가 늘수록 관계자 요청 하나가 연결을 오래 쥔다. 시계는 2030-04-01(월) 로 고정돼 내일(화)이 대상이다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import(FixedClock20300401Config.class)
class WithdrawalPreviewQueryCountTest {

    private static final LocalDate TOMORROW = LocalDate.parse("2030-04-02");

    @Autowired private WithdrawalPreviewQueryService previewService;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AcademyRepository academyRepository;
    @Autowired private BusRepository busRepository;
    @Autowired private RouteRepository routeRepository;
    @Autowired private RouteStopRepository routeStopRepository;
    @Autowired private StopRepository stopRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private WeeklyAddressRepository weeklyAddressRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private ManagerRepository managerRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private GuardianRepository guardianRepository;
    @Autowired private GuardianStudentRepository guardianStudentRepository;
    @Autowired private RunConfirmationService confirmationService;

    private long academyId;

    @AfterEach
    void 뒷정리한다() {
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
    @DisplayName("DA-7 — 확정 전 회차가 4개일 때와 40개일 때 SQL 수가 같고 탑승 회차는 모두 실린다")
    void 확정_전_회차_수와_무관하게_SQL_수가_같다() {
        Phase9RosterFixtures fx = new Phase9RosterFixtures(new RunConfirmationFixtures(academyRepository,
                busRepository, routeRepository, routeStopRepository, stopRepository, studentRepository,
                weeklyAddressRepository, runRepository), managerRepository, accountRepository, assignmentRepository,
                guardianRepository, guardianStudentRepository, confirmationService);
        academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.TUE, Direction.TO_ACADEMY, stopId);
        long studentId = fx.student(academyId, "퇴원예정");
        fx.verifiedAddress(studentId, stopId, Weekday.TUE, Direction.TO_ACADEMY, "37.500000", "127.000000");
        AuthUser staff = new AuthUser(1L, academyId, Role.STAFF, AccountStatus.ACTIVE, false);

        for (int i = 0; i < 4; i++) {
            idleRun(fx, busId, i);
        }
        long withFour = 쿼리_수(staff, studentId, 4);
        for (int i = 4; i < 40; i++) {
            idleRun(fx, busId, i);
        }
        long withForty = 쿼리_수(staff, studentId, 40);

        assertThat(withForty).as("4개 %d건 · 40개 %d건", withFour, withForty).isEqualTo(withFour);
    }

    private void idleRun(Phase9RosterFixtures fx, long busId, int index) {
        OffsetDateTime departTime = OffsetDateTime.of(TOMORROW, LocalTime.of(6 + index / 6, (index % 6) * 10), ZoneOffset.ofHours(9));
        fx.idleRun(academyId, busId, TOMORROW, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
    }

    private long 쿼리_수(AuthUser staff, long studentId, int expectedRuns) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long before = statistics.getPrepareStatementCount();
        WithdrawalPreviewResponse response = previewService.preview(staff, studentId);
        long count = statistics.getPrepareStatementCount() - before;
        assertThat(response.tomorrowRuns()).as("탑승 회차").hasSize(expectedRuns);
        return count;
    }
}
