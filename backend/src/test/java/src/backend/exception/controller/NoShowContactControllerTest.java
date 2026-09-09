package src.backend.exception.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.command.BoardingCommandFixtures;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.exception.entity.NoShowCase;
import src.backend.exception.repository.NoShowCaseRepository;
import src.backend.exception.repository.NoShowContactRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 미승차 연락 시도 기록(API_SPEC §4.8, Phase 11 목표 3) — {@code POST
 * /runs/{runId}/riders/{riderId}/no-show-contacts}. 케이스는 {@code BoardingController} 의 no_show
 * 처리 경로를 거치지 않고 {@link NoShowCaseRepository} 로 직접 만든다({@code NoShowEscalationFixtures}
 * 와 같은 근거 — 이 컨트롤러의 관심사는 이미 존재하는 케이스에 연락 시도를 쌓는 것이지 케이스 생성
 * 그 자체가 아니다) — 그래서 회차는 {@code moving} 이기만 하면 되고 {@code BoardingCommandFixtures} 를
 * 그대로 재사용한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NoShowContactControllerTest {

    private static final String RECORD_ATTEMPT = "/api/v1/runs/%d/riders/%d/no-show-contacts";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

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

    @Autowired
    private NoShowCaseRepository noShowCaseRepository;

    @Autowired
    private NoShowContactRepository noShowContactRepository;

    @Autowired
    private ManagerRepository managerRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    private BoardingCommandFixtures fixtures;

    @TestConfiguration
    static class FixedClockConfig {

        private static final Instant FIXED = Instant.parse("2031-04-01T03:00:00Z"); // 2031-04-01 12:00 KST

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }
    }

    private BoardingCommandFixtures fixtures() {
        if (fixtures == null) {
            fixtures = new BoardingCommandFixtures(academyRepository, busRepository, studentRepository,
                    guardianRepository, guardianStudentRepository, accountRepository, academyStaffRepository,
                    runRepository, stopRepository, runRiderRepository, confirmedRouteRepository,
                    routeVersionRepository, runStopRepository, jdbcTemplate, entityManager);
        }
        return fixtures;
    }

    /** 대기 만료 시각이 이미 지난 케이스를 하나 만들고, 그 소유 학원·회차·탑승자·케이스 id 를 돌려준다. */
    private long[] caseScenario(OffsetDateTime now) {
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.500000", "127.000000");
        long studentId = fixtures().student(academyId, "학생-T1F1");
        long runId = fixtures().movingRun(academyId, busId, now.minusMinutes(10), now.minusMinutes(40));
        long riderId = fixtures().runRider(runId, studentId, stopId);
        NoShowCase noShowCase = noShowCaseRepository
                .save(NoShowCase.forRunRider(riderId, now.minusMinutes(10), now.minusMinutes(1), now.minusMinutes(10)));
        entityManager.flush();
        return new long[] { academyId, runId, riderId, noShowCase.getId() };
    }

    // ── 목표3 — 적재: 연락 시도가 no_show_contact 에 실제로 쌓인다 ──────────────

    @Test
    @DisplayName("목표3 — 동승자가 연락 시도를 기록하면 no_show_contact 행이 생기고 201 을 응답한다")
    void 연락_시도를_기록하면_no_show_contact_행이_생긴다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long[] s = caseScenario(now);
        long academyId = s[0];
        long runId = s[1];
        long riderId = s[2];
        long caseId = s[3];
        long escortAccountId = fixtures().assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);

        mockMvc.perform(post(RECORD_ATTEMPT.formatted(runId, riderId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attempt_type\":\"call\",\"result\":\"no_answer\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.case_id").value(caseId))
                .andExpect(jsonPath("$.data.attempt_type").value("call"))
                .andExpect(jsonPath("$.data.result").value("no_answer"));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM no_show_contact WHERE no_show_case_id = ?",
                Integer.class, caseId)).as("①적재된 행 수").isEqualTo(1);
    }

    // ── 목표3 — 중단: result=answered 면 카운트다운을 멈춘다 ────────────────────

    @Test
    @DisplayName("목표3 — result=answered 로 기록하면 케이스가 즉시 종결되고 다음 폴링에서 제외된다")
    void 응답이_answered_면_카운트다운이_중단된다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long[] s = caseScenario(now);
        long academyId = s[0];
        long runId = s[1];
        long riderId = s[2];
        long caseId = s[3];
        long escortAccountId = fixtures().assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);

        mockMvc.perform(post(RECORD_ATTEMPT.formatted(runId, riderId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attempt_type\":\"call\",\"result\":\"answered\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.resolved_at").isNotEmpty());

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT resolved_at FROM no_show_case WHERE id = ?",
                OffsetDateTime.class, caseId)).as("①resolved_at 이 채워져야 한다").isNotNull();

        List<NoShowCase> due = noShowCaseRepository.findDueForEscalation(now.plusHours(1), PageRequest.of(0, 50));
        assertThat(due).as("②해소된 케이스는 다음 폴링(findDueForEscalation) 대상에서 빠져야 한다")
                .extracting(NoShowCase::getId).doesNotContain(caseId);
    }

    /** answered 와 짝을 이루는 시험 — no_answer 는 중단시키지 않는다("항상 중단"으로 구현해도 통과하는 함정 방지). */
    @Test
    @DisplayName("목표3 — result=no_answer 는 카운트다운을 멈추지 않고 다음 폴링 대상에 그대로 남는다")
    void 응답이_no_answer_면_카운트다운이_유지된다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long[] s = caseScenario(now);
        long academyId = s[0];
        long runId = s[1];
        long riderId = s[2];
        long caseId = s[3];
        long escortAccountId = fixtures().assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);

        mockMvc.perform(post(RECORD_ATTEMPT.formatted(runId, riderId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attempt_type\":\"call\",\"result\":\"no_answer\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.resolved_at").isEmpty());

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT resolved_at FROM no_show_case WHERE id = ?",
                OffsetDateTime.class, caseId)).as("①resolved_at 은 비어 있어야 한다").isNull();

        List<NoShowCase> due = noShowCaseRepository.findDueForEscalation(now.plusHours(1), PageRequest.of(0, 50));
        assertThat(due).as("②해소되지 않았으니 여전히 폴링 대상이어야 한다").extracting(NoShowCase::getId)
                .contains(caseId);
    }

    // ── 목표3 — 권한: 동승자 전용, 다른 역할은 거부된다 ──────────────────────

    @Test
    @DisplayName("목표3 — 기사 토큰으로 연락 시도를 기록하려 하면 403 ESCORT_ONLY 이고 아무 행도 생기지 않는다")
    void 동승자가_아니면_403_ESCORT_ONLY_이다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long[] s = caseScenario(now);
        long academyId = s[0];
        long runId = s[1];
        long riderId = s[2];
        long caseId = s[3];
        long driverAccountId = fixtures().driverAccount(academyId);

        mockMvc.perform(post(RECORD_ATTEMPT.formatted(runId, riderId))
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attempt_type\":\"call\",\"result\":\"no_answer\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ESCORT_ONLY"));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM no_show_contact WHERE no_show_case_id = ?",
                Integer.class, caseId)).as("거부됐으니 행이 생기면 안 된다").isEqualTo(0);
    }

    // ── 목표3 — 학원 범위: 남의 학원 회차에는 접근할 수 없다 ────────────────────

    @Test
    @DisplayName("목표3 — 다른 학원 동승자가 접근하면 403 FORBIDDEN 이고 아무 행도 생기지 않는다(§1.11, Ruling 259(b))")
    void 다른_학원_동승자는_403_FORBIDDEN_이다() throws Exception {
        // 배치는 매니저·회차가 같은 학원일 때만 생성되므로(NoShowContactCommandService 판정 순서),
        // 다른 학원 동승자는 회차 존재 여부와 무관하게 배치 확인에서 걸려 403 이다 — 404 가 아니다.
        OffsetDateTime now = OffsetDateTime.now(clock);
        long[] s = caseScenario(now);
        long runId = s[1];
        long riderId = s[2];
        long caseId = s[3];
        long otherAcademyId = fixtures().academy();
        long otherEscortAccountId = fixtures().escortAccount(otherAcademyId);

        mockMvc.perform(post(RECORD_ATTEMPT.formatted(runId, riderId))
                        .header("Authorization", 토큰(otherEscortAccountId, otherAcademyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attempt_type\":\"call\",\"result\":\"no_answer\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM no_show_contact WHERE no_show_case_id = ?",
                Integer.class, caseId)).as("거부됐으니 행이 생기면 안 된다").isEqualTo(0);
    }

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }
}
