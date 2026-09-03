package src.backend.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.audit.entity.AuditAction;
import src.backend.audit.entity.AuditCategory;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.Phase9RosterFixtures;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.command.RunConfirmationService;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * Phase 14 T1 목표 1 음성 대조용 — {@link src.backend.boarding.query.RosterQueryService} 의 두 호출
 * ({@code managerRoster}·{@code staffRoster})이 각각 정확히 감사 로그 1건을 남기는지 실제 DB 로 확인한다.
 *
 * <p>두 메서드가 같은 {@code target_type}("run_roster")을 쓰지만 서로 다른 {@code run_id}(테스트마다
 * 새로 만든 회차)로 격리되므로, 한쪽 호출의 감사 코드만 없어져도(negative control #1·#3) 그 메서드를
 * 검사하는 테스트만 실패하고 다른 쪽은 그대로 통과한다 — {@code ManagerRosterResponse} 감사만 빠지는
 * 결함(negative control #3)을 이 두 테스트의 조합으로 가른다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RosterAuditIntegrationTest {

    private static final String SERVICE_DATE = "2031-09-01";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private RouteStopRepository routeStopRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private WeeklyAddressRepository weeklyAddressRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private ManagerRepository managerRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

    @Autowired
    private RunConfirmationService confirmationService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private Phase9RosterFixtures fixtures() {
        RunConfirmationFixtures base = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        return new Phase9RosterFixtures(base, managerRepository, accountRepository, assignmentRepository,
                guardianRepository, guardianStudentRepository, confirmationService);
    }

    @Test
    void 매니저_명단_조회는_감사_로그_1건을_남기고_student_ids_와_L3_필드_목록을_담는다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.MON, Direction.TO_ACADEMY, stopId);
        long studentId = fx.student(academyId, "학생1");
        fx.verifiedAddress(studentId, stopId, Weekday.MON, Direction.TO_ACADEMY, "37.500000", "127.000000");
        OffsetDateTime departTime = OffsetDateTime.parse("2031-09-01T08:00:00+09:00");
        long runId = fx.confirmedRun(academyId, busId, LocalDate.parse(SERVICE_DATE), Direction.TO_ACADEMY,
                departTime, departTime.minusMinutes(30));
        Phase9RosterFixtures.ManagerAccount manager = fx.manager(academyId, ManagerRole.DRIVER, "기사");
        fx.assign(runId, manager.managerId(), ManagerRole.DRIVER);

        mockMvc.perform(get("/api/v1/runs/" + runId + "/roster").header("Authorization",
                "Bearer " + tokenProvider.createAccessToken(manager.accountId(), academyId, Role.DRIVER,
                        AccountStatus.ACTIVE)))
                .andExpect(status().isOk());

        List<AuditLog> rows = auditLogRepository.findAll().stream()
                .filter(log -> log.getCategory() == AuditCategory.DATA_ACCESS
                        && "run_roster".equals(log.getTargetType()) && log.getTargetId().equals(runId))
                .toList();
        assertThat(rows)
                .as("run_id·student_id 는 서로 다른 시퀀스라 숫자가 우연히 겹칠 수 있어 target_type 도 함께 좁힌다")
                .hasSize(1);
        AuditLog row = rows.get(0);
        assertThat(row.getAction()).isEqualTo(AuditAction.READ);
        assertThat(row.getActorAccountId()).isEqualTo(manager.accountId());
        assertThat(row.getAcademyId()).isEqualTo(academyId);
        @SuppressWarnings("unchecked")
        List<String> studentIds = (List<String>) row.getDetail().get("student_ids");
        assertThat(studentIds).containsExactly(String.valueOf(studentId));
        @SuppressWarnings("unchecked")
        List<String> fields = (List<String>) row.getDetail().get("fields");
        assertThat(fields).containsExactlyInAnyOrder("photo_url", "note", "address");
    }

    @Test
    void 관계자_명단_조회는_감사_로그_1건을_남기고_보호자_연락처_원본을_L3_필드로_담는다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.MON, Direction.TO_ACADEMY, stopId);
        long studentId = fx.student(academyId, "학생1");
        fx.verifiedAddress(studentId, stopId, Weekday.MON, Direction.TO_ACADEMY, "37.500000", "127.000000");
        OffsetDateTime departTime = OffsetDateTime.parse("2031-09-01T08:00:00+09:00");
        long runId = fx.confirmedRun(academyId, busId, LocalDate.parse(SERVICE_DATE), Direction.TO_ACADEMY,
                departTime, departTime.minusMinutes(30));
        Account staffAccount = accountRepository.save(Account.forSignup(academyId, "p14t1staff" + System.nanoTime(),
                "{noop}password", "관계자", "010-0000-0000", null, Role.STAFF));

        mockMvc.perform(get("/api/v1/staff/runs/" + runId + "/roster").header("Authorization",
                "Bearer " + tokenProvider.createAccessToken(staffAccount.getId(), academyId, Role.STAFF,
                        AccountStatus.ACTIVE)))
                .andExpect(status().isOk());

        List<AuditLog> rows = auditLogRepository.findAll().stream()
                .filter(log -> log.getCategory() == AuditCategory.DATA_ACCESS
                        && "run_roster".equals(log.getTargetType()) && log.getTargetId().equals(runId))
                .toList();
        assertThat(rows)
                .as("run_id·student_id 는 서로 다른 시퀀스라 숫자가 우연히 겹칠 수 있어 target_type 도 함께 좁힌다")
                .hasSize(1);
        AuditLog row = rows.get(0);
        assertThat(row.getActorAccountId()).isEqualTo(staffAccount.getId());
        @SuppressWarnings("unchecked")
        List<String> studentIds = (List<String>) row.getDetail().get("student_ids");
        assertThat(studentIds).containsExactly(String.valueOf(studentId));
        @SuppressWarnings("unchecked")
        List<String> fields = (List<String>) row.getDetail().get("fields");
        assertThat(fields).containsExactlyInAnyOrder("guardian_phone", "note");
    }
}
