package src.backend.boarding.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.dto.ManagerRosterResponse;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.controller.DriverRunFixtures;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import testsupport.db.ConnectionCountingConfig;

/**
 * DA-6(Ruling 899) — 운행 명단 조회 1회는 풀에서 연결을 <b>한 번</b> 얻는다(읽기 트랜잭션 하나). 감사 기록({@code REQUIRES_NEW})은
 * 그 읽기가 끝나 연결을 돌려준 <b>뒤</b>에 두 번째로 얻는다 — 한 요청이 연결 둘을 동시에 쥐면 동시 요청이 풀 크기에 닿을 때 서로의 두 번째 연결을
 * 기다려 풀리지 않는다(R46 감사 #1).
 */
@SpringBootTest
@Import(ConnectionCountingConfig.class)
class RosterQueryConnectionTest {

    @Autowired private RosterQueryService rosterQueryService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AcademyRepository academyRepository;
    @Autowired private BusRepository busRepository;
    @Autowired private StopRepository stopRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ManagerRepository managerRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private ConfirmedRouteRepository confirmedRouteRepository;
    @Autowired private RouteVersionRepository routeVersionRepository;
    @Autowired private RunStopRepository runStopRepository;
    @Autowired private RunRiderRepository runRiderRepository;
    @Autowired private AcademyStaffRepository academyStaffRepository;
    @Autowired private GuardianRepository guardianRepository;
    @Autowired private GuardianStudentRepository guardianStudentRepository;
    @Autowired private ChangeRequestRepository changeRequestRepository;

    private final List<Long> academyIds = new ArrayList<>();

    @AfterEach
    void 뒷정리한다() {
        for (Long academyId : academyIds) {
            jdbcTemplate.update("DELETE FROM audit_log WHERE actor_account_id IN (SELECT id FROM account WHERE academy_id = ?)", academyId);
            jdbcTemplate.update("DELETE FROM run_rider WHERE run_id IN (SELECT id FROM run WHERE academy_id = ?)", academyId);
            jdbcTemplate.update("DELETE FROM run WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM student WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM manager WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM account WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM stop WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM bus WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM academy WHERE id = ?", academyId);
        }
        academyIds.clear();
    }

    @Test
    @DisplayName("DA-6 — 매니저 앱 명단 조회는 연결을 읽기에 한 번, 감사 기록에 한 번 얻고 동시에 둘을 쥐지 않는다")
    void 명단_조회는_읽기_연결_하나_감사는_그_뒤() {
        DriverRunFixtures fx = new DriverRunFixtures(academyRepository, busRepository, stopRepository,
                studentRepository, accountRepository, managerRepository, assignmentRepository, runRepository,
                confirmedRouteRepository, routeVersionRepository, runStopRepository, runRiderRepository,
                academyStaffRepository, guardianRepository, guardianStudentRepository, changeRequestRepository);
        long academyId = fx.academy();
        academyIds.add(academyId);
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.560000", "126.970000");
        OffsetDateTime now = OffsetDateTime.now();
        long runId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now, now.minusMinutes(30));
        fx.startRun(runId, now);
        long versionId = fx.confirmedRouteWithVersion(runId, now);
        fx.runStopForStop(versionId, stopId, 1, now);
        fx.runStopForDestination(versionId, 2);
        long studentId = fx.student(academyId, "탑승학생");
        fx.rider(runId, studentId, stopId, RiderStatus.WAITING, now);
        long driverAccountId = fx.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now);
        AuthUser driver = new AuthUser(driverAccountId, academyId, Role.DRIVER, AccountStatus.ACTIVE, false);

        ConnectionCountingConfig.reset();
        ManagerRosterResponse roster = rosterQueryService.managerRoster(driver, runId);

        assertThat(roster.stops()).isNotEmpty();
        assertThat(ConnectionCountingConfig.acquired()).as("읽기 1 + 감사 1").isEqualTo(2);
        assertThat(ConnectionCountingConfig.maxOpen()).as("동시에 쥔 연결").isEqualTo(1);
    }
}
