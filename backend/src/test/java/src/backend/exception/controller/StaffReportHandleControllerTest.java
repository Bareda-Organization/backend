package src.backend.exception.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
import testsupport.clock.FixedClock20300401Config;

/**
 * 운행 리포트 처리 표시(API_SPEC §5.20 · Ruling 814) — 처리 뒤 목록 반영 · 두 번째 요청이 처음 처리자를 덮지 않는 멱등 ·
 * 남의 학원·없는 보고 {@code 404 REPORT_NOT_FOUND} · 관계자가 아닌 역할 {@code 403} · 목록 {@code handled} 필터와
 * {@code counts} 를 검사한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FixedClock20300401Config.class)
class StaffReportHandleControllerTest {

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

    private ExceptionReportFixtures fixtures() {
        return new ExceptionReportFixtures(academyRepository, busRepository, stopRepository, studentRepository,
                accountRepository, managerRepository, assignmentRepository, runRepository, runRiderRepository,
                academyStaffRepository, exceptionReportRepository);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    @Test
    @DisplayName("처리 표시 — 응답이 목록 항목 모양으로 handled=true·시각·처리자 이름을 담고, 이어진 목록에도 같은 값이 보인다")
    void 처리_표시는_목록에_반영된다() throws Exception {
        ExceptionReportFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long staffAccountId = fixtures.staffAccount(academyId, "김관계");
        long reportId = fixtures.exceptionReport(academyId, runId, ExceptionReportType.ROAD_BLOCK, "도로 통제",
                driverAccountId, now(), null);

        mockMvc.perform(post("/api/v1/staff/reports/{id}/handle", reportId)
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.report_id").value(reportId))
                .andExpect(jsonPath("$.data.handled").value(true))
                .andExpect(jsonPath("$.data.handled_at").exists())
                .andExpect(jsonPath("$.data.handled_by_name").value("김관계"));

        mockMvc.perform(get("/api/v1/staff/reports")
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].report_id").value(reportId))
                .andExpect(jsonPath("$.data.items[0].handled").value(true))
                .andExpect(jsonPath("$.data.items[0].handled_at").exists())
                .andExpect(jsonPath("$.data.items[0].handled_by_name").value("김관계"));
    }

    @Test
    @DisplayName("처리 표시 멱등 — 이미 처리된 보고에 다른 관계자가 다시 보내도 처음 처리자·시각이 남고 200 이다")
    void 두_번_보내면_처음_처리자와_시각이_유지된다() throws Exception {
        ExceptionReportFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long firstStaffId = fixtures.formerStaffAccount(academyId, "먼저");
        long secondStaffId = fixtures.staffAccount(academyId, "나중");
        long reportId = fixtures.exceptionReport(academyId, runId, ExceptionReportType.ETC, "기타", driverAccountId,
                now(), null);
        OffsetDateTime firstHandledAt = now().minusHours(1);
        fixtures.markHandled(academyId, reportId, firstStaffId, firstHandledAt);

        mockMvc.perform(post("/api/v1/staff/reports/{id}/handle", reportId)
                .header("Authorization", 토큰(secondStaffId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.handled").value(true))
                .andExpect(jsonPath("$.data.handled_by_name").value("먼저"))
                .andExpect(jsonPath("$.data.handled_at").value(firstHandledAt.toInstant().toString()));
    }

    @Test
    @DisplayName("처리 표시 — 없는 보고와 남의 학원 보고는 둘 다 404 REPORT_NOT_FOUND 이고 남의 학원 보고는 바뀌지 않는다")
    void 없는_보고와_남의_학원_보고는_404다() throws Exception {
        ExceptionReportFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long otherAcademyId = fixtures.academy();
        long otherBusId = fixtures.bus(otherAcademyId);
        long otherRunId = fixtures.confirmedRun(otherAcademyId, otherBusId, Direction.TO_ACADEMY, now(),
                now().minusMinutes(30));
        long otherDriverId = fixtures.assignedManager(otherAcademyId, otherRunId, ManagerRole.DRIVER, "타기사", now());
        long otherReportId = fixtures.exceptionReport(otherAcademyId, otherRunId, ExceptionReportType.ETC, "타 학원",
                otherDriverId, now(), null);
        long staffAccountId = fixtures.staffAccount(academyId, "관계자");

        mockMvc.perform(post("/api/v1/staff/reports/{id}/handle", otherReportId)
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("REPORT_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/staff/reports/{id}/handle", 999_999_999L)
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("REPORT_NOT_FOUND"));

        // 남의 학원 보고는 그 학원 관계자 눈에 여전히 미처리다.
        long otherStaffId = fixtures.staffAccount(otherAcademyId, "타관계자");
        mockMvc.perform(get("/api/v1/staff/reports")
                .header("Authorization", 토큰(otherStaffId, otherAcademyId, Role.STAFF)))
                .andExpect(jsonPath("$.data.items[0].handled").value(false));
    }

    @Test
    @DisplayName("처리 표시 — 관계자가 아닌 역할(기사)은 403 이다")
    void 관계자가_아니면_403이다() throws Exception {
        ExceptionReportFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long reportId = fixtures.exceptionReport(academyId, runId, ExceptionReportType.ETC, "기타", driverAccountId,
                now(), null);

        mockMvc.perform(post("/api/v1/staff/reports/{id}/handle", reportId)
                .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("목록 — handled 쿼리는 처리 여부로 좁히고, counts 는 handled 만 뺀 같은 조건(type)의 건수다")
    void handled_쿼리와_counts는_handled만_빼고_센다() throws Exception {
        ExceptionReportFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long staffAccountId = fixtures.staffAccount(academyId, "관계자");
        long handledEtc = fixtures.exceptionReport(academyId, runId, ExceptionReportType.ETC, "처리됨", driverAccountId,
                now(), null);
        fixtures.exceptionReport(academyId, runId, ExceptionReportType.ETC, "미처리1", driverAccountId, now(), null);
        fixtures.exceptionReport(academyId, runId, ExceptionReportType.ETC, "미처리2", driverAccountId, now(), null);
        // type 이 다른 행 — counts 에 들어가면 안 된다.
        fixtures.exceptionReport(academyId, runId, ExceptionReportType.ROAD_BLOCK, "다른 종류", driverAccountId, now(),
                null);
        fixtures.markHandled(academyId, handledEtc, staffAccountId, now());

        mockMvc.perform(get("/api/v1/staff/reports").param("type", "etc").param("handled", "true")
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].report_id").value(handledEtc))
                .andExpect(jsonPath("$.data.counts.handled").value(1))
                .andExpect(jsonPath("$.data.counts.unhandled").value(2));

        mockMvc.perform(get("/api/v1/staff/reports").param("type", "etc").param("handled", "false")
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.counts.handled").value(1))
                .andExpect(jsonPath("$.data.counts.unhandled").value(2));
    }

    @Test
    @DisplayName("목록 — handled 가 불리언이 아니면 422 VALIDATION_FAILED 이고, 항목에 보고자 역할(driver·escort)이 실린다")
    void handled가_불리언이_아니면_422이고_보고자_역할이_실린다() throws Exception {
        ExceptionReportFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        long escortAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", now());
        long staffAccountId = fixtures.staffAccount(academyId, "관계자");
        fixtures.exceptionReport(academyId, runId, ExceptionReportType.ETC, "동승자 보고", escortAccountId, now(), null);

        mockMvc.perform(get("/api/v1/staff/reports").param("handled", "abc")
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/v1/staff/reports")
                .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(jsonPath("$.data.items[0].reported_by_role").value("escort"))
                .andExpect(jsonPath("$.data.items[0].handled_at").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].handled_by_name").doesNotExist());
    }

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }
}
