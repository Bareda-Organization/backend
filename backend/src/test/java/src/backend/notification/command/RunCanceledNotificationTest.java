package src.backend.notification.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
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
import src.backend.run.controller.DriverRunFixtures;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * R56 K3(Ruling 894 ⑤) — 확정된({@code confirmed}) 회차를 관계자가 취소하면 배치된 기사·동승자에게
 * {@code run_canceled} 알림이 간다. 확정 전({@code idle}) 취소는 매니저가 아직 회차를 모르므로 보내지 않는다.
 *
 * <p>적재는 취소와 같은 트랜잭션 안이라 {@code @Transactional} 시험에서 같은 연결로 행이 보인다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RunCanceledNotificationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    @Test
    @DisplayName("R56 K3 — confirmed 회차를 취소하면 배치된 기사·동승자에게 run_canceled 가 각 1건 적재된다")
    void 확정_회차_취소는_기사와_동승자에게_통지한다() throws Exception {
        DriverRunFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        OffsetDateTime departTime = OffsetDateTime.now().plusHours(2);
        long runId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        long driverAccountId = fx.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사1", OffsetDateTime.now());
        long escortAccountId = fx.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자1", OffsetDateTime.now());
        long staffAccountId = fx.staffAccount(academyId, "관계자1");

        mockMvc.perform(delete("/api/v1/staff/runs/" + runId).header("Authorization", 토큰(staffAccountId, academyId)))
                .andExpect(status().isNoContent());

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT recipient_account_id, recipient_role, run_id FROM notification_log "
                        + "WHERE type = 'run_canceled' AND run_id = ? ORDER BY recipient_role",
                runId);
        assertThat(rows).as("기사·동승자 각 1건").hasSize(2);
        assertThat(rows).extracting(row -> row.get("recipient_account_id"))
                .containsExactlyInAnyOrder(driverAccountId, escortAccountId);
        assertThat(rows).extracting(row -> row.get("recipient_role")).containsExactly("driver", "escort");
    }

    @Test
    @DisplayName("R56 K3 — idle(확정 전) 회차를 취소하면 알림이 한 건도 적재되지 않는다")
    void 확정_전_회차_취소는_통지하지_않는다() throws Exception {
        DriverRunFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        OffsetDateTime departTime = OffsetDateTime.now().plusHours(2);
        long runId = fx.idleRun(academyId, busId, Direction.TO_ACADEMY, departTime);
        fx.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사1", OffsetDateTime.now());
        long staffAccountId = fx.staffAccount(academyId, "관계자1");

        mockMvc.perform(delete("/api/v1/staff/runs/" + runId).header("Authorization", 토큰(staffAccountId, academyId)))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE type = 'run_canceled' AND run_id = ?", Integer.class,
                runId)).isZero();
    }

    @Test
    @DisplayName("R56 K3 — 이미 취소된 확정 회차를 다시 취소해도(멱등) 알림이 늘지 않는다")
    void 두_번_취소해도_통지는_한_번이다() throws Exception {
        DriverRunFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        OffsetDateTime departTime = OffsetDateTime.now().plusHours(2);
        long runId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        fx.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사1", OffsetDateTime.now());
        long staffAccountId = fx.staffAccount(academyId, "관계자1");

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(delete("/api/v1/staff/runs/" + runId)
                            .header("Authorization", 토큰(staffAccountId, academyId)))
                    .andExpect(status().isNoContent());
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE type = 'run_canceled' AND run_id = ?", Integer.class,
                runId)).isEqualTo(1);
    }

    private String 토큰(long accountId, long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, Role.STAFF, AccountStatus.ACTIVE);
    }
}
