package src.backend.boarding.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 하원 회차 시작 시 명단 전원 일괄 승차(C-07 · BRD-03, 목표 11) — {@link AutoBoardingService} 를 직접
 * 호출한다. 이 워크트리에는 이 메서드를 부르는 T2 소유의 회차 시작 커맨드가 없어 컨트롤러 경유 시험이
 * 불가능하고, 그래서 컨트롤러 계층 없이 서비스 메서드를 직접 검증한다(T2/T3 경계, 브리프 참고).
 */
@SpringBootTest
@Transactional
class AutoBoardingServiceTest {

    @Autowired
    private AutoBoardingService autoBoardingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private RunRiderRepository runRiderRepository;

    @Autowired
    private ConfirmedRouteRepository confirmedRouteRepository;

    @Autowired
    private RouteVersionRepository routeVersionRepository;

    @Autowired
    private RunStopRepository runStopRepository;

    private BoardingCommandFixtures fixtures;

    private BoardingCommandFixtures fixtures() {
        if (fixtures == null) {
            fixtures = new BoardingCommandFixtures(academyRepository, busRepository, studentRepository,
                    guardianRepository, guardianStudentRepository, accountRepository, academyStaffRepository,
                    runRepository, stopRepository, runRiderRepository, confirmedRouteRepository,
                    routeVersionRepository, runStopRepository, jdbcTemplate, entityManager);
        }
        return fixtures;
    }

    @Test
    @DisplayName("목표11 — 하원 회차 시작 시 대기 중인 탑승자 전원이 자동 승차 처리된다")
    void 하원_시작_시_대기중인_탑승자_전원이_자동_승차된다() {
        OffsetDateTime now = OffsetDateTime.parse("2030-04-01T12:00:00+09:00");
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.500000", "127.000000");
        long waitingStudent1 = fixtures().student(academyId, "학생1");
        long waitingStudent2 = fixtures().student(academyId, "학생2");
        long alreadyNoShowStudent = fixtures().student(academyId, "학생3");
        long runId = fixtures().run(academyId, busId, now.minusMinutes(30), now.minusMinutes(60));
        long riderWaiting1 = fixtures().runRider(runId, waitingStudent1, stopId);
        long riderWaiting2 = fixtures().runRider(runId, waitingStudent2, stopId);
        long riderNoShow = fixtures().runRider(runId, alreadyNoShowStudent, stopId);
        // 이미 미승차로 빠진 탑승자는 하원 시작 시점에도 그대로 둔다(AutoBoardingService 자바독) — 그
        // 판단을 검증 가능한 상태로 만들기 위해 이력 없이 직접 상태만 no_show 로 앞당겨 둔다.
        jdbcTemplate.update("UPDATE run_rider SET status = 'no_show' WHERE id = ?", riderNoShow);
        entityManager.clear();

        int boardedCount = autoBoardingService.boardAllForDropOff(runId, now);

        assertThat(boardedCount).as("①응답 계산값 — 대기 중이던 2명만 승차 처리된다").isEqualTo(2);

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run_rider WHERE id = ?", String.class,
                riderWaiting1)).as("②저장값").isEqualTo("boarded");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run_rider WHERE id = ?", String.class,
                riderWaiting2)).as("②저장값").isEqualTo("boarded");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run_rider WHERE id = ?", String.class,
                riderNoShow)).as("②저장값 — 이미 미승차인 탑승자는 건드리지 않는다").isEqualTo("no_show");
    }
}
