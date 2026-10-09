package src.backend.run.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import testsupport.clock.FixedClock20300401Config;

/**
 * 승하차 알림이 나가는 길 전부 — R51 H1(하원 시작 자동 승차분의 승차 알림) · M-B2(자동 전이의 상태 이력
 * {@code actor_type=system}) · H2(기사가 도착을 누르지 않은 정차지도 다음 도착·운행 종료 때 강제 출발).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FixedClock20300401Config.class)
class RunBoardingNotificationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ManagerRepository managerRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private ConfirmedRouteRepository confirmedRouteRepository;

    @Autowired
    private RouteVersionRepository routeVersionRepository;

    @Autowired
    private RunStopRepository runStopRepository;

    @Autowired
    private RunRiderRepository runRiderRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

    @Autowired
    private ChangeRequestRepository changeRequestRepository;

    private DriverRunFixtures fixtures() {
        return new DriverRunFixtures(academyRepository, busRepository, stopRepository, studentRepository,
                accountRepository, managerRepository, assignmentRepository, runRepository, confirmedRouteRepository,
                routeVersionRepository, runStopRepository, runRiderRepository, academyStaffRepository,
                guardianRepository, guardianStudentRepository, changeRequestRepository);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    @Test
    @DisplayName("H1 — 하원 시작으로 자동 승차된 학생마다 보호자 전원에게 boarding 알림이 1건씩 적재된다")
    void 하원_시작_자동_승차분의_보호자_전원에게_승차_알림이_간다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long stopId = fixtures.stop(academyId, "37.560000", "126.970000");
        long runId = fixtures.confirmedRun(academyId, busId, Direction.FROM_ACADEMY, now(), now().minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long waitingTwoGuardians = fixtures.studentWithAccount(academyId, "학생A");
        long guardianA1 = fixtures.guardianOf(academyId, waitingTwoGuardians, "A엄마", now());
        long guardianA2 = fixtures.guardianOf(academyId, waitingTwoGuardians, "A아빠", now().plusSeconds(1));
        long waitingOneGuardian = fixtures.studentWithAccount(academyId, "학생B");
        long guardianB = fixtures.guardianOf(academyId, waitingOneGuardian, "B엄마", now());
        long absentStudent = fixtures.studentWithAccount(academyId, "학생C");
        fixtures.guardianOf(academyId, absentStudent, "C엄마", now());
        fixtures.rider(runId, waitingTwoGuardians, stopId, RiderStatus.WAITING, now());
        fixtures.rider(runId, waitingOneGuardian, stopId, RiderStatus.WAITING, now());
        fixtures.rider(runId, absentStudent, stopId, RiderStatus.ABSENT, now());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/start")
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk());
        entityManager.flush();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT recipient_account_id, student_id, recipient_role FROM notification_log "
                        + "WHERE type = 'boarding' AND dedup_key LIKE ?", "auto_boarded:" + runId + ":%");
        assertThat(rows).as("자동 승차 2명의 보호자 3명 — 결석 학생의 보호자·학생 본인·관계자에게는 없다").hasSize(3);
        assertThat(rows).extracting(row -> ((Number) row.get("recipient_account_id")).longValue())
                .containsExactlyInAnyOrder(guardianA1, guardianA2, guardianB);
        assertThat(rows).extracting(row -> row.get("recipient_role")).containsOnly("parent");
    }

    @Test
    @DisplayName("M-B2 — 하원 시작 자동 승차는 rider_status_history 에 actor_type=system(waiting→boarded)으로 남는다")
    void 하원_시작_자동_승차가_상태_이력에_system_으로_남는다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long stopId = fixtures.stop(academyId, "37.560000", "126.970000");
        long runId = fixtures.confirmedRun(academyId, busId, Direction.FROM_ACADEMY, now(), now().minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long studentId = fixtures.student(academyId, "학생");
        long riderId = fixtures.rider(runId, studentId, stopId, RiderStatus.WAITING, now());
        long absentRiderId = fixtures.rider(runId, fixtures.student(academyId, "결석"), stopId, RiderStatus.ABSENT, now());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/start")
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk());
        entityManager.flush();

        Map<String, Object> history = jdbcTemplate.queryForMap(
                "SELECT from_status, to_status, actor_type, changed_by, is_revert FROM rider_status_history "
                        + "WHERE run_rider_id = ?", riderId);
        assertThat(history).containsEntry("from_status", "waiting").containsEntry("to_status", "boarded")
                .containsEntry("actor_type", "system").containsEntry("changed_by", null)
                .containsEntry("is_revert", false);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM rider_status_history WHERE run_rider_id = ?",
                Integer.class, absentRiderId)).as("결석 학생은 전이가 없으니 이력도 없다").isZero();
    }

    @Test
    @DisplayName("H1 부수 — 하원에서 하차 처리 없이 승하차지를 출발하면 탑승 중인 학생에게 승차 알림이 나가지 않는다(하차한 학생은 하차 알림)")
    void 하원_정차지_출발은_탑승중인_학생에게_승차_알림을_내지_않는다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long stop1 = fixtures.stop(academyId, "37.560000", "126.970000");
        long stop2 = fixtures.stop(academyId, "37.561000", "126.971000");
        long runId = fixtures.confirmedRun(academyId, busId, Direction.FROM_ACADEMY, now(), now().minusMinutes(30));
        fixtures.startRun(runId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long versionId = fixtures.confirmedRouteWithVersion(runId, now());
        long runStop1 = fixtures.runStopForStop(versionId, stop1, 1, now());
        long runStop2 = fixtures.runStopForStop(versionId, stop2, 2, now());
        long stillOnBus = fixtures.studentWithAccount(academyId, "탑승중");
        fixtures.guardianOf(academyId, stillOnBus, "탑승중엄마", now());
        fixtures.rider(runId, stillOnBus, stop1, RiderStatus.BOARDED, now());
        long gotOff = fixtures.studentWithAccount(academyId, "하차");
        fixtures.guardianOf(academyId, gotOff, "하차엄마", now());
        fixtures.rider(runId, gotOff, stop1, RiderStatus.ALIGHTED, now());

        arrive(runId, runStop1, driverAccountId, academyId);
        arrive(runId, runStop2, driverAccountId, academyId);

        assertThat(출발_통지_행수(runId, "boarding")).as("하원 승차 알림은 시작 때 나갔다 — 하차지 출발에서 또 나가면 안 된다").isZero();
        assertThat(출발_통지_행수(runId, "alighting")).as("하차한 학생은 하차 알림").isEqualTo(1);
    }

    @Test
    @DisplayName("H1 부수 대조 — 등원에서는 승하차지를 출발할 때 탑승한 학생의 승차 알림이 나간다")
    void 등원_정차지_출발은_탑승한_학생에게_승차_알림을_낸다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long stop1 = fixtures.stop(academyId, "37.560000", "126.970000");
        long stop2 = fixtures.stop(academyId, "37.561000", "126.971000");
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        fixtures.startRun(runId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long versionId = fixtures.confirmedRouteWithVersion(runId, now());
        long runStop1 = fixtures.runStopForStop(versionId, stop1, 1, now());
        long runStop2 = fixtures.runStopForStop(versionId, stop2, 2, now());
        fixtures.runStopForDestination(versionId, 3);
        long student = fixtures.studentWithAccount(academyId, "탑승");
        fixtures.guardianOf(academyId, student, "탑승엄마", now());
        fixtures.rider(runId, student, stop1, RiderStatus.BOARDED, now());

        arrive(runId, runStop1, driverAccountId, academyId);
        arrive(runId, runStop2, driverAccountId, academyId);

        assertThat(출발_통지_행수(runId, "boarding")).isEqualTo(1);
    }

    private void arrive(long runId, long runStopId, long driverAccountId, long academyId) throws Exception {
        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + runStopId + "/arrive")
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk());
        entityManager.flush();
    }

    private long 출발_통지_행수(long runId, String type) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log nl JOIN run_rider rr ON rr.student_id = nl.student_id "
                        + "WHERE rr.run_id = ? AND nl.type = ? AND nl.recipient_role = 'parent' "
                        + "AND nl.dedup_key LIKE 'stop_departed:%'",
                Integer.class, runId, type);
        return count == null ? 0 : count;
    }

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }
}
