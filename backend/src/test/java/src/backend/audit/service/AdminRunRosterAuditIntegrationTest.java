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
import src.backend.account.repository.AccountRepository;
import src.backend.audit.entity.AuditAction;
import src.backend.audit.entity.AuditCategory;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
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
 * Phase 14 수정 라운드 1 — {@link src.backend.monitoring.query.AdminRunRosterQueryService#roster}
 * 의 감사 호출에 전담 단언을 둔다({@code review-p14-r1.md} 🔴 1건 — R1 이 no-op 변형을 심어 살아남음을
 * 확인한 자리). {@link RosterAuditIntegrationTest} 와 같은 {@link Phase9RosterFixtures} 로 확정
 * 회차·명단을 구성하되, 호출 경로는 메인 관리자 콘솔({@code /admin/runs/{runId}/roster})이다 —
 * {@code AdminRunRosterQueryService} 는 요청자를 파라미터가 아니라 {@code SecurityContextHolder}
 * 에서 읽으므로, 이 테스트는 반드시 실제 HTTP 인증 경로를 태워야 그 경로까지 함께 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminRunRosterAuditIntegrationTest {

    private static final String SERVICE_DATE = "2031-09-01";
    private static final long SYSTEM_ADMIN_ACCOUNT_ID = 1L;

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
    void 관리자_회차_명단_조회는_감사_로그_1건을_남기고_run_roster_필드_목록을_담는다() throws Exception {
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

        mockMvc.perform(get("/api/v1/admin/runs/" + runId + "/roster").header("Authorization", 메인관리자_토큰()))
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
        assertThat(row.getAcademyId()).isEqualTo(academyId);
        @SuppressWarnings("unchecked")
        List<String> studentIds = (List<String>) row.getDetail().get("student_ids");
        assertThat(studentIds).containsExactly(String.valueOf(studentId));
        @SuppressWarnings("unchecked")
        List<String> fields = (List<String>) row.getDetail().get("fields");
        assertThat(fields).containsExactlyInAnyOrder("photo_url", "guardian_phone");
    }

    private String 메인관리자_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(SYSTEM_ADMIN_ACCOUNT_ID, null, Role.SYSTEM_ADMIN,
                AccountStatus.ACTIVE);
    }
}
