package src.backend.request.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.request.command.BoardingIntentFixtures;
import src.backend.routing.pipeline.RouteComputationPipeline;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 탑승 의사 토글(ATT-01·02, P-03, API_SPEC §3.6) — Phase 8 목표 1·2·5·8·9(부분).
 *
 * <p>3구간 판정 자체({@link src.backend.request.domain.ChangeWindowPolicy})는 별도로 이미 단위
 * 시험이 있다고 가정하지 않는다 — 이 클래스가 그 판정을 <b>엔드포인트 경유로 실제로 타는지</b>까지
 * 함께 본다. {@link RouteComputationPipeline} 을 스파이로 감싸 ①·③ 어느 경로도 이를 부르지 않음을
 * 직접 확인한다(목표 1 — Ruling 198, "재최적화는 결과이지 동기 호출이 아니다").
 *
 * <p>목표 9 는 부분이다(Ruling 195) — {@code notification_log} 적재까지만 검사하고, 웹소켓 발행은
 * Phase 10 범위라 이 클래스가 단언하지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BoardingIntentControllerTest {

    private static final String INTENT = "/api/v1/students/%d/runs/%d/intent";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private Clock clock;

    @MockitoSpyBean
    private RouteComputationPipeline routeComputationPipeline;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private AcademyRepository academyRepository;

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
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private ConfirmedRouteRepository confirmedRouteRepository;

    @Autowired
    private RouteVersionRepository routeVersionRepository;

    @Autowired
    private RunStopRepository runStopRepository;

    @Autowired
    private RunRiderRepository runRiderRepository;

    private BoardingIntentFixtures fixtures;

    @TestConfiguration
    static class FixedClockConfig {

        private static final Instant FIXED = Instant.parse("2030-04-01T03:00:00Z"); // 2030-04-01 12:00 KST

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }
    }

    private BoardingIntentFixtures fixtures() {
        if (fixtures == null) {
            fixtures = new BoardingIntentFixtures(academyRepository, busRepository, studentRepository,
                    guardianRepository, guardianStudentRepository, accountRepository, academyStaffRepository,
                    runRepository, stopRepository, confirmedRouteRepository, routeVersionRepository,
                    runStopRepository, runRiderRepository);
        }
        return fixtures;
    }

    // ── 목표 1 — ①구간 즉시 반영, 재최적화 미호출 ──────────────────────────

    /**
     * ①구간에서 토글하면 응답이 즉시 바뀐 값을 싣고, {@link RouteComputationPipeline#compute} 는 <b>한
     * 번도</b> 불리지 않는다 — 이 회차는 아직 idle 이라 재최적화할 확정 노선 자체가 없다(Ruling 198).
     */
    @Test
    @DisplayName("목표1 — 즉시반영구간에서 토글하면 재최적화 없이 즉시 반영된다")
    void 즉시반영구간에서_토글하면_재최적화_없이_즉시_반영된다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long studentId = fixtures().student(academyId, "학생1");
        BoardingIntentFixtures.GuardianAccount guardian1 = fixtures().guardian(academyId, "보호자1");
        long guardianAccountId = guardian1.accountId();
        fixtures().linkChild(guardian1.guardianId(), studentId, now.minusDays(1));
        long runId = fixtures().run(academyId, busId, now.plusHours(2), now.plusMinutes(90));

        mockMvc.perform(patch(INTENT.formatted(studentId, runId))
                        .header("Authorization", 토큰(guardianAccountId, academyId, Role.PARENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riding\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("applied"))
                .andExpect(jsonPath("$.data.riding").value(false))
                .andExpect(jsonPath("$.data.rider_status").value("absent"));

        verify(routeComputationPipeline, never()).compute(any());

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT riding FROM boarding_intent WHERE run_id = ? AND student_id = ?", Boolean.class, runId,
                studentId)).isFalse();
    }

    // ── 목표 2 — ②구간 승인 대기, 기존값 응답 ───────────────────────────────

    /**
     * ②구간에서 토글하면 즉시 반영되지 않고 승인 대기 큐에 올라간다. 응답의 {@code riding} 은 <b>바뀌지
     * 않은 기존 값</b>이어야 한다(§3.6) — 실제 반영은 승인 이후다.
     */
    @Test
    @DisplayName("목표2 — 승인대기구간에서 토글하면 대기 접수되고 응답은 기존값을 유지한다")
    void 승인대기구간에서_토글하면_대기_접수되고_기존값을_응답한다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long studentId = fixtures().student(academyId, "학생2");
        BoardingIntentFixtures.GuardianAccount guardian2 = fixtures().guardian(academyId, "보호자2");
        long guardianAccountId = guardian2.accountId();
        fixtures().linkChild(guardian2.guardianId(), studentId, now.minusDays(1));
        long runId = fixtures().run(academyId, busId, now.plusMinutes(20), now.minusMinutes(10));

        mockMvc.perform(patch(INTENT.formatted(studentId, runId))
                        .header("Authorization", 토큰(guardianAccountId, academyId, Role.PARENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riding\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("pending_approval"))
                .andExpect(jsonPath("$.data.riding").value(true))
                .andExpect(jsonPath("$.data.change_request_id").isNumber())
                .andExpect(jsonPath("$.data.change_quota_left").value(0));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT riding FROM boarding_intent WHERE run_id = ? AND student_id = ?", Boolean.class, runId,
                studentId)).as("②구간은 실제 반영 전이라 riding 이 그대로여야 한다").isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT change_used_count FROM boarding_intent WHERE run_id = ? AND student_id = ?", Integer.class,
                runId, studentId)).isEqualTo(1);

        Map<String, Object> changeRequest = jdbcTemplate.queryForMap(
                "SELECT source, type, status, window_segment FROM change_request WHERE run_id = ? AND student_id = ?",
                runId, studentId);
        assertThat(changeRequest.get("source")).isEqualTo("intent");
        assertThat(changeRequest.get("type")).isEqualTo("cancel");
        assertThat(changeRequest.get("status")).isEqualTo("pending");
        assertThat(((Number) changeRequest.get("window_segment")).shortValue()).isEqualTo((short) 2);
    }

    // ── 목표 5 — 한도 소진 후 재요청은 403 ──────────────────────────────────

    /** ②구간 변경 한도는 회차당 1회다 — 소진 후 재요청은 {@code 403 CHANGE_LIMIT_REACHED}(목표 5). */
    @Test
    @DisplayName("목표5 — 한도 소진 후 승인대기 재요청은 403 CHANGE_LIMIT_REACHED 다")
    void 한도_소진_후_승인대기_재요청은_403_이다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long studentId = fixtures().student(academyId, "학생3");
        BoardingIntentFixtures.GuardianAccount guardian3 = fixtures().guardian(academyId, "보호자3");
        long guardianAccountId = guardian3.accountId();
        fixtures().linkChild(guardian3.guardianId(), studentId, now.minusDays(1));
        long runId = fixtures().run(academyId, busId, now.plusMinutes(20), now.minusMinutes(10));
        String 토큰 = 토큰(guardianAccountId, academyId, Role.PARENT);

        mockMvc.perform(patch(INTENT.formatted(studentId, runId))
                        .header("Authorization", 토큰)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riding\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch(INTENT.formatted(studentId, runId))
                        .header("Authorization", 토큰)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riding\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CHANGE_LIMIT_REACHED"));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM change_request WHERE run_id = ? AND student_id = ?", Integer.class, runId,
                studentId)).as("소진 뒤 재요청은 두 번째 change_request 를 남기지 않는다").isEqualTo(1);
    }

    // ── 목표 8 — ③구간 재최적화 없는 즉시 수용 ──────────────────────────────

    /**
     * ③구간(운행 시작 후 또는 출발 시각 도달)에서 {@code riding=false} 는 재최적화 없이 즉시 수용되고,
     * 그 정차지에 남은 탑승자가 0명이면 {@code run_stop.change='skipped'} 로 표시된다 — {@code seq} 는
     * 손대지 않는다(목표 8). {@code riding=true}(되돌리기)는 {@code 403 CHANGE_WINDOW_CLOSED} 다.
     */
    @Test
    @DisplayName("목표8 — 마감구간에서 riding=false는 재최적화 없이 수용되고 riding=true는 403이다")
    void 마감구간에서_미등원은_즉시수용되고_되돌리기는_403이다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long studentId = fixtures().student(academyId, "학생4");
        BoardingIntentFixtures.GuardianAccount guardian4 = fixtures().guardian(academyId, "보호자4");
        long guardianAccountId = guardian4.accountId();
        fixtures().linkChild(guardian4.guardianId(), studentId, now.minusDays(1));
        long runId = fixtures().run(academyId, busId, now.minusMinutes(5), now.minusMinutes(35));
        long stopId = fixtures().stop(academyId, "37.560000", "126.970000");
        long runStopId = fixtures().confirmedSingleRiderStop(runId, studentId, stopId, now.minusHours(1));
        String 토큰 = 토큰(guardianAccountId, academyId, Role.PARENT);

        mockMvc.perform(patch(INTENT.formatted(studentId, runId))
                        .header("Authorization", 토큰)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riding\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("applied_no_reroute"))
                .andExpect(jsonPath("$.data.riding").value(false));

        verify(routeComputationPipeline, never()).compute(any());

        entityManager.flush();
        Map<String, Object> runStop = jdbcTemplate.queryForMap("SELECT change, seq FROM run_stop WHERE id = ?",
                runStopId);
        assertThat(runStop.get("change")).isEqualTo("skipped");
        assertThat(runStop.get("seq")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run_rider WHERE run_id = ? AND student_id = ?",
                String.class, runId, studentId)).isEqualTo("absent");

        mockMvc.perform(patch(INTENT.formatted(studentId, runId))
                        .header("Authorization", 토큰)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riding\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CHANGE_WINDOW_CLOSED"));
    }

    // ── 목표 9(부분) — 알림 적재(푸시만, 웹소켓은 Phase 10) ──────────────────

    /**
     * ①·②구간 모두 관계자에게 알림이 적재된다(API_SPEC §9.7, Ruling 195) — 여기서는
     * {@code notification_log} 적재만 본다. 웹소켓 발행은 이 태스크 범위 밖이라 단언하지 않는다.
     */
    @Test
    @DisplayName("목표9(부분) — 즉시반영·승인대기 모두 notification_log 에 적재된다")
    void 즉시반영과_승인대기_모두_알림이_적재된다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long staffAccountId = fixtures().staffAccount(academyId);

        long immediateStudentId = fixtures().student(academyId, "학생5");
        BoardingIntentFixtures.GuardianAccount guardian5 = fixtures().guardian(academyId, "보호자5");
        long guardian5AccountId = guardian5.accountId();
        fixtures().linkChild(guardian5.guardianId(), immediateStudentId, now.minusDays(1));
        long immediateRunId = fixtures().run(academyId, busId, now.plusHours(2), now.plusMinutes(90));

        mockMvc.perform(patch(INTENT.formatted(immediateStudentId, immediateRunId))
                        .header("Authorization", 토큰(guardian5AccountId, academyId, Role.PARENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riding\":false}"))
                .andExpect(status().isOk());

        long approvalStudentId = fixtures().student(academyId, "학생6");
        BoardingIntentFixtures.GuardianAccount guardian6 = fixtures().guardian(academyId, "보호자6");
        long guardian6AccountId = guardian6.accountId();
        fixtures().linkChild(guardian6.guardianId(), approvalStudentId, now.minusDays(1));
        long approvalRunId = fixtures().run(academyId, busId, now.plusMinutes(20), now.minusMinutes(10));

        mockMvc.perform(patch(INTENT.formatted(approvalStudentId, approvalRunId))
                        .header("Authorization", 토큰(guardian6AccountId, academyId, Role.PARENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riding\":false}"))
                .andExpect(status().isOk());

        entityManager.flush();
        // notification_log.run_id 는 이 파이프라인(NotificationOutbox/NotificationDraft)이 채우지
        // 않는다 — RunRouteConfirmedNotificationTest 와 같은 이유로 dedup_key 접두 매칭으로 찾는다.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE recipient_account_id = ? AND type = 'intent_changed' AND dedup_key LIKE ?",
                Integer.class, staffAccountId, "intent_changed:" + immediateRunId + ":%")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE recipient_account_id = ? AND type = 'approval_requested' AND dedup_key LIKE ?",
                Integer.class, staffAccountId, "approval_requested:" + approvalRunId + ":%")).isEqualTo(1);
    }

    // ── 권한 · 학원 범위 ─────────────────────────────────────────────────

    /** {@code INTENT_WRITE} 는 학부모 전용이다 — 학생 토큰으로 호출하면 403 FORBIDDEN(§3.6 에러 표). */
    @Test
    @DisplayName("권한 — 학부모가 아닌 역할은 403 FORBIDDEN 이다")
    void 학부모가_아닌_역할은_403이다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long studentId = fixtures().student(academyId, "학생7");
        long runId = fixtures().run(academyId, busId, now.plusHours(2), now.plusMinutes(90));

        mockMvc.perform(patch(INTENT.formatted(studentId, runId))
                        .header("Authorization", 토큰(90007L, academyId, Role.STUDENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riding\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    /**
     * T1 인계 사항 — {@code BoardingIntentRepository#findByRunIdAndStudentId} 가
     * {@code @AcademyScopeExempt} 인 전제("호출부가 이미 학원 소속을 확인했다")를 이 서비스가
     * 실제로 지킨다. 회차 조회를 {@link RunRepository#findByIdAndAcademyId} 로 학생의 학원에 직접
     * 좁히므로, 다른 학원의 회차를 지목하면 그 조회가 곧바로 빈 결과가 되어 {@code 404 RUN_NOT_FOUND}
     * 다 — 남의 학원 {@code boarding_intent} 행에 닿기 전에 차단된다.
     */
    @Test
    @DisplayName("학원 범위 — 다른 학원의 회차를 지목하면 404 RUN_NOT_FOUND 다")
    void 다른_학원의_회차를_지목하면_404이다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyA = fixtures().academy();
        long studentId = fixtures().student(academyA, "학생8");
        BoardingIntentFixtures.GuardianAccount guardian8 = fixtures().guardian(academyA, "보호자8");
        long guardianAccountId = guardian8.accountId();
        fixtures().linkChild(guardian8.guardianId(), studentId, now.minusDays(1));

        long academyB = fixtures().academy();
        long busB = fixtures().bus(academyB);
        long otherAcademyRunId = fixtures().run(academyB, busB, now.plusHours(2), now.plusMinutes(90));

        mockMvc.perform(patch(INTENT.formatted(studentId, otherAcademyRunId))
                        .header("Authorization", 토큰(guardianAccountId, academyA, Role.PARENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riding\":false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_FOUND"));
    }

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }
}
