package src.backend.global.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.auth.TestTokens.토큰;
import static testsupport.web.TestResponses.본문;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
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
 * Ruling 894 ⑥ — 멱등 키 비우기 범위를 HTTP 로 본다.
 *
 * <ul>
 *   <li>비상 발신 키는 회차가 끝나고 25시간이 지나 정리 배치가 돌아도 남아, 같은 {@code client_key} 재전송이 새 행 없이 최초 결과를
 *       {@code 200} 으로 돌려준다(키를 비웠다면 종료 회차도 받는 비상 발신이 새 행을 만들어 알림을 다시 보낸다)</li>
 *   <li>비워진 키로 오는 승하차 처리 · 연락 기록 재전송은 종료 회차라 {@code 409 RUN_NOT_MOVING} 이고 새 행이 생기지 않는다</li>
 * </ul>
 *
 * <p>배치는 실제 시각을 쓰므로 회차의 종료 시각을 실제 {@code now} 기준 25시간 전으로 심는다. {@code @Transactional} 시험이라 배치의
 * UPDATE 도 시험이 끝나면 되돌려진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class IdempotencyKeyScopeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private RetentionCleanupScheduler scheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

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

    private DriverRunFixtures fx;

    private long academyId;

    private long runId;

    private long escortAccountId;

    private long riderId;

    private OffsetDateTime departTime;

    @BeforeEach
    void setUp() {
        // 프록시를 벗긴다 — @SchedulerLock(ShedLock)이 락을 잡고 풀 때 시험 트랜잭션을 커밋해, 심은 행(만료된 미승차 케이스 등)이
        // 되돌려지지 않고 남아 다른 시험(DomainEventCounterWiringTest)의 에스컬레이션 집계에 섞인다
        scheduler = AopTestUtils.getUltimateTargetObject(scheduler);
        fx = new DriverRunFixtures(academyRepository, busRepository, stopRepository, studentRepository,
                accountRepository, managerRepository, assignmentRepository, runRepository, confirmedRouteRepository,
                routeVersionRepository, runStopRepository, runRiderRepository, academyStaffRepository,
                guardianRepository, guardianStudentRepository, changeRequestRepository);
        academyId = fx.academy();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        // 25시간 전에 끝난 회차 — 시작·종료 시각은 아래 종료 처리에서 심는다
        departTime = OffsetDateTime.now(clock).minusHours(27);
        runId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        escortAccountId = fx.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", departTime);
        long studentId = fx.student(academyId, "학생");
        riderId = fx.rider(runId, studentId, stopId, RiderStatus.WAITING, departTime);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("후속1 — 회차 종료 25시간 뒤 정리 배치를 거쳐도 같은 client_key 비상 발신 재전송은 새 행 없이 최초 결과를 200 으로 돌려준다")
    void 늦은_비상_재전송은_새_행_없이_최초_결과를_돌려준다() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"type\":\"accident\",\"client_key\":\"" + key + "\",\"lat\":37.5,\"lng\":127.0}";
        MvcResult first = mockMvc.perform(post("/api/v1/runs/" + runId + "/emergency")
                        .header("Authorization", 토큰(tokenProvider, escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        종료한다(OffsetDateTime.now(clock).minusHours(25));

        scheduler.cleanUp();
        MvcResult replay = mockMvc.perform(post("/api/v1/runs/" + runId + "/emergency")
                        .header("Authorization", 토큰(tokenProvider, escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();

        assertThat(JsonPath.<Object>read(본문(replay), "$.data.emergency_id"))
                .isEqualTo(JsonPath.<Object>read(본문(first), "$.data.emergency_id"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM emergency_alert WHERE run_id = ?",
                Integer.class, runId)).as("비상 알림 행 수").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM emergency_alert WHERE client_key = ?::uuid",
                Integer.class, key)).as("키가 비워지지 않았다").isEqualTo(1);
    }

    @Test
    @DisplayName("키가 비워진 뒤 같은 client_key 승하차 PATCH·연락 기록 POST 는 종료 회차라 409 RUN_NOT_MOVING 이고 새 행이 없다")
    void 비워진_키의_재전송은_종료_회차라_409다() throws Exception {
        UUID key = UUID.randomUUID();
        종료한다(OffsetDateTime.now(clock).minusHours(25));
        long historyId = jdbcTemplate.queryForObject("""
                INSERT INTO rider_status_history (run_rider_id, to_status, changed_at, actor_type, client_key)
                VALUES (?, 'boarded', ?, 'escort', ?) RETURNING id""", Long.class, riderId, departTime, key);
        long caseId = jdbcTemplate.queryForObject("""
                INSERT INTO no_show_case (run_rider_id, started_at, expires_at) VALUES (?, ?, ?) RETURNING id""",
                Long.class, riderId, departTime, departTime.plusMinutes(3));
        UUID contactKey = UUID.randomUUID();
        long contactId = jdbcTemplate.queryForObject("""
                INSERT INTO no_show_contact (no_show_case_id, attempt_type, result, attempted_at, attempted_by, client_key)
                VALUES (?, 'call', 'no_answer', ?, ?, ?) RETURNING id""", Long.class, caseId, departTime,
                escortAccountId, contactKey);

        scheduler.cleanUp();

        assertThat(jdbcTemplate.queryForObject("SELECT client_key FROM rider_status_history WHERE id = ?",
                Object.class, historyId)).as("정리가 승하차 이력 키를 비웠다").isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT client_key FROM no_show_contact WHERE id = ?",
                Object.class, contactId)).as("정리가 연락 기록 키를 비웠다").isNull();

        mockMvc.perform(patch("/api/v1/runs/" + runId + "/riders/" + riderId)
                        .header("Authorization", 토큰(tokenProvider, escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"boarded\",\"verify_method\":\"manual\",\"client_key\":\"" + key + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_MOVING"));
        mockMvc.perform(post("/api/v1/runs/" + runId + "/riders/" + riderId + "/no-show-contacts")
                        .header("Authorization", 토큰(tokenProvider, escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attempt_type\":\"call\",\"result\":\"no_answer\",\"client_key\":\"" + contactKey + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_MOVING"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM rider_status_history WHERE run_rider_id = ?",
                Integer.class, riderId)).as("승하차 이력 행 수").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM no_show_contact WHERE no_show_case_id = ?",
                Integer.class, caseId)).as("연락 기록 행 수").isEqualTo(1);
    }

    private void 종료한다(OffsetDateTime finishedAt) {
        fx.startRun(runId, departTime);
        fx.finishRun(runId, finishedAt);
        entityManager.flush();
        entityManager.clear();
    }
}
