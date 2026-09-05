package src.backend.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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

import jakarta.persistence.EntityManager;

import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.security.JwtTokenProvider;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.command.RunConfirmationService;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 강제 확정 콘솔 개입(API_SPEC §6.14, F3 S2 목표 11) — {@code POST
 * /admin/runs/{runId}/force-confirm}.
 *
 * <p>픽스처는 {@code RunConfirmationServiceTest} 와 같은 {@link RunConfirmationFixtures}·고정
 * {@link Clock} 조합을 그대로 쓴다 — 확정 파이프라인 자체는 이미 그 시험이 검증했으므로, 여기서는
 * <b>강제 확정 콘솔이 그 파이프라인을 forceFallback=true 로 진입시키는지</b>와 §6.14 의 전제·에러
 * 조건만 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminRunForceConfirmControllerTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 4, 1); // 월요일

    private static final Weekday WEEKDAY = Weekday.MON;

    private static final String FORCE_CONFIRM = "/api/v1/admin/runs/%d/force-confirm";

    /** 고정 "현재 시각" — 2030-04-01 12:00 KST. {@code confirm_at} 을 이 값의 앞뒤로 둬 경과 여부를 가른다. */
    @TestConfiguration
    static class FixedClockConfig {

        private static final Instant FIXED = Instant.parse("2030-04-01T03:00:00Z");

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private Clock clock;

    @Autowired
    private RunConfirmationService runConfirmationService;

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

    private RunConfirmationFixtures fixtures;

    private RunConfirmationFixtures fixtures() {
        if (fixtures == null) {
            fixtures = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                    routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        }
        return fixtures;
    }

    /**
     * idle + {@code confirm_at} 경과 회차 — 통상 정차지 좌표(스텁의 장애 마커가 아님)라 정상 배치라면
     * {@code fallback_used=false} 로 확정된다. 강제 확정이 이 값을 뒤집는 것 자체가 이 시험의 핵심이다.
     */
    private long dueIdleRun() {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long firstStop = fixtures().stop(academyId, "37.560000", "126.970000");
        long lastStop = fixtures().stop(academyId, "37.561000", "126.971000");
        fixtures().route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, firstStop, lastStop);

        // ck_run_confirm_at 제약 — confirm_at 은 반드시 depart_time - 30분이어야 한다(V1__init_schema.sql).
        // depart_time 을 now + 29분으로 두면 confirm_at 은 now - 1분 — 방금 지난 시점이 된다.
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime departTime = now.plusMinutes(29);
        return fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
    }

    /**
     * 목표 11 — idle 상태이고 {@code confirm_at} 이 지난 회차의 강제 확정은 {@code 201} 이고,
     * {@code route_version.fallback_used} 가 실제로 {@code true} 로 저장된다.
     *
     * <p>{@code fallback_used} 를 <b>DB 컬럼</b>에서 확인하는 것이 핵심이다 — 응답 필드만 보면 그
     * 값을 응답 DTO 가 미리 단정해도 통과한다. 강제 폴백 호출({@code confirmOne(runId, true)})이
     * 조용히 빠져 일반 확정으로 떨어지는 결함은 DB 값을 직접 읽어야만 드러난다.
     */
    @Test
    @DisplayName("목표11 — idle·confirm_at 경과 회차의 강제 확정은 201 이고 fallback_used 가 실제로 true 로 저장된다")
    void 강제_확정하면_201_이고_route_version_이_fallback_used_true_로_생긴다() throws Exception {
        long runId = dueIdleRun();

        mockMvc.perform(post(FORCE_CONFIRM.formatted(runId))
                        .header("Authorization", 메인관리자_토큰())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"기사 무응답으로 콘솔 강제 확정\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.run_id").value(runId))
                .andExpect(jsonPath("$.data.fallback_used").value(true))
                .andExpect(jsonPath("$.data.route_version_id").isNumber())
                .andExpect(jsonPath("$.data.confirmed_at").isNotEmpty());

        동기화한다();
        assertThat(runRepository.findById(runId).orElseThrow().getStatus())
                .as("확정 배치와 같은 결과여야 한다 — 회차 상태가 CONFIRMED 로 전이돼야 한다")
                .isEqualTo(RunStatus.CONFIRMED);

        Long versionId = jdbcTemplate.queryForObject(
                "SELECT current_version_id FROM confirmed_route WHERE run_id = ?", Long.class, runId);
        Boolean fallbackUsed = jdbcTemplate.queryForObject(
                "SELECT fallback_used FROM route_version WHERE id = ?", Boolean.class, versionId);
        assertThat(fallbackUsed)
                .as("이 회차 좌표는 스텁의 장애 마커가 아니다 — 정상 배치였다면 false 였을 값이 " +
                        "강제 확정 때문에 true 가 되어야 한다")
                .isTrue();
    }

    /**
     * §6.14 감사 — {@code audit_log} 에 {@code reason}·{@code fallback_used} 가 실린 행이 남는다.
     *
     * <p>{@code action}·{@code category} 열은 CHECK 제약(닫힌 7종·2종) 때문에 스펙 문면의
     * {@code run.force_confirm} 을 그대로 담지 못한다({@code AuditLog#forRunForceConfirm} 참고) —
     * 그래서 이 시험은 그 문자열을 {@code action}·{@code category} 열이 아니라 {@code detail} 열에서
     * 찾는다. 감사 적재 호출이 조용히 no-op 이 되는 결함은 이 시험이 잡는다(존재 자체를 본다).
     */
    @Test
    @DisplayName("목표11 — 강제 확정은 audit_log 에 reason·fallback_used 를 담은 행을 남긴다")
    void 강제_확정하면_audit_log_에_행이_남는다() throws Exception {
        long runId = dueIdleRun();

        mockMvc.perform(post(FORCE_CONFIRM.formatted(runId))
                        .header("Authorization", 메인관리자_토큰())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"기사 무응답으로 콘솔 강제 확정\"}"))
                .andExpect(status().isCreated());

        동기화한다();
        String detail = jdbcTemplate.queryForObject(
                "SELECT detail::text FROM audit_log WHERE target_type = 'run' AND target_id = ? "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, runId);
        assertThat(detail)
                .as("audit_log 적재가 조용히 빠지면 이 조회 자체가 EmptyResultDataAccessException 이다")
                .contains("run.force_confirm")
                .contains("기사 무응답으로 콘솔 강제 확정")
                .contains("\"fallback_used\": true");
    }

    /**
     * §6.14 전제 — {@code confirm_at} 이 아직 지나지 않은 idle 회차의 강제 확정은
     * {@code 409 RUN_NOT_DUE} 다. 이 단언이 없으면 관리자가 아직 시간이 안 된 회차를 조기에
     * 강제로 확정시킬 수 있다.
     */
    @Test
    @DisplayName("목표11 — confirm_at 이 지나지 않은 idle 회차의 강제 확정은 409 RUN_NOT_DUE 다")
    void confirm_at_이_안_지난_회차는_409_RUN_NOT_DUE_다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long firstStop = fixtures().stop(academyId, "37.560000", "126.970000");
        long lastStop = fixtures().stop(academyId, "37.561000", "126.971000");
        fixtures().route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, firstStop, lastStop);

        // ck_run_confirm_at 제약 — confirm_at 은 반드시 depart_time - 30분이어야 한다. depart_time 을
        // now + 3시간으로 두면 confirm_at 은 now + 2시간30분 — 아직 도래하지 않은 미래 시점이 된다.
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime departTime = now.plusHours(3);
        long runId = fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));

        mockMvc.perform(post(FORCE_CONFIRM.formatted(runId))
                        .header("Authorization", 메인관리자_토큰())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"조기 강제 확정 시도\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_DUE"));

        동기화한다();
        assertThat(runRepository.findById(runId).orElseThrow().getStatus())
                .as("전제를 통과하지 못했으므로 상태가 바뀌면 안 된다")
                .isEqualTo(RunStatus.IDLE);
    }

    /** idle 이 아닌(이미 confirmed) 회차의 강제 확정은 {@code 409 RUN_NOT_IDLE} 다(§6.14). */
    @Test
    @DisplayName("목표11 — idle 이 아닌 회차의 강제 확정은 409 RUN_NOT_IDLE 다")
    void idle_이_아닌_회차는_409_RUN_NOT_IDLE_다() throws Exception {
        long runId = dueIdleRun();
        runConfirmationService.confirmOne(runId);
        동기화한다();
        assertThat(runRepository.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.CONFIRMED);

        mockMvc.perform(post(FORCE_CONFIRM.formatted(runId))
                        .header("Authorization", 메인관리자_토큰())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"이미 확정된 회차를 다시 강제 확정 시도\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_IDLE"));
    }

    /** 미존재 회차는 {@code 404 RUN_NOT_FOUND} 다(§6.14). */
    @Test
    @DisplayName("목표11 — 없는 회차의 강제 확정은 404 RUN_NOT_FOUND 다")
    void 없는_회차는_404_RUN_NOT_FOUND_다() throws Exception {
        mockMvc.perform(post(FORCE_CONFIRM.formatted(999_999_999L))
                        .header("Authorization", 메인관리자_토큰())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"없는 회차\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_FOUND"));
    }

    /** {@code reason} 이 공백뿐이면 {@code 422 VALIDATION_FAILED} 다(§6.14). */
    @Test
    @DisplayName("목표11 — reason 이 공백뿐이면 422 VALIDATION_FAILED 다")
    void reason_이_공백뿐이면_422_VALIDATION_FAILED_다() throws Exception {
        long runId = dueIdleRun();

        mockMvc.perform(post(FORCE_CONFIRM.formatted(runId))
                        .header("Authorization", 메인관리자_토큰())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        동기화한다();
        assertThat(runRepository.findById(runId).orElseThrow().getStatus())
                .as("검증 실패 요청은 상태를 바꾸면 안 된다")
                .isEqualTo(RunStatus.IDLE);
    }

    /**
     * {@code RUN_FORCE_CONFIRM} 은 메인 관리자만 보유한다(FEATURE_SPEC §6.2). 이 단언이 없으면
     * 학원 관계자가 다른 학원의 회차까지 강제로 확정시킬 수 있게 되고, 그것은 §1.5 격리 예외
     * 구역이 조용히 넓어지는 형태다.
     */
    @Test
    @DisplayName("목표11 — 메인 관리자가 아닌 계정의 강제 확정은 403 이다")
    void 메인_관리자가_아닌_계정의_강제_확정은_403_이다() throws Exception {
        long runId = dueIdleRun();
        String staffToken = "Bearer " + tokenProvider.createAccessToken(2L, 1L, Role.STAFF, AccountStatus.ACTIVE);

        mockMvc.perform(post(FORCE_CONFIRM.formatted(runId))
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"관계자의 강제 확정 시도\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private String 메인관리자_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(1L, null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);
    }

    private void 동기화한다() {
        entityManager.flush();
        entityManager.clear();
    }
}
