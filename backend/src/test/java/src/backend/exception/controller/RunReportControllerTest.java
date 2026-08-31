package src.backend.exception.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.exception.repository.ExceptionReportRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.run.repository.RunRepository;
import src.backend.academy.repository.AcademyRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 기사·동승자 단말의 현장 예외 보고 등록 API(§4.13, EXC-02·03, Phase 11 goal 15).
 *
 * <p>{@code @Transactional} 을 쓴다 — {@code DriverRunControllerTest} 와 같은 근거로, 커맨드
 * 서비스의 {@code @Transactional} 은 기본 전파라 테스트 트랜잭션에 합류하고 끝나면 함께 롤백된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RunReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    // ── goal 15 — 등록 성공 ────────────────────────────────────────────────

    @Test
    @DisplayName("goal15 — 도로 통제(rider_id 불필요) 보고는 배치된 기사가 등록하면 201 이고 저장된다")
    void 도로_통제_보고는_배치된_기사가_등록하면_201이다() throws Exception {
        ExceptionReportFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/reports")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"road_block\",\"memo\":\"도로 공사로 우회 중\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.report_id").exists())
                .andExpect(jsonPath("$.data.reported_at").exists());

        assertThat(보고_개수(runId)).isEqualTo(1);
        assertThat(보고_타입(runId)).isEqualTo("road_block");
        assertThat(보고_라이더(runId)).as("보호자 부재가 아니면 run_rider_id 가 비어 있어야 한다").isNull();
    }

    @Test
    @DisplayName("goal15 — 보호자 부재 보고는 유효한 rider_id 와 함께 배치된 동승자가 등록하면 201 이고 run_rider_id 가 저장된다")
    void 보호자_부재_보고는_유효한_rider_id로_동승자가_등록하면_201이다() throws Exception {
        ExceptionReportFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long stopId = fixtures.stop(academyId, "37.560000", "126.970000");
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        long escortAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", now());
        long studentId = fixtures.student(academyId, "학생1");
        long runRiderId = fixtures.rider(runId, studentId, stopId);

        mockMvc.perform(post("/api/v1/runs/" + runId + "/reports")
                .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"guardian_absent\",\"memo\":\"보호자가 정류장에 없음\",\"rider_id\":" + runRiderId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.report_id").exists());

        assertThat(보고_타입(runId)).isEqualTo("guardian_absent");
        assertThat(보고_라이더(runId)).isEqualTo(runRiderId);
    }

    // ── goal 15 — 422 vs 404 구분 ──────────────────────────────────────────

    @Test
    @DisplayName("goal15 급소 — type=guardian_absent 인데 rider_id 가 없으면 422 VALIDATION_FAILED 이고 저장되지 않는다")
    void 보호자_부재_보고에_rider_id가_없으면_422다() throws Exception {
        ExceptionReportFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/reports")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"guardian_absent\",\"memo\":\"보호자가 없음\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(보고_개수(runId)).as("rider_id 누락이면 저장되면 안 된다").isEqualTo(0);
    }

    @Test
    @DisplayName("goal15 급소 — type=guardian_absent 인데 rider_id 가 이 회차의 탑승자가 아니면 404 RIDER_NOT_FOUND 이고 저장되지 않는다")
    void 보호자_부재_보고에_rider_id가_유효하지_않으면_404다() throws Exception {
        ExceptionReportFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long stopId = fixtures.stop(academyId, "37.560000", "126.970000");
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());

        // 다른 회차의 라이더 — id 는 존재하지만 이 회차 소속이 아니다.
        long otherRunId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY,
                departTime.plusHours(1), departTime.minusMinutes(30));
        long studentId = fixtures.student(academyId, "학생1");
        long otherRunRiderId = fixtures.rider(otherRunId, studentId, stopId);

        mockMvc.perform(post("/api/v1/runs/" + runId + "/reports")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"guardian_absent\",\"memo\":\"보호자가 없음\",\"rider_id\":" + otherRunRiderId + "}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RIDER_NOT_FOUND"));

        assertThat(보고_개수(runId)).as("rider_id 가 이 회차 소속이 아니면 저장되면 안 된다").isEqualTo(0);
    }

    // ── goal 15 — 회차·인가 ────────────────────────────────────────────────

    @Test
    @DisplayName("goal15 — 배치는 통과해도 토큰이 주장하는 academy_id 가 그 회차의 실제 학원과 다르면 404 RUN_NOT_FOUND 이고 저장되지 않는다"
            + "(RunAssignmentAccess#assertAssignedDriverOrEscort 는 학원으로 좁히지 않는 조회라 이 뒤 단계의 회차 조회가 학원 경계를 지킨다)")
    void 배치는_맞아도_토큰_academy가_다르면_RUN_NOT_FOUND다() throws Exception {
        ExceptionReportFixtures fixtures = fixtures();
        long realAcademyId = fixtures.academy();
        long otherAcademyId = fixtures.academy();
        long busId = fixtures.bus(realAcademyId);
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(realAcademyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(realAcademyId, runId, ManagerRole.DRIVER, "기사", now());

        // 배치 조회는 통과하지만(학원으로 좁히지 않으므로), 토큰이 주장하는 academy_id 가 실제와 다르면
        // 회차 조회(findByIdAndAcademyId)가 걸러야 한다 — 그렇지 않으면 남의 학원 회차가 새는 것과 같다.
        mockMvc.perform(post("/api/v1/runs/" + runId + "/reports")
                .header("Authorization", 토큰(driverAccountId, otherAcademyId, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"road_block\",\"memo\":\"도로 공사\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_FOUND"));

        assertThat(보고_개수(runId)).as("학원 불일치면 저장되면 안 된다").isEqualTo(0);
    }

    @Test
    @DisplayName("goal15 FORBIDDEN — 같은 학원의 기사이지만 이 회차에 배치되지 않으면 403 FORBIDDEN 이고 저장되지 않는다")
    void 배치되지_않은_같은_학원_기사는_FORBIDDEN_이다() throws Exception {
        ExceptionReportFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "배치된기사", now());
        long unassignedDriverAccountId = fixtures.unassignedManager(academyId, ManagerRole.DRIVER, "미배치기사");

        mockMvc.perform(post("/api/v1/runs/" + runId + "/reports")
                .header("Authorization", 토큰(unassignedDriverAccountId, academyId, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"road_block\",\"memo\":\"도로 공사\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(보고_개수(runId)).as("배치되지 않은 호출은 저장되면 안 된다").isEqualTo(0);
    }

    @Test
    @DisplayName("goal15 FORBIDDEN — 이 회차에 배치된 기사라도 토큰의 역할이 기사·동승자가 아니면 403 FORBIDDEN 이다")
    void 토큰_역할이_기사_동승자가_아니면_FORBIDDEN_이다() throws Exception {
        ExceptionReportFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/reports")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.STAFF))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"road_block\",\"memo\":\"도로 공사\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(보고_개수(runId)).as("역할 판정에서 거절됐으면 저장되면 안 된다").isEqualTo(0);
    }

    // ── 픽스처 · 호출 도우미 ──────────────────────────────────────────────

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }

    private Integer 보고_개수(long runId) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM exception_report WHERE run_id = ?",
                Integer.class, runId);
        return count == null ? 0 : count;
    }

    private String 보고_타입(long runId) {
        return jdbcTemplate.queryForObject(
                "SELECT type FROM exception_report WHERE run_id = ? ORDER BY id DESC LIMIT 1", String.class, runId);
    }

    private Long 보고_라이더(long runId) {
        return jdbcTemplate.queryForObject(
                "SELECT run_rider_id FROM exception_report WHERE run_id = ? ORDER BY id DESC LIMIT 1", Long.class,
                runId);
    }
}
