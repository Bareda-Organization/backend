package src.backend.run.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.annotation.Import;

import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.entity.RiderStatus;
import src.backend.run.event.RunEndedEvent;
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
import src.backend.routing.entity.Waypoint;
import src.backend.routing.repository.WaypointRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import testsupport.clock.FixedClock20300401Config;

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
@RecordApplicationEvents
@Import(FixedClock20300401Config.class)
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

    @Autowired
    private WaypointRepository waypointRepository;

    @Autowired
    private ApplicationEvents applicationEvents;


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
    @DisplayName("BR-042 — 취소된 회차는 창 안이어도 시작할 수 없다(409 RUN_CANCELED)")
    void 취소된_회차는_시작할_수_없다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now().plusMinutes(9);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        jdbcTemplate.update("UPDATE run SET canceled_at = now() WHERE id = ?", runId);

        mockMvc.perform(post("/api/v1/runs/" + runId + "/start").header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("RUN_CANCELED"));

        assertThat(회차_시작시각(runId)).as("취소된 운행의 시작 알림이 학부모에게 나가면 안 된다").isNull();
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

        // R13 — 학부모·학생 갈래는 studentId·studentName 을 채우고, 관계자 갈래는 회차 전체(다수 학생)를
        // 가리키므로 null 로 남는다(docs/archive/rounds/be-rounds-r5-r14.md §8.16 목표 3·5).
        Long parentLegStudentId = jdbcTemplate.queryForObject(
                "SELECT student_id FROM notification_log WHERE type = 'run_started' AND recipient_role = 'parent' "
                        + "AND dedup_key LIKE ?",
                Long.class, "run_started:" + runId + ":%");
        assertThat(parentLegStudentId).as("보호자 행의 student_id 가 채워진다").isEqualTo(studentId);

        Long staffLegStudentId = jdbcTemplate.queryForObject(
                "SELECT student_id FROM notification_log WHERE type = 'run_started' AND recipient_role = 'staff' "
                        + "AND dedup_key LIKE ?",
                Long.class, "run_started:" + runId + ":%");
        assertThat(staffLegStudentId).as("관계자 행은 회차 전체를 알리므로 student_id 가 null 로 남는다").isNull();

        // R14 목표 5 — 관계자 알림에도 호차가 채워진다(Run → Bus 조회).
        String busNo = jdbcTemplate.queryForObject("SELECT bus_no FROM bus WHERE id = ?", String.class, busId);
        String staffLegBusNo = jdbcTemplate.queryForObject(
                "SELECT bus_no FROM notification_log WHERE type = 'run_started' AND recipient_role = 'staff' "
                        + "AND dedup_key LIKE ?",
                String.class, "run_started:" + runId + ":%");
        assertThat(staffLegBusNo).as("관계자 run_started 알림에 호차가 채워진다").isEqualTo(busNo);
    }

    @Test
    @DisplayName("absent 학생(①구간 OFF·다른 버스로 이동)의 보호자에게는 run_started 가 가지 않는다(§9.4 학부모 알림 부재)")
    void 미등원_학생의_보호자에게는_운행_시작_알림이_가지_않는다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long stopId = fixtures.stop(academyId, "37.560000", "126.970000");
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long studentId = fixtures.student(academyId, "쉬는학생");
        fixtures.guardianOf(academyId, studentId, "학부모1", now());
        fixtures.rider(runId, studentId, stopId, RiderStatus.ABSENT, now());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/start").header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk());

        entityManager.flush();
        assertThat(알림_행수(runId, "run_started", "parent")).isZero();
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
        long runStopId = fixtures.runStopForStop(versionId, stopId, 1, now());

        // 동승자(배치와 무관하게 역할 자체가 틀린 경우) — DRIVER_ONLY, run_stop 은 건드려지지 않는다
        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + runStopId + "/arrive")
                .header("Authorization", 토큰(999_999L, academyId, Role.ESCORT)))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("DRIVER_ONLY"));
        assertThat(도착시각(versionId, stopId)).as("동승자 호출은 도착 시각을 건드리면 안 된다").isNull();

        // 배치된 기사 — 성공. 경로의 {stopId} 는 run_stop.id 다(Ruling 327)
        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + runStopId + "/arrive")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk());
        entityManager.flush();
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
        long runStopId = fixtures.runStopForStop(versionId, stopId, 1, now());

        // 같은 학원 소속 기사 계정이지만(역할은 맞다) 이 회차에는 배치되지 않았다 — 다른 회차 계정과
        // 갈리지 않도록 반드시 같은 학원 계정을 쓴다(학원 불일치가 먼저 걸리면 인가 판정 자체를 못 본다).
        long unassignedDriverAccountId = fixtures.unassignedManager(academyId, ManagerRole.DRIVER, "미배치기사");

        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + runStopId + "/arrive")
                .header("Authorization", 토큰(unassignedDriverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("FORBIDDEN"));
        assertThat(도착시각(versionId, stopId)).as("배치되지 않은 기사 호출은 도착 시각을 건드리면 안 된다").isNull();
    }

    // ── goal 9 — 등원 최종 지점 전원 자동 하차 ────────────────────────────

    @Test
    @DisplayName("목표9 · BR-002 — 등원은 마지막 승차지가 아니라 학원 항목 도착에서 전원 자동 하차·종료된다(Ruling 327)")
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
        long runStop1 = fixtures.runStopForStop(versionId, stop1, 1, now());
        long runStop2 = fixtures.runStopForStop(versionId, stop2, 2, now());
        long academyStop = fixtures.runStopForDestination(versionId, 3);

        long student1 = fixtures.studentWithAccount(academyId, "학생1");
        fixtures.guardianOf(academyId, student1, "학부모1", now());
        fixtures.rider(runId, student1, stop1, RiderStatus.BOARDED, now());
        long student2 = fixtures.studentWithAccount(academyId, "학생2");
        fixtures.guardianOf(academyId, student2, "학부모2", now());
        fixtures.rider(runId, student2, stop2, RiderStatus.BOARDED, now());

        // 첫 정차지 — 최종이 아니라 하차가 없다
        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + runStop1 + "/arrive")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.is_final").value(false));

        // 마지막 승차지 — 일반 도착이다. 학원까지 남은 구간이 있고, 이 승차지 학생의 승차 처리가 계속 가능해야 한다
        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + runStop2 + "/arrive")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.is_final").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.run_status").value("moving"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.next_stop.stop_id").value((int) academyStop))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.next_stop.stop_name").value(DriverRunFixtures.ACADEMY_NAME));
        entityManager.flush();
        assertThat(라이더_상태(runId, student1)).as("학원 도착 전에는 자동 하차가 없다").isEqualTo("boarded");

        // 학원 항목 — 전원 자동 하차 + 종료
        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + academyStop + "/arrive")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.is_final").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.run_status").value("finished"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.auto_alighted_count").value(2));
        // BR-037 — 등원 종료도 run_ended 의 재료(RunEndedEvent)를 낸다. 학원 도착에서 자동 하차한 인원을 싣는다.
        assertThat(applicationEvents.stream(RunEndedEvent.class))
                .singleElement().extracting(RunEndedEvent::autoAlightedCount).isEqualTo(2L);

        entityManager.flush();
        assertThat(라이더_상태(runId, student1)).isEqualTo("alighted");
        assertThat(라이더_상태(runId, student2)).isEqualTo("alighted");
        assertThat(알림_행수(runId, "alighting", "parent")).as("자동 하차 인원 수와 정확히 같아야 한다").isEqualTo(2);

        // R13 — 자동 하차 alighting 도 studentId·studentName 을 채운다(docs/archive/rounds/be-rounds-r5-r14.md §8.16 목표 3).
        List<Long> alightedStudentIds = jdbcTemplate.queryForList(
                "SELECT student_id FROM notification_log WHERE type = 'alighting' AND recipient_role = 'parent' "
                        + "AND dedup_key LIKE ?",
                Long.class, "alighting:" + runId + ":%");
        assertThat(alightedStudentIds).as("두 학생의 student_id 가 모두 채워진다")
                .containsExactlyInAnyOrder(student1, student2);
    }

    /**
     * R15-T3 후속(조율자 지적) — 등원 최종 지점(stop2, 마지막 승차지)에서 <b>그 지점에 배정된
     * 학생이</b> 도착 처리 시점에 이미 승차해 있으면, 그 정차지의 강제 출발 적용이
     * {@code alightAllBoarded} 보다 <b>먼저</b> 일어나야 한다 — 최종 지점은 다음 {@code arrive}
     * 호출이 없어 goal7 의 일반 폴백(다음 도착 시 이전 정차지 강제)을 받지 못하는 <b>유일한
     * 정차지</b>이기 때문이다. 순서가 거꾸로면 이 학생의 확정 결과가 이미 ALIGHTED 로 바뀐 뒤라
     * "하차"로 잘못 나가고, {@code RunAutoAlightedEvent} 의 ALIGHTING 과 중복까지 된다.
     */
    @Test
    @DisplayName("R15-T3 후속 — 등원 최종 지점 도착 시 그 지점 강제 출발 적용이 자동 하차보다 먼저 일어나 중복·오분류가 없다")
    void 등원_최종지점_도착시_그_지점_강제_출발_적용이_자동하차보다_먼저_일어난다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long stop1 = fixtures.stop(academyId, "37.560000", "126.970000");
        long stop2 = fixtures.stop(academyId, "37.561000", "126.971000");
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        fixtures.startRun(runId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long versionId = fixtures.confirmedRouteWithVersion(runId, now());
        long runStop1 = fixtures.runStopForStop(versionId, stop1, 1, now());
        long runStop2 = fixtures.runStopForStop(versionId, stop2, 2, now());
        long academyStop = fixtures.runStopForDestination(versionId, 3);

        // 최종 정차지(stop2) 에 배정된 학생이 이미 승차해 있다 — 최종 지점은 그 다음 arrive 호출이
        // 없어 goal7 의 "다음 도착 시 이전 정차지 강제" 폴백을 받지 못하는 유일한 정차지다.
        long student2 = fixtures.studentWithAccount(academyId, "학생2");
        fixtures.guardianOf(academyId, student2, "학부모2", now());
        fixtures.rider(runId, student2, stop2, RiderStatus.BOARDED, now());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + runStop1 + "/arrive")
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + runStop2 + "/arrive")
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + academyStop + "/arrive")
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.is_final").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.run_status").value("finished"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.auto_alighted_count").value(1));

        entityManager.flush();
        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT nl.type FROM notification_log nl JOIN run_rider rr ON rr.student_id = nl.student_id "
                        + "WHERE rr.run_id = ? AND nl.recipient_role = 'parent'",
                runId);
        assertThat(rows).as("①BOARDING 1건 + ALIGHTING 1건, 합 2건 — 같은 종류가 중복되면 안 된다")
                .hasSize(2);
        assertThat(rows).extracting(row -> row.get("type")).as("②승차·하차 각각 정확히 1건씩")
                .containsExactlyInAnyOrder("boarding", "alighting");
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
        long runStop1 = fixtures.runStopForStop(versionId, stop1, 1, now());
        long runStop2 = fixtures.runStopForStop(versionId, stop2, 2, now());

        long student1 = fixtures.studentWithAccount(academyId, "학생1");
        fixtures.rider(runId, student1, stop2, RiderStatus.BOARDED, now());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + runStop1 + "/arrive")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + runStop2 + "/arrive")
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
        assertThat(applicationEvents.stream(RunEndedEvent.class)).as("보류는 종료가 아니다").isEmpty();
    }

    @Test
    @DisplayName("BR-015 — 하원 노선 맨 뒤에 경유 지점이 있어도 마지막 하차지 도착이 최종이고 운행이 끝난다")
    void 맨_뒤_경유_지점이_있어도_마지막_하차지_도착이_최종이다() throws Exception {
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
        long runStop1 = fixtures.runStopForStop(versionId, stop1, 1, now());
        long runStop2 = fixtures.runStopForStop(versionId, stop2, 2, now());
        Waypoint waypoint = waypointRepository.save(Waypoint.forRun(runId, "주유소", null, new BigDecimal("37.562000"),
                new BigDecimal("126.972000"), null, driverAccountId, now()));
        fixtures.runStopForWaypoint(versionId, waypoint.getId(), 3);

        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + runStop1 + "/arrive")
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + runStop2 + "/arrive")
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.is_final").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.run_status").value("finished"));
        // BR-037 — 하원 잔류 0명 즉시 종료도 RunEndedEvent 를 낸다(자동 하차 인원 0).
        assertThat(applicationEvents.stream(RunEndedEvent.class))
                .singleElement().extracting(RunEndedEvent::autoAlightedCount).isEqualTo(0L);
    }

    // ── docs/archive/rounds/be-rounds-r15-r21.md §8.23 T3 목표 7(Ruling 308) — 다음 승하차지 도착이 이전 정차지의 출발을 강제한다 ──────

    @Test
    @DisplayName("목표7(Ruling 308) — 위치 신호로 출발 판정이 안 된 이전 정차지도 다음 도착 처리가 강제로 출발시킨다")
    void 다음_정차지_도착이_이전_정차지의_출발을_강제한다() throws Exception {
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
        long runStop1 = fixtures.runStopForStop(versionId, stop1, 1, now());
        long runStop2 = fixtures.runStopForStop(versionId, stop2, 2, now());

        // 하원이라 확정 결과는 "하차" 다 — 탑승 중(boarded)인 채 출발한 학생은 확정된 결과가 없어 알림 대상이 아니다(R51 H1).
        long student1 = fixtures.studentWithAccount(academyId, "학생1");
        fixtures.guardianOf(academyId, student1, "학부모1", now());
        fixtures.rider(runId, student1, stop1, RiderStatus.ALIGHTED, now());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + runStop1 + "/arrive")
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk());
        entityManager.flush();
        assertThat(출발시각(versionId, stop1)).as("①위치 판정 없이는 아직 출발 처리 전이다").isNull();

        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + runStop2 + "/arrive")
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk());

        entityManager.flush();
        assertThat(출발시각(versionId, stop1)).as("②다음 정차지 도착이 이전 정차지를 강제로 출발시킨다").isNotNull();
        assertThat(출발_통지_행수(runId, "alighting", "parent")).as("③출발 확정으로 하차 알림 1건").isEqualTo(1);
    }

    // ── docs/archive/rounds/be-rounds-r15-r21.md §8.23 T3 목표 8(Ruling 312) — 마지막 승하차지는 운행 종료가 출발로 갈음한다 ───────────

    @Test
    @DisplayName("목표8(Ruling 312) — 다음 정차지가 없는 마지막 승하차지도 운행 종료 시 강제로 출발 처리된다")
    void 마지막_승하차지는_운행_종료가_출발로_갈음한다() throws Exception {
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
        long runStop1 = fixtures.runStopForStop(versionId, stop1, 1, now());
        long runStop2 = fixtures.runStopForStop(versionId, stop2, 2, now());

        // stop2 가 마지막이고, 그 탑승자는 이미 하차 처리됐다고 가정한다 — 도착 즉시 종료(stillBoarded=0)되어
        // 다음 정차지가 없으니 목표7 폴백을 받지 못한다. Ruling 312 가 이 구멍을 막는다.
        long student2 = fixtures.studentWithAccount(academyId, "학생2");
        fixtures.guardianOf(academyId, student2, "학부모2", now());
        fixtures.rider(runId, student2, stop2, RiderStatus.ALIGHTED, now());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + runStop1 + "/arrive")
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/stops/" + runStop2 + "/arrive")
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.is_final").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.run_status").value("finished"));

        entityManager.flush();
        assertThat(출발시각(versionId, stop2)).as("①다음 정차지가 없는 마지막 정차지도 종료 시 강제 출발 처리된다")
                .isNotNull();
        assertThat(출발_통지_행수(runId, "alighting", "parent")).as("②마지막 정차지 확정 결과가 통지된다").isEqualTo(1);
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

    @Test
    @DisplayName("ackChanges FORBIDDEN — 같은 학원의 기사이지만 이 회차에 배치되지 않으면 403 FORBIDDEN 이고 확인 처리가 남지 않는다")
    void 배치되지_않은_같은_학원_기사의_ackChanges는_FORBIDDEN_이다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "배치된기사", now());
        fixtures.confirmedRouteWithVersion(runId, now());

        // 같은 학원 소속 기사 계정이지만(역할은 맞다) 이 회차에는 배치되지 않았다 — 다른 회차 계정과
        // 갈리지 않도록 반드시 같은 학원 계정을 쓴다(학원 불일치가 먼저 걸리면 인가 판정 자체를 못 본다).
        long unassignedDriverAccountId = fixtures.unassignedManager(academyId, ManagerRole.DRIVER, "미배치기사");

        mockMvc.perform(post("/api/v1/runs/" + runId + "/ack-changes")
                .header("Authorization", 토큰(unassignedDriverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("FORBIDDEN"));

        entityManager.flush();
        assertThat(배치_확인버전(runId, ManagerRole.DRIVER)).as("배치되지 않은 기사 호출은 확인 처리를 남기면 안 된다").isNull();
    }

    @Test
    @DisplayName("ackChanges FORBIDDEN — 이 회차에 배치된 기사라도 토큰의 역할이 기사·동승자가 아니면 403 FORBIDDEN 이다")
    void 배치된_기사라도_토큰_역할이_기사_동승자가_아니면_ackChanges는_FORBIDDEN_이다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        fixtures.confirmedRouteWithVersion(runId, now());

        // 계정 자체는 이 회차에 배치된 기사이지만, 토큰이 주장하는 역할이 STAFF 다 — 역할 판정
        // (assertAssignedDriverOrEscort 의 switch) 이 배치 조회보다 먼저 걸러야 한다. 배치 조회만으로는
        // 이 사람이 실제로 그 회차의 기사라 통과해 버리므로, 역할 분기 자체가 살아 있는지는 이 테스트만이 잡는다.
        mockMvc.perform(post("/api/v1/runs/" + runId + "/ack-changes")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.STAFF)))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("FORBIDDEN"));

        entityManager.flush();
        assertThat(배치_확인버전(runId, ManagerRole.DRIVER)).as("역할 판정에서 거절됐으면 확인 처리가 남으면 안 된다").isNull();
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

    /** R15-T3(Ruling 308) — 출발 처리 시각. {@code null} 이면 아직 출발 처리되지 않은 것이다. */
    private OffsetDateTime 출발시각(long routeVersionId, long stopId) {
        return jdbcTemplate.queryForObject(
                "SELECT departed_at FROM run_stop WHERE route_version_id = ? AND stop_id = ?", OffsetDateTime.class,
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

    /**
     * R15-T3(Ruling 308) — 출발 시점 통지({@code BoardingNotificationListener#appendStopDeparted})가
     * 쓰는 {@code dedup_key} 는 {@code stop_departed:...} 형태라 {@link #알림_행수} 의 패턴과 다르다.
     * {@code runId} 는 이 회차에 배정된 계정만 걸러 좁힌다(다른 시험이 만든 행과 섞이지 않도록).
     */
    private long 출발_통지_행수(long runId, String type, String recipientRole) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log nl JOIN run_rider rr ON rr.student_id = nl.student_id "
                        + "WHERE rr.run_id = ? AND nl.type = ? AND nl.recipient_role = ? "
                        + "AND nl.dedup_key LIKE 'stop_departed:%'",
                Integer.class, runId, type, recipientRole);
        return count == null ? 0 : count;
    }

    private MvcResult 본문(MvcResult result) {
        return result;
    }
}
