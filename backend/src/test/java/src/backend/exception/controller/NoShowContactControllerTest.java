package src.backend.exception.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.auth.TestTokens.토큰;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.annotation.Import;

import src.backend.boarding.command.BoardingCommandFixtures;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.exception.entity.NoShowCase;
import src.backend.exception.repository.NoShowCaseRepository;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import testsupport.clock.FixedClock20310401Config;

/**
 * 미승차 연락 시도 기록(API_SPEC §4.8) — {@code POST
 * /runs/{runId}/riders/{riderId}/no-show-contacts}. 케이스는 {@code BoardingController} 의 no_show
 * 처리 경로를 거치지 않고 {@link NoShowCaseRepository} 로 직접 만든다({@code NoShowEscalationFixtures}
 * 와 같은 근거 — 이 컨트롤러의 관심사는 이미 존재하는 케이스에 연락 시도를 쌓는 것이지 케이스 생성
 * 그 자체가 아니다) — 그래서 회차는 {@code moving} 이기만 하면 되고 {@code BoardingCommandFixtures} 를
 * 그대로 재사용한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FixedClock20310401Config.class)
class NoShowContactControllerTest {

    private static final String RECORD_ATTEMPT = "/api/v1/runs/%d/riders/%d/no-show-contacts";

    @Autowired
    private ApplicationContext applicationContext;

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
    private RunRiderRepository runRiderRepository;

    @Autowired
    private NoShowCaseRepository noShowCaseRepository;

    @Autowired
    private ManagerRepository managerRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    private BoardingCommandFixtures fixtures;

    private BoardingCommandFixtures fixtures() {
        if (fixtures == null) {
            fixtures = BoardingCommandFixtures.of(applicationContext, entityManager);
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
        // 케이스는 탑승자가 no_show 일 때만 존재한다 — 실제 상태와 같게 맞춘다(BR-254)
        RunRider rider = runRiderRepository.findById(riderId).orElseThrow();
        rider.markNoShow(now.minusMinutes(10));
        runRiderRepository.save(rider);
        NoShowCase noShowCase = noShowCaseRepository
                .save(NoShowCase.forRunRider(riderId, now.minusMinutes(10), now.minusMinutes(1), now.minusMinutes(10)));
        entityManager.flush();
        return new long[] { academyId, runId, riderId, noShowCase.getId() };
    }

    // ── 적재:연락 시도가 no_show_contact 에 실제로 쌓인다 ──────────────

    @Test
    @DisplayName("동승자가 연락 시도를 기록하면 no_show_contact 행이 생기고 201 을 응답한다")
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
                        .header("Authorization", 토큰(tokenProvider, escortAccountId, academyId, Role.ESCORT))
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

    /** BR-254 — 미승차를 되돌린 탑승자(케이스는 종결 행으로 남는다, BR-009)에게는 연락 기록을 받지 않는다(§4.8). */
    @Test
    @DisplayName("BR-254 — 미승차를 되돌린 탑승자에게 연락을 기록하면 404 NO_SHOW_CASE_NOT_FOUND")
    void 되돌린_탑승자에게는_연락을_기록하지_못한다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long[] s = caseScenario(now);
        long academyId = s[0];
        long runId = s[1];
        long riderId = s[2];
        long caseId = s[3];
        long escortAccountId = fixtures().assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);
        RunRider rider = runRiderRepository.findById(riderId).orElseThrow();
        rider.revertTo(RiderStatus.WAITING, now);
        runRiderRepository.save(rider);
        noShowCaseRepository.findById(caseId).orElseThrow().resolveByRevert(now);
        entityManager.flush();

        mockMvc.perform(post(RECORD_ATTEMPT.formatted(runId, riderId))
                        .header("Authorization", 토큰(tokenProvider, escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attempt_type\":\"call\",\"result\":\"answered\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_SHOW_CASE_NOT_FOUND"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM no_show_contact WHERE no_show_case_id = ?",
                Integer.class, caseId)).as("이력이 쌓이지 않아야 한다").isZero();
    }

    // ── 중단:result=answered 면 카운트다운을 멈춘다 ────────────────────

    @Test
    @DisplayName("result=answered 로 기록하면 케이스가 즉시 종결되고 다음 폴링에서 제외된다")
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
                        .header("Authorization", 토큰(tokenProvider, escortAccountId, academyId, Role.ESCORT))
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
    @DisplayName("result=no_answer 는 카운트다운을 멈추지 않고 다음 폴링 대상에 그대로 남는다")
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
                        .header("Authorization", 토큰(tokenProvider, escortAccountId, academyId, Role.ESCORT))
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

    // ── 권한:동승자 전용, 다른 역할은 거부된다 ──────────────────────

    @Test
    @DisplayName("기사 토큰으로 연락 시도를 기록하려 하면 403 ESCORT_ONLY 이고 아무 행도 생기지 않는다")
    void 동승자가_아니면_403_ESCORT_ONLY_이다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long[] s = caseScenario(now);
        long academyId = s[0];
        long runId = s[1];
        long riderId = s[2];
        long caseId = s[3];
        long driverAccountId = fixtures().driverAccount(academyId);

        mockMvc.perform(post(RECORD_ATTEMPT.formatted(runId, riderId))
                        .header("Authorization", 토큰(tokenProvider, driverAccountId, academyId, Role.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attempt_type\":\"call\",\"result\":\"no_answer\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ESCORT_ONLY"));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM no_show_contact WHERE no_show_case_id = ?",
                Integer.class, caseId)).as("거부됐으니 행이 생기면 안 된다").isEqualTo(0);
    }

    // ── 학원 범위:남의 학원 회차에는 접근할 수 없다 ────────────────────

    @Test
    @DisplayName("다른 학원 동승자가 접근하면 403 FORBIDDEN 이고 아무 행도 생기지 않는다(§1.11, Ruling 259(b))")
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
                        .header("Authorization", 토큰(tokenProvider, otherEscortAccountId, otherAcademyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attempt_type\":\"call\",\"result\":\"no_answer\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM no_show_contact WHERE no_show_case_id = ?",
                Integer.class, caseId)).as("거부됐으니 행이 생기면 안 된다").isEqualTo(0);
    }

    // ── Ruling 894 ⑧ — 오프라인 큐 재전송 키 ─────────────────────────────

    @Test
    @DisplayName("같은 client_key 를 두 번 보내면 행은 1개이고 두 번째는 200 으로 최초 결과를 돌려준다")
    void 같은_client_key_재전송은_행을_늘리지_않고_200이다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long[] s = caseScenario(now);
        long academyId = s[0];
        long runId = s[1];
        long riderId = s[2];
        long caseId = s[3];
        long escortAccountId = fixtures().assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);
        String body = "{\"attempt_type\":\"call\",\"result\":\"no_answer\",\"client_key\":\"" + UUID.randomUUID() + "\"}";

        mockMvc.perform(post(RECORD_ATTEMPT.formatted(runId, riderId))
                        .header("Authorization", 토큰(tokenProvider, escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post(RECORD_ATTEMPT.formatted(runId, riderId))
                        .header("Authorization", 토큰(tokenProvider, escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.case_id").value(caseId))
                .andExpect(jsonPath("$.data.attempt_type").value("call"))
                .andExpect(jsonPath("$.data.result").value("no_answer"))
                .andExpect(jsonPath("$.data.replayed").doesNotExist());

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM no_show_contact WHERE no_show_case_id = ?",
                Integer.class, caseId)).as("재전송이 연락 기록을 두 번 만들면 안 된다").isEqualTo(1);
    }

    @Test
    @DisplayName("같은 client_key 로 다른 내용(결과)을 보내면 422 VALIDATION_FAILED 이고 새 행이 생기지 않는다")
    void 같은_client_key_다른_내용은_422다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long[] s = caseScenario(now);
        long academyId = s[0];
        long runId = s[1];
        long riderId = s[2];
        long caseId = s[3];
        long escortAccountId = fixtures().assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post(RECORD_ATTEMPT.formatted(runId, riderId))
                        .header("Authorization", 토큰(tokenProvider, escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attempt_type\":\"call\",\"result\":\"no_answer\",\"client_key\":\"" + key + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post(RECORD_ATTEMPT.formatted(runId, riderId))
                        .header("Authorization", 토큰(tokenProvider, escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attempt_type\":\"message\",\"result\":\"no_answer\",\"client_key\":\"" + key + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM no_show_contact WHERE no_show_case_id = ?",
                Integer.class, caseId)).isEqualTo(1);
    }

    @Test
    @DisplayName("client_key 없이 같은 요청을 두 번 보내면 지금처럼 두 번 다 201 이고 행이 2개다")
    void client_key_가_없으면_지금과_같다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long[] s = caseScenario(now);
        long academyId = s[0];
        long runId = s[1];
        long riderId = s[2];
        long caseId = s[3];
        long escortAccountId = fixtures().assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(RECORD_ATTEMPT.formatted(runId, riderId))
                            .header("Authorization", 토큰(tokenProvider, escortAccountId, academyId, Role.ESCORT))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"attempt_type\":\"call\",\"result\":\"no_answer\"}"))
                    .andExpect(status().isCreated());
        }

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM no_show_contact WHERE no_show_case_id = ?",
                Integer.class, caseId)).isEqualTo(2);
    }
}
