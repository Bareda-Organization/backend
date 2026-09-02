package src.backend.exception.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.exception.entity.ExceptionReportType;
import src.backend.exception.repository.ExceptionReportRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 관계자 웹의 예외 보고 조회 API(§5.20, Phase 11 goal 16) — {@code type}·{@code date}·{@code run_id}
 * 필터 각각 매칭·비매칭 행을 함께 심어 필터가 실제로 좁히는지 검사한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private Clock clock;

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

    @TestConfiguration
    static class FixedClockConfig {

        private static final Instant FIXED = Instant.parse("2030-04-01T03:00:00Z"); // 2030-04-01 12:00 KST

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }
    }

    private ExceptionReportFixtures fixtures() {
        return new ExceptionReportFixtures(academyRepository, busRepository, stopRepository, studentRepository,
                accountRepository, managerRepository, assignmentRepository, runRepository, runRiderRepository,
                academyStaffRepository, exceptionReportRepository);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    // ── goal 16 — type 필터 ───────────────────────────────────────────────

    @Test
    @DisplayName("goal16 — type 필터는 매칭 타입만 남기고 비매칭 타입은 뺀다")
    void type_필터는_매칭만_남긴다() throws Exception {
        ExceptionReportFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long staffAccountId = fixtures.staffAccount(academyId, "관계자");
        long matching = fixtures.exceptionReport(academyId, runId, ExceptionReportType.ROAD_BLOCK, "도로 통제",
                driverAccountId, now(), null);
        fixtures.exceptionReport(academyId, runId, ExceptionReportType.VEHICLE_ISSUE, "차량 문제", driverAccountId,
                now(), null);

        mockMvc.perform(get("/api/v1/staff/reports").param("type", "road_block")
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].report_id").value(matching))
                .andExpect(jsonPath("$.data.items[0].type").value("road_block"));
    }

    // ── goal 16 — run_id 필터 ─────────────────────────────────────────────

    @Test
    @DisplayName("goal16 — run_id 필터는 매칭 회차만 남기고 다른 회차는 뺀다")
    void run_id_필터는_매칭만_남긴다() throws Exception {
        ExceptionReportFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId1 = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        long runId2 = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now().plusHours(1),
                now().minusMinutes(30));
        long driverAccountId1 = fixtures.assignedManager(academyId, runId1, ManagerRole.DRIVER, "기사1", now());
        long driverAccountId2 = fixtures.assignedManager(academyId, runId2, ManagerRole.DRIVER, "기사2", now());
        long staffAccountId = fixtures.staffAccount(academyId, "관계자");
        long matching = fixtures.exceptionReport(academyId, runId1, ExceptionReportType.ETC, "기타1",
                driverAccountId1, now(), null);
        fixtures.exceptionReport(academyId, runId2, ExceptionReportType.ETC, "기타2", driverAccountId2, now(), null);

        mockMvc.perform(get("/api/v1/staff/reports").param("run_id", String.valueOf(runId1))
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].report_id").value(matching))
                .andExpect(jsonPath("$.data.items[0].run_id").value(runId1));
    }

    // ── goal 16 — date 필터(reported_at 의 날짜 성분, run.service_date 아님) ──

    @Test
    @DisplayName("goal16 — date 필터는 reported_at 이 그 날짜(학원 자정 기준)인 행만 남기고 하루 밖은 뺀다")
    void date_필터는_reported_at_기준_매칭만_남긴다() throws Exception {
        ExceptionReportFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long staffAccountId = fixtures.staffAccount(academyId, "관계자");
        // now() 는 2030-04-01 12:00 KST 로 고정돼 있다 — 매칭은 같은 날, 비매칭은 하루 전날.
        long matching = fixtures.exceptionReport(academyId, runId, ExceptionReportType.ETC, "오늘 보고",
                driverAccountId, now(), null);
        fixtures.exceptionReport(academyId, runId, ExceptionReportType.ETC, "어제 보고", driverAccountId,
                now().minusDays(1), null);

        mockMvc.perform(get("/api/v1/staff/reports").param("date", "2030-04-01")
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].report_id").value(matching));
    }

    // ── goal 16 — 필드 조립 ───────────────────────────────────────────────

    @Test
    @DisplayName("goal16 — 보호자 부재 보고는 student_name 이 채워지고 handled 는 항상 false·handled_at 은 null 이다")
    void 보호자_부재_보고는_student_name이_채워지고_handled는_항상_false다() throws Exception {
        ExceptionReportFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long stopId = fixtures.stop(academyId, "37.560000", "126.970000");
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long staffAccountId = fixtures.staffAccount(academyId, "관계자");
        long studentId = fixtures.student(academyId, "학생1");
        long runRiderId = fixtures.rider(runId, studentId, stopId);
        fixtures.exceptionReport(academyId, runId, ExceptionReportType.GUARDIAN_ABSENT, "보호자 부재", driverAccountId,
                now(), runRiderId);
        // 비매칭 대조 — 타입이 다르면 student_name 이 없어야 한다.
        fixtures.exceptionReport(academyId, runId, ExceptionReportType.ETC, "기타", driverAccountId, now(), null);

        mockMvc.perform(get("/api/v1/staff/reports").param("type", "guardian_absent")
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].student_name").value("학생1"))
                .andExpect(jsonPath("$.data.items[0].bus_no").exists())
                .andExpect(jsonPath("$.data.items[0].reported_by").value("기사"))
                .andExpect(jsonPath("$.data.items[0].handled").value(false))
                .andExpect(jsonPath("$.data.items[0].handled_at").doesNotExist());
    }

    @Test
    @DisplayName("goal16 — 보호자 부재가 아닌 보고는 student_name 이 없다")
    void 보호자_부재가_아닌_보고는_student_name이_없다() throws Exception {
        ExceptionReportFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long staffAccountId = fixtures.staffAccount(academyId, "관계자");
        fixtures.exceptionReport(academyId, runId, ExceptionReportType.ROAD_BLOCK, "도로 통제", driverAccountId, now(),
                null);

        mockMvc.perform(get("/api/v1/staff/reports")
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].student_name").doesNotExist());
    }

    // ── goal 16 — 학원 격리 ───────────────────────────────────────────────

    @Test
    @DisplayName("goal16 — 필터 없는 목록은 이 학원 보고만 돌려주고 다른 학원 보고는 새지 않는다")
    void 필터_없는_목록은_다른_학원_보고를_섞지_않는다() throws Exception {
        ExceptionReportFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long otherAcademyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long otherBusId = fixtures.bus(otherAcademyId);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        long otherRunId = fixtures.confirmedRun(otherAcademyId, otherBusId, Direction.TO_ACADEMY, now(),
                now().minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long otherDriverAccountId = fixtures.assignedManager(otherAcademyId, otherRunId, ManagerRole.DRIVER, "타학원기사",
                now());
        long staffAccountId = fixtures.staffAccount(academyId, "관계자");
        long matching = fixtures.exceptionReport(academyId, runId, ExceptionReportType.ETC, "우리 학원 보고",
                driverAccountId, now(), null);
        fixtures.exceptionReport(otherAcademyId, otherRunId, ExceptionReportType.ETC, "다른 학원 보고",
                otherDriverAccountId, now(), null);

        mockMvc.perform(get("/api/v1/staff/reports")
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].report_id").value(matching));
    }

    // ── goal 16 — 상세 ────────────────────────────────────────────────────

    @Test
    @DisplayName("goal16 — 상세는 자기 학원 보고를 200 으로 돌려준다")
    void 상세는_자기_학원_보고를_200으로_돌려준다() throws Exception {
        ExceptionReportFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long staffAccountId = fixtures.staffAccount(academyId, "관계자");
        long reportId = fixtures.exceptionReport(academyId, runId, ExceptionReportType.ETC, "기타 보고",
                driverAccountId, now(), null);

        mockMvc.perform(get("/api/v1/staff/reports/" + reportId)
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.report_id").value(reportId))
                .andExpect(jsonPath("$.data.memo").value("기타 보고"));
    }

    @Test
    @DisplayName("goal16 급소 — 상세는 존재하지 않거나 다른 학원 소속인 id 면 404 REPORT_NOT_FOUND 다")
    void 상세는_없거나_다른_학원_소속이면_404다() throws Exception {
        ExceptionReportFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long otherAcademyId = fixtures.academy();
        long busId = fixtures.bus(otherAcademyId);
        long runId = fixtures.confirmedRun(otherAcademyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(otherAcademyId, runId, ManagerRole.DRIVER, "타학원기사", now());
        long staffAccountId = fixtures.staffAccount(academyId, "관계자");
        long otherAcademyReportId = fixtures.exceptionReport(otherAcademyId, runId, ExceptionReportType.ETC,
                "다른 학원 보고", driverAccountId, now(), null);

        // 존재하지 않는 id
        mockMvc.perform(get("/api/v1/staff/reports/999999999")
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("REPORT_NOT_FOUND"));

        // 다른 학원 소속 id
        mockMvc.perform(get("/api/v1/staff/reports/" + otherAcademyReportId)
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("REPORT_NOT_FOUND"));
    }

    // ── 호출 도우미 ──────────────────────────────────────────────────────

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }
}
