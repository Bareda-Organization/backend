package src.backend.exception.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.exception.controller.EmergencyFixtures;
import src.backend.exception.controller.ExceptionReportFixtures;
import src.backend.exception.entity.EmergencyAlert;
import src.backend.exception.entity.EmergencyType;
import src.backend.exception.entity.ExceptionReportType;
import src.backend.exception.repository.EmergencyAlertRepository;
import src.backend.exception.repository.ExceptionReportRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.request.PageParams;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 페이징 파라미터가 없는 관계자 목록 3종(§5.20 예외 보고 · §5.16 비상 알림 · §6.11 메인 관리자 비상 알림)의
 * 응답 행 수 상한과 보고 목록의 쿼리 수(BR-228) — 두 테이블({@code exception_report} · {@code emergency_alert})은
 * 무기한 보존이라 상한이 없으면 호출 한 번이 누적 전량을 읽고, 보고 목록은 보고마다 쿼리 3~5개가 붙는다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Transactional
class StaffListBoundTest {

    private static final int OVER_LIMIT = PageParams.UNPAGED_LIST_MAX + 1;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

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
    private RunRiderRepository runRiderRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private ExceptionReportRepository exceptionReportRepository;

    @Autowired
    private EmergencyAlertRepository emergencyAlertRepository;

    private ExceptionReportFixtures reportFixtures() {
        return new ExceptionReportFixtures(academyRepository, busRepository, stopRepository, studentRepository,
                accountRepository, managerRepository, assignmentRepository, runRepository, runRiderRepository,
                academyStaffRepository, exceptionReportRepository);
    }

    private EmergencyFixtures emergencyFixtures() {
        return new EmergencyFixtures(academyRepository, busRepository, accountRepository, managerRepository,
                assignmentRepository, runRepository, academyStaffRepository);
    }

    @Test
    void 예외_보고_목록은_최근_상한_건만_돌려준다() throws Exception {
        ExceptionReportFixtures fixtures = reportFixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime base = OffsetDateTime.parse("2030-04-01T09:00:00+09:00");
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, base, base.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", base);
        long staffAccountId = fixtures.staffAccount(academyId, "관계자");
        for (int i = 0; i < OVER_LIMIT; i++) {
            fixtures.exceptionReport(academyId, runId, ExceptionReportType.ETC, "보고" + i, driverAccountId,
                    base.plusMinutes(i), null);
        }

        mockMvc.perform(get("/api/v1/staff/reports").header("Authorization",
                토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(PageParams.UNPAGED_LIST_MAX))
                .andExpect(jsonPath("$.data.items[0].memo").value("보고" + (OVER_LIMIT - 1)));
    }

    @Test
    void 예외_보고_목록의_쿼리_수가_보고_수에_비례하지_않는다() throws Exception {
        ExceptionReportFixtures fixtures = reportFixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime base = OffsetDateTime.parse("2030-04-01T09:00:00+09:00");
        long staffAccountId = fixtures.staffAccount(academyId, "관계자");
        보고를_심는다(fixtures, academyId, busId, base, 1);
        long oneReport = 목록_쿼리_수("/api/v1/staff/reports", 토큰(staffAccountId, academyId, Role.STAFF));

        보고를_심는다(fixtures, academyId, busId, base.plusHours(1), 3);

        assertThat(목록_쿼리_수("/api/v1/staff/reports", 토큰(staffAccountId, academyId, Role.STAFF)))
                .isEqualTo(oneReport);
    }

    private void 보고를_심는다(ExceptionReportFixtures fixtures, long academyId, long busId, OffsetDateTime base,
            int count) {
        for (int i = 0; i < count; i++) {
            OffsetDateTime departTime = base.plusMinutes(i);
            long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                    departTime.minusMinutes(30));
            long reporter = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사" + i, departTime);
            fixtures.exceptionReport(academyId, runId, ExceptionReportType.ETC, "보고", reporter, departTime, null);
        }
    }

    @Test
    void 관계자_비상_목록은_취소_건도_최근_상한_건만_돌려준다() throws Exception {
        EmergencyFixtures fixtures = emergencyFixtures();
        long academyId = fixtures.academy();
        String staffToken = 토큰(fixtures.staffAccount(academyId, "직원"), academyId, Role.STAFF);
        취소된_신고를_심는다(fixtures, academyId, OVER_LIMIT);

        mockMvc.perform(get("/api/v1/staff/emergencies").param("status", "canceled").header("Authorization",
                staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(PageParams.UNPAGED_LIST_MAX));
    }

    @Test
    void 메인_관리자_비상_목록도_취소_건은_최근_상한_건만_돌려준다() throws Exception {
        EmergencyFixtures fixtures = emergencyFixtures();
        long academyId = fixtures.academy();
        String adminToken = 토큰(fixtures.systemAdminAccount("메인관리자"), null, Role.SYSTEM_ADMIN);
        취소된_신고를_심는다(fixtures, academyId, OVER_LIMIT);

        mockMvc.perform(get("/api/v1/admin/emergencies").param("status", "canceled").param("academy_id",
                String.valueOf(academyId)).header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(PageParams.UNPAGED_LIST_MAX));
    }

    private void 취소된_신고를_심는다(EmergencyFixtures fixtures, long academyId, int count) {
        OffsetDateTime base = OffsetDateTime.parse("2030-04-01T09:00:00+09:00");
        long runId = fixtures.confirmedRun(academyId, fixtures.bus(academyId), base);
        long raiserId = managerRepository
                .findByAccountId(fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", base))
                .orElseThrow().getId();
        for (int i = 0; i < count; i++) {
            OffsetDateTime receivedAt = base.plusMinutes(i);
            EmergencyAlert alert = EmergencyAlert.onRaise(academyId, runId, "1호차", raiserId, ManagerRole.DRIVER,
                    EmergencyType.ACCIDENT, 0, receivedAt, receivedAt, UUID.randomUUID());
            alert.cancel(receivedAt.plusSeconds(10));
            emergencyAlertRepository.save(alert);
        }
    }

    private long 목록_쿼리_수(String path, String token) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        mockMvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk());
        return statistics.getPrepareStatementCount();
    }

    private String 토큰(long accountId, Long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }
}
