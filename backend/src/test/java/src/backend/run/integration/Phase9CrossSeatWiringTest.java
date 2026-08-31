package src.backend.run.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

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
import jakarta.persistence.PersistenceContext;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.controller.DriverRunFixtures;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * Phase 9 통합 좌석 — T1(로스터·노선 조회) · T2(운행 시작·종료) · T3(승하차·미승차) · T4(내비게이션)가
 * 각자 워크트리에서 {@code @MockitoBean} 이나 직접 seed 로 대체했던 서로의 실제 호출 경로를,
 * 병합된 이 워크트리에서 처음으로 진짜 배선 그대로 잇는다.
 *
 * <p>세 시험 모두 그 좌석 자신의 시험(예 {@code RunCompletionServiceTest}·{@code BoardingControllerTest}
 * ·{@code NavigationControllerTest})이 이미 검사하는 <b>내부 로직</b>은 다시 검사하지 않는다 —
 * 여기서 확인하는 것은 오직 <b>실제 HTTP 경로로 그 내부 로직에 실제로 도달하는가</b>다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Phase9CrossSeatWiringTest {

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
    private ConfirmedRouteRepository confirmedRouteRepository;

    @Autowired
    private RouteVersionRepository routeVersionRepository;

    @Autowired
    private RunStopRepository runStopRepository;

    @Autowired
    private RunRiderRepository runRiderRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

    @Autowired
    private ChangeRequestRepository changeRequestRepository;

    @TestConfiguration
    static class FixedClockConfig {

        private static final Instant FIXED = Instant.parse("2031-08-01T03:00:00Z"); // 2031-08-01 12:00 KST

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }
    }

    private DriverRunFixtures fixtures() {
        return new DriverRunFixtures(academyRepository, busRepository, stopRepository, studentRepository,
                accountRepository, managerRepository, assignmentRepository, runRepository, confirmedRouteRepository,
                routeVersionRepository, runStopRepository, runRiderRepository, academyStaffRepository,
                guardianRepository, guardianStudentRepository, changeRequestRepository);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    // ── 1. T2(도착·종료 보류) → T3(하차 PATCH) — 마지막 하차가 실제로 회차를 finished 로 전이한다 ──

    /**
     * absent 학생을 로스터에 섞어 둔다(함정) — 잔여 판정이 {@code BOARDED} 만 세지 않고 "ALIGHTED
     * 가 아닌 전부" 처럼 잘못 넓게 세면, absent 학생이 영원히 잔류로 남아 이 시험이 통과해도
     * 아무것도 검증하지 않는다.
     */
    @Test
    @DisplayName("교차1 — 하원 최종지점 도착으로 종료 보류된 회차는, 마지막 탑승자의 실제 PATCH 하차로 finished 가 된다")
    void 하차_PATCH_가_실제로_회차를_종료시킨다() throws Exception {
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long stop1 = fixtures().stop(academyId, "37.560000", "126.970000");
        long stop2 = fixtures().stop(academyId, "37.561000", "126.971000");
        OffsetDateTime departTime = now();
        long runId = fixtures().confirmedRun(academyId, busId, Direction.FROM_ACADEMY, departTime,
                departTime.minusMinutes(30));
        fixtures().startRun(runId, now());
        long driverAccountId = fixtures().assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long escortAccountId = fixtures().assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", now());
        long versionId = fixtures().confirmedRouteWithVersion(runId, now());
        fixtures().runStopForStop(versionId, stop1, 1, now());
        fixtures().runStopForStop(versionId, stop2, 2, now());

        long absentStudentId = fixtures().student(academyId, "결석학생");
        fixtures().rider(runId, absentStudentId, stop2, RiderStatus.ABSENT, now());
        long boardedStudentId = fixtures().student(academyId, "탑승학생");
        long boardedRiderId = fixtures().rider(runId, boardedStudentId, stop2, RiderStatus.BOARDED, now());

        // T2 — 최종지점 도착, 탑승자 1명이 남아 있어 종료가 보류된다(실제 arrive 경로).
        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + stop1 + "/arrive")
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + stop2 + "/arrive")
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.finish_pending").value(true));
        entityManager.flush();
        assertThat(회차_상태(runId)).as("아직 1명이 탑승 중이라 종료가 보류돼야 한다").isEqualTo("moving");

        // T3 — 마지막 탑승자의 실제 PATCH 하차(@MockitoBean 없이 RunCompletionService 실물 호출).
        mockMvc.perform(patch("/api/v1/runs/" + runId + "/riders/" + boardedRiderId)
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(하차_본문(now())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("alighted"));

        entityManager.flush();
        assertThat(회차_상태(runId)).as("마지막 탑승자가 실제 PATCH 로 하차하는 순간 finished 로 전이돼야 한다")
                .isEqualTo("finished");
        assertThat(회차_종료시각(runId)).isNotNull();
        assertThat(라이더_상태(boardedRiderId)).isEqualTo("alighted");
        assertThat(라이더_상태(runRiderRepository.findAll().stream()
                .filter(r -> r.getRunId() == runId && r.getStudentId() == absentStudentId).findFirst().orElseThrow()
                .getId())).as("absent 학생은 이 하차와 무관하게 그대로 남아야 한다").isEqualTo("absent");
    }

    // ── 2. T2(도착) → T1(노선 조회) — 실제 도착 기록이 current_stop·next_stop 에 그대로 반영된다 ──

    @Test
    @DisplayName("교차2 — 실제 arrive 로 남긴 도착 기록이 GET route 의 current_stop·next_stop 에 그대로 반영된다")
    void arrive_기록이_route_조회의_current_next_에_반영된다() throws Exception {
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long stop1 = fixtures().stop(academyId, "37.500000", "127.000000");
        long stop2 = fixtures().stop(academyId, "37.510000", "127.010000");
        long stop3 = fixtures().stop(academyId, "37.520000", "127.020000");
        OffsetDateTime departTime = now();
        long runId = fixtures().confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        fixtures().startRun(runId, now());
        long driverAccountId = fixtures().assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long versionId = fixtures().confirmedRouteWithVersion(runId, now());
        fixtures().runStopForStop(versionId, stop1, 1, now());
        fixtures().runStopForStop(versionId, stop2, 2, now());
        fixtures().runStopForStop(versionId, stop3, 3, now());

        // T2 — 1번 정차지에 실제로 도착(최종이 아니라 회차는 계속 moving).
        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + stop1 + "/arrive")
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.is_final").value(false));

        // T1 — 같은 회차의 실시간 노선 조회.
        mockMvc.perform(get("/api/v1/runs/" + runId + "/route")
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current_stop.stop_id").value(stop1))
                .andExpect(jsonPath("$.data.next_stop.stop_id").value(stop2));
    }

    // ── 3. T3(미승차 → 정차지 skip) → T4(내비게이션) — 실제로 skip 된 정차지가 내비 결과에서 빠진다 ──

    /**
     * 이미 absent 처리된 학생 + 지금 no_show 처리하는 학생 1명뿐이면(잔여 0명), T3 의 실제 PATCH 가
     * {@code run_stop.change} 를 {@code skipped} 로 전환한다({@code BoardingControllerTest} 와 같은
     * 함정 — absent 를 잔여로 잘못 세면 stop 이 skip 되지 않아 이 시험 자체가 성립하지 않는다).
     */
    @Test
    @DisplayName("교차3 — 실제 미승차 PATCH 로 skip 된 정차지는 GET navigation 결과에서 실제로 빠진다")
    void no_show_로_skip_된_정차지가_navigation_에서_빠진다() throws Exception {
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long stop1 = fixtures().stop(academyId, "37.530000", "127.030000");
        long stop2 = fixtures().stop(academyId, "37.540000", "127.040000");
        OffsetDateTime departTime = now();
        long runId = fixtures().confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        fixtures().startRun(runId, now());
        long driverAccountId = fixtures().assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long escortAccountId = fixtures().assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", now());
        long versionId = fixtures().confirmedRouteWithVersion(runId, now());
        fixtures().runStopForStop(versionId, stop1, 1, now());
        fixtures().runStopForStop(versionId, stop2, 2, now());

        long alreadyAbsentStudentId = fixtures().student(academyId, "이미결석");
        fixtures().rider(runId, alreadyAbsentStudentId, stop1, RiderStatus.ABSENT, now());
        long targetStudentId = fixtures().student(academyId, "미승차대상");
        long targetRiderId = fixtures().rider(runId, targetStudentId, stop1, RiderStatus.WAITING, now());

        // T3 — 실제 미승차 PATCH. stop1 의 잔여가 0명이 되어 run_stop 이 실제로 skipped 로 전환된다.
        mockMvc.perform(patch("/api/v1/runs/" + runId + "/riders/" + targetRiderId)
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(미승차_본문(now())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stop_skipped").value(true));
        entityManager.flush();
        assertThat(정차_change(versionId, stop1)).isEqualTo("skipped");

        // T4 — 같은 회차의 내비게이션 조회. skip 된 stop1 은 빠지고 stop2 만 남아야 한다.
        mockMvc.perform(get("/api/v1/runs/" + runId + "/navigation").param("scope", "remaining")
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_remaining_stops").value(1))
                .andExpect(jsonPath("$.data.destination.stop_id").value(stop2));
    }

    // ── 픽스처 · 호출 도우미 ──────────────────────────────────────────────

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }

    private String 하차_본문(OffsetDateTime occurredAt) {
        return "{\"status\":\"alighted\",\"verify_method\":\"manual\",\"client_key\":\"%s\",\"occurred_at\":\"%s\"}"
                .formatted(UUID.randomUUID(), occurredAt);
    }

    private String 미승차_본문(OffsetDateTime occurredAt) {
        return "{\"status\":\"no_show\",\"verify_method\":\"manual\",\"client_key\":\"%s\",\"occurred_at\":\"%s\"}"
                .formatted(UUID.randomUUID(), occurredAt);
    }

    private String 회차_상태(long runId) {
        return jdbcTemplate.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId);
    }

    private OffsetDateTime 회차_종료시각(long runId) {
        return jdbcTemplate.queryForObject("SELECT finished_at FROM run WHERE id = ?", OffsetDateTime.class, runId);
    }

    private String 라이더_상태(long riderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM run_rider WHERE id = ?", String.class, riderId);
    }

    private String 정차_change(long routeVersionId, long stopId) {
        return jdbcTemplate.queryForObject(
                "SELECT change FROM run_stop WHERE route_version_id = ? AND stop_id = ?", String.class,
                routeVersionId, stopId);
    }
}
