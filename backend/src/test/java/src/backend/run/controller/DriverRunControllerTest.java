package src.backend.run.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

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
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 기사·동승자 단말의 회차 운행 조작 API(§4.4·§4.5·§4.11) — Phase 9 T2 목표 1(±10분 창) · 2(운행 시작
 * 알림 3종) · 3(미결 변경 요청 즉시 종결) · 5(기사 전용 인가) · 9(등원 최종 지점 전원 자동 하차) ·
 * 10(하원 최종 지점 미하차 잔류 시 종료 보류)을 검증한다.
 *
 * <p>{@code @Transactional} 을 쓴다({@code StaffRunControllerTest} 와 같은 근거) — 이 컨트롤러가
 * 부르는 커맨드 서비스(예 {@code ChangeRequestAutoRejectionPersistence.autoRejectOne})의
 * {@code @Transactional} 은 기본 전파(REQUIRED)라 테스트 트랜잭션에 그대로 합류하고, 테스트가 끝나면
 * 함께 롤백된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DriverRunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

        private static final Instant FIXED = Instant.parse("2030-04-01T03:00:00Z"); // 2030-04-01 12:00 KST

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

    // ── goal 1 — 출발 ±10분 창 ──────────────────────────────────────────

    @Test
    @DisplayName("목표1 — 출발 9분 전은 창 안이라 시작이 성공한다")
    void 출발_9분_전은_시작이_성공한다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now().plusMinutes(9);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/start").header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.run_status").value("moving"));

        assertThat(회차_시작시각(runId)).isNotNull();
    }

    @Test
    @DisplayName("목표1 급소 — 출발 11분 전은 창 밖이라 403 이고 시작 시각이 기록되지 않는다")
    void 출발_11분_전은_창_밖이라_거부된다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now().plusMinutes(11);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/start").header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("START_WINDOW_CLOSED"));

        assertThat(회차_시작시각(runId)).as("창 밖이면 started_at 이 기록되면 안 된다").isNull();
    }

    @Test
    @DisplayName("목표1 반대편 경계 — 출발 9분 후는 창 안이라 시작이 성공한다")
    void 출발_9분_후는_시작이_성공한다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now().minusMinutes(9);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/start").header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.run_status").value("moving"));

        assertThat(회차_시작시각(runId)).isNotNull();
    }

    @Test
    @DisplayName("목표1 반대편 경계 급소 — 출발 11분 후는 창 밖이라 403 이고 시작 시각이 기록되지 않는다")
    void 출발_11분_후는_창_밖이라_거부된다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now().minusMinutes(11);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/start").header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("START_WINDOW_CLOSED"));

        assertThat(회차_시작시각(runId)).as("창 밖이면 started_at 이 기록되면 안 된다").isNull();
    }

    @Test
    @DisplayName("목표1 경계값 — 정확히 출발 10분 후(창의 끝)는 양끝 포함이라 시작이 성공한다")
    void 출발_정확히_10분_후_경계는_시작이_성공한다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now().minusMinutes(10);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/start").header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.run_status").value("moving"));

        assertThat(회차_시작시각(runId)).isNotNull();
    }

    @Test
    @DisplayName("목표1 경계값 — 정확히 출발 10분 전(창의 시작)은 양끝 포함이라 시작이 성공한다")
    void 출발_정확히_10분_전_경계는_시작이_성공한다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now().plusMinutes(10);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/start").header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.run_status").value("moving"));

        assertThat(회차_시작시각(runId)).isNotNull();
    }

    // ── goal 2 — 운행 시작 알림 3종 ──────────────────────────────────────

    @Test
    @DisplayName("목표2 — 운행 시작 시 관계자·보호자·학생 앞으로 run_started 알림이 각 1건씩 적재된다")
    void 운행_시작하면_세_수신자_범주에_알림이_적재된다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long stopId = fixtures.stop(academyId, "37.560000", "126.970000");
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        fixtures.staffAccount(academyId, "관계자1");
        long studentId = fixtures.studentWithAccount(academyId, "학생1");
        fixtures.guardianOf(academyId, studentId, "학부모1", now());
        fixtures.rider(runId, studentId, stopId, RiderStatus.WAITING, now());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/start").header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk());

        entityManager.flush();
        assertThat(알림_행수(runId, "run_started", "staff")).as("재직 관계자 1명").isEqualTo(1);
        assertThat(알림_행수(runId, "run_started", "parent")).as("보호자 1명").isEqualTo(1);
        assertThat(알림_행수(runId, "run_started", "student")).as("계정 연결된 학생 1명").isEqualTo(1);
    }

    // ── goal 3 — 미결 변경 요청 즉시 종결 ─────────────────────────────────

    @Test
    @DisplayName("목표3 — 운행을 시작하면 그 회차의 미결 변경 요청이 폴링 없이 즉시 자동거절된다")
    void 운행_시작하면_미결_변경요청이_즉시_자동거절된다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long studentId = fixtures.student(academyId, "학생1");
        long parentAccountId = fixtures.guardianOf(academyId, studentId, "학부모1", now());
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long changeRequestId = fixtures.pendingChangeRequest(academyId, runId, studentId, parentAccountId,
                now().minusMinutes(10), departTime);

        mockMvc.perform(post("/api/v1/runs/" + runId + "/start").header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk());

        entityManager.flush();
        String status = jdbcTemplate.queryForObject("SELECT status FROM change_request WHERE id = ?", String.class,
                changeRequestId);
        assertThat(status).as("폴링 틱을 기다리지 않고 시작 트랜잭션 안에서 즉시 종결돼야 한다").isEqualTo("auto_rejected");
    }

    // ── goal 5 — 기사 전용 인가 ───────────────────────────────────────────

    @Test
    @DisplayName("목표5 — 동승자는 도착 처리를 할 수 없고(403), 배치된 기사만 성공한다")
    void 도착_처리는_배치된_기사만_할_수_있다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long stopId = fixtures.stop(academyId, "37.560000", "126.970000");
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        fixtures.startRun(runId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long versionId = fixtures.confirmedRouteWithVersion(runId, now());
        fixtures.runStopForStop(versionId, stopId, 1, now());

        // 동승자(배치와 무관하게 역할 자체가 틀린 경우) — DRIVER_ONLY, run_stop 은 건드려지지 않는다
        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + stopId + "/arrive")
                .header("Authorization", 토큰(999_999L, academyId, Role.ESCORT)))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("DRIVER_ONLY"));
        assertThat(도착시각(versionId, stopId)).as("동승자 호출은 도착 시각을 건드리면 안 된다").isNull();

        // 배치된 기사 — 성공
        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + stopId + "/arrive")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk());
        assertThat(도착시각(versionId, stopId)).as("기사 호출은 도착 시각을 남겨야 한다").isNotNull();
    }

    @Test
    @DisplayName("목표5 FORBIDDEN — 같은 학원의 기사이지만 이 회차에 배치되지 않으면 403 FORBIDDEN 이다(DRIVER_ONLY 아님)")
    void 배치되지_않은_같은_학원_기사는_FORBIDDEN_이다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long stopId = fixtures.stop(academyId, "37.560000", "126.970000");
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        fixtures.startRun(runId, now());
        fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "배치된기사", now());
        long versionId = fixtures.confirmedRouteWithVersion(runId, now());
        fixtures.runStopForStop(versionId, stopId, 1, now());

        // 같은 학원 소속 기사 계정이지만(역할은 맞다) 이 회차에는 배치되지 않았다 — 다른 회차 계정과
        // 갈리지 않도록 반드시 같은 학원 계정을 쓴다(학원 불일치가 먼저 걸리면 인가 판정 자체를 못 본다).
        long unassignedDriverAccountId = fixtures.unassignedManager(academyId, ManagerRole.DRIVER, "미배치기사");

        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + stopId + "/arrive")
                .header("Authorization", 토큰(unassignedDriverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("FORBIDDEN"));
        assertThat(도착시각(versionId, stopId)).as("배치되지 않은 기사 호출은 도착 시각을 건드리면 안 된다").isNull();
    }

    // ── goal 9 — 등원 최종 지점 전원 자동 하차 ────────────────────────────

    @Test
    @DisplayName("목표9 — 등원 최종 지점에 도착하면 탑승 중이던 전원이 자동 하차하고 회차가 종료된다")
    void 등원_최종지점_도착시_전원_자동하차하고_종료된다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long stop1 = fixtures.stop(academyId, "37.560000", "126.970000");
        long stop2 = fixtures.stop(academyId, "37.561000", "126.971000");
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        fixtures.startRun(runId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long versionId = fixtures.confirmedRouteWithVersion(runId, now());
        fixtures.runStopForStop(versionId, stop1, 1, now());
        fixtures.runStopForStop(versionId, stop2, 2, now());

        long student1 = fixtures.studentWithAccount(academyId, "학생1");
        fixtures.guardianOf(academyId, student1, "학부모1", now());
        fixtures.rider(runId, student1, stop1, RiderStatus.BOARDED, now());
        long student2 = fixtures.studentWithAccount(academyId, "학생2");
        fixtures.guardianOf(academyId, student2, "학부모2", now());
        fixtures.rider(runId, student2, stop2, RiderStatus.BOARDED, now());

        // 첫 정차지 — 최종이 아니라 하차가 없다
        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + stop1 + "/arrive")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.is_final").value(false));

        // 최종 정차지 — 전원 자동 하차 + 종료
        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + stop2 + "/arrive")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.is_final").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.run_status").value("finished"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.auto_alighted_count").value(2));

        entityManager.flush();
        assertThat(라이더_상태(runId, student1)).isEqualTo("alighted");
        assertThat(라이더_상태(runId, student2)).isEqualTo("alighted");
        assertThat(알림_행수(runId, "alighting", "parent")).as("자동 하차 인원 수와 정확히 같아야 한다").isEqualTo(2);
    }

    // ── goal 10 — 하원 최종 지점 미하차 잔류 시 종료 보류 ──────────────────

    @Test
    @DisplayName("목표10 — 하원 최종 지점에 도착해도 미하차 탑승자가 남아 있으면 종료가 보류된다")
    void 하원_최종지점_미하차_잔류시_종료가_보류된다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long stop1 = fixtures.stop(academyId, "37.560000", "126.970000");
        long stop2 = fixtures.stop(academyId, "37.561000", "126.971000");
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.FROM_ACADEMY, departTime,
                departTime.minusMinutes(30));
        fixtures.startRun(runId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long versionId = fixtures.confirmedRouteWithVersion(runId, now());
        fixtures.runStopForStop(versionId, stop1, 1, now());
        fixtures.runStopForStop(versionId, stop2, 2, now());

        long student1 = fixtures.studentWithAccount(academyId, "학생1");
        fixtures.rider(runId, student1, stop2, RiderStatus.BOARDED, now());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + stop1 + "/arrive")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + stop2 + "/arrive")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.is_final").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.run_status").value("moving"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.finish_pending").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.remaining[0].rider_id").exists());

        entityManager.flush();
        assertThat(회차_상태(runId)).isEqualTo("moving");
        assertThat(회차_종료보류(runId)).isTrue();
        assertThat(회차_종료시각(runId)).isNull();
    }

    // ── ackChanges — §4.11 노선 변경 확인 응답 ─────────────────────────────

    @Test
    @DisplayName("ackChanges — 배치된 기사의 확인 응답은 200 이고 Assignment.ack 가 현재 배포 버전으로 갱신된다")
    void ackChanges_는_배치된_기사의_확인을_기록하고_200을_반환한다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long versionId = fixtures.confirmedRouteWithVersion(runId, now());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/ack-changes")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.acked_at").exists());

        entityManager.flush();
        assertThat(배치_확인버전(runId, ManagerRole.DRIVER)).isEqualTo(versionId);
        assertThat(배치_확인시각(runId, ManagerRole.DRIVER)).isNotNull();
    }

    @Test
    @DisplayName("ackChanges — 확정되지 않은(idle) 회차는 RUN_NOT_CONFIRMED 로 거절되고 Assignment.ack 는 그대로 null 이다")
    void 확정되지_않은_회차의_ackChanges는_RUN_NOT_CONFIRMED_이다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now().plusMinutes(9);
        long runId = fixtures.idleRun(academyId, busId, Direction.TO_ACADEMY, departTime);
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/ack-changes")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("RUN_NOT_CONFIRMED"));

        entityManager.flush();
        assertThat(배치_확인버전(runId, ManagerRole.DRIVER)).as("거절됐으면 확인 처리가 남으면 안 된다").isNull();
    }

    // ── 픽스처 · 호출 도우미 ──────────────────────────────────────────────

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }

    private OffsetDateTime 회차_시작시각(long runId) {
        return jdbcTemplate.queryForObject("SELECT started_at FROM run WHERE id = ?", OffsetDateTime.class, runId);
    }

    private OffsetDateTime 회차_종료시각(long runId) {
        return jdbcTemplate.queryForObject("SELECT finished_at FROM run WHERE id = ?", OffsetDateTime.class, runId);
    }

    private String 회차_상태(long runId) {
        return jdbcTemplate.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId);
    }

    private boolean 회차_종료보류(long runId) {
        Boolean value = jdbcTemplate.queryForObject("SELECT finish_pending FROM run WHERE id = ?", Boolean.class,
                runId);
        return value != null && value;
    }

    private OffsetDateTime 도착시각(long routeVersionId, long stopId) {
        return jdbcTemplate.queryForObject(
                "SELECT arrived_at FROM run_stop WHERE route_version_id = ? AND stop_id = ?", OffsetDateTime.class,
                routeVersionId, stopId);
    }

    private String 라이더_상태(long runId, long studentId) {
        return jdbcTemplate.queryForObject("SELECT status FROM run_rider WHERE run_id = ? AND student_id = ?",
                String.class, runId, studentId);
    }

    private Long 배치_확인버전(long runId, ManagerRole role) {
        return jdbcTemplate.queryForObject(
                "SELECT acked_route_version_id FROM assignment WHERE run_id = ? AND role = ?", Long.class, runId,
                role.name().toLowerCase());
    }

    private OffsetDateTime 배치_확인시각(long runId, ManagerRole role) {
        return jdbcTemplate.queryForObject(
                "SELECT acked_at FROM assignment WHERE run_id = ? AND role = ?", OffsetDateTime.class, runId,
                role.name().toLowerCase());
    }

    private long 알림_행수(long runId, String type, String recipientRole) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE type = ? AND recipient_role = ? AND dedup_key LIKE ?",
                Integer.class, type, recipientRole, type + ":" + runId + ":%");
        return count == null ? 0 : count;
    }

    private MvcResult 본문(MvcResult result) {
        return result;
    }
}
