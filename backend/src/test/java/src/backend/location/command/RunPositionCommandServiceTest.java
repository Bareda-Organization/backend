package src.backend.location.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

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

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.location.event.RunPositionReceivedEvent;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.controller.DriverRunFixtures;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 기사 단말의 위치 송신 API(§4.12, 목표 1·2) — {@code moving} 회차만 성공하고, 인가·상태 판정을
 * 어긴 호출은 저장소를 건드리지 않는다.
 *
 * <p>{@code @Transactional} 을 쓴다({@code DriverRunControllerTest} 와 같은 근거 — 커맨드 서비스의
 * {@code @Transactional} 이 기본 전파라 테스트 트랜잭션에 합류해 끝나면 함께 롤백된다). 그래서 이
 * 클래스는 {@link RunPositionReceivedEvent} 가 실제로 커밋되는지(목표 3, Redis 갱신)는 보지 않는다
 * — 그건 커밋이 필요 없는 이벤트 발행 자체(인자 순서)와, 실제 커밋이 필요한 Redis 갱신을 각각
 * {@code RunPositionReceivedEventTest}·{@code RunPositionRedisIntegrationTest} 로 나눠 검사한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RunPositionCommandServiceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @Autowired
    private RunRepository runRepository;

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

    /**
     * 발행된 이벤트를 커밋 여부와 무관하게 그 자리에서 잡는다 — {@code publishEvent(...)} 호출 자체는
     * 일반 리스너(비-{@code @TransactionalEventListener})라면 트랜잭션 커밋을 기다리지 않고 동기로
     * 도므로, {@code @Transactional} 로 롤백되는 이 테스트 안에서도 호출 인자 순서를 그대로 잡아낼 수
     * 있다.
     */
    @Autowired
    private CapturedEvents capturedEvents;

    @TestConfiguration
    static class TestSupportConfig {

        private static final Instant FIXED = Instant.parse("2030-04-01T03:00:00Z"); // 2030-04-01 12:00 KST

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }

        @Bean
        CapturedEvents capturedEvents() {
            return new CapturedEvents();
        }
    }

    static class CapturedEvents {

        private final List<RunPositionReceivedEvent> events = new java.util.ArrayList<>();

        // RunPositionReceivedEvent 는 ApplicationEvent 를 상속하지 않는 순수 POJO 라
        // ApplicationListener<E extends ApplicationEvent> 로는 받을 수 없다 — @EventListener 는
        // publishEvent(POJO) 가 감싸는 PayloadApplicationEvent 를 자동으로 풀어 페이로드 타입으로
        // 매칭해 주므로 그대로 쓴다.
        @org.springframework.context.event.EventListener
        void onEvent(RunPositionReceivedEvent event) {
            events.add(event);
        }

        List<RunPositionReceivedEvent> events() {
            return events;
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

    // ── goal 1 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("목표1 — moving 회차에 배치된 기사가 위치를 송신하면 204 이고 이력이 적재된다")
    void moving_회차에_위치를_송신하면_204다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        fixtures.startRun(runId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());

        OffsetDateTime recordedAt = now().minusSeconds(3);
        String body = """
                {"lat": 37.501000, "lng": 127.001000, "recorded_at": "%s"}
                """.formatted(recordedAt);

        mockMvc.perform(post("/api/v1/runs/" + runId + "/position")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isNoContent());

        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM run_position WHERE run_id = ?",
                Integer.class, runId);
        assertThat(count).as("run_position 이력이 함께 적재돼야 한다").isEqualTo(1);

        // 호출부 인자 순서 회귀 — recordedAt(요청값)·receivedAt(서버 시각)이 같은 OffsetDateTime 타입이라
        // 이 커맨드 서비스가 실제로 부르는 자리에서 뒤바뀌어도 컴파일은 통과한다(RunPositionReceivedEventTest
        // 는 생성자 자체만 보고 이 호출부는 못 본다).
        assertThat(capturedEvents.events()).hasSize(1);
        RunPositionReceivedEvent event = capturedEvents.events().get(0);
        assertThat(event.recordedAt()).as("recordedAt 은 요청이 보낸 기기 시각이어야 한다").isEqualTo(recordedAt);
        assertThat(event.receivedAt()).as("receivedAt 은 recordedAt 과 달라야 한다(서버 수신 시각)")
                .isNotEqualTo(recordedAt);
    }

    // ── goal 2 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("목표2 — idle 회차에 위치를 송신하면 409 RUN_NOT_MOVING 이고 이력이 남지 않는다")
    void idle_회차는_RUN_NOT_MOVING_이다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now().plusMinutes(9);
        long runId = fixtures.idleRun(academyId, busId, Direction.TO_ACADEMY, departTime);
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/position")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"lat": 37.5, "lng": 127.0, "recorded_at": "%s"}
                        """.formatted(now())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_MOVING"));

        assertRunPositionEmpty(runId);
    }

    @Test
    @DisplayName("목표2 — finished 회차에 위치를 송신하면 409 RUN_NOT_MOVING 이고 이력이 남지 않는다")
    void finished_회차는_RUN_NOT_MOVING_이다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        fixtures.startRun(runId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        Run run = runRepository.findById(runId).orElseThrow();
        run.finish(now());
        runRepository.save(run);

        mockMvc.perform(post("/api/v1/runs/" + runId + "/position")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"lat": 37.5, "lng": 127.0, "recorded_at": "%s"}
                        """.formatted(now())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_MOVING"));

        assertRunPositionEmpty(runId);
    }

    @Test
    @DisplayName("목표2 — 동승자가 위치를 송신하면 403 DRIVER_ONLY 이고 이력이 남지 않는다")
    void 동승자_호출은_DRIVER_ONLY_이다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        fixtures.startRun(runId, now());
        long escortAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", now());

        mockMvc.perform(post("/api/v1/runs/" + runId + "/position")
                .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"lat": 37.5, "lng": 127.0, "recorded_at": "%s"}
                        """.formatted(now())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("DRIVER_ONLY"));

        assertRunPositionEmpty(runId);
    }

    private void assertRunPositionEmpty(long runId) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM run_position WHERE run_id = ?",
                Integer.class, runId);
        assertThat(count).as("거절된 호출은 이력을 남기면 안 된다").isEqualTo(0);
    }

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }
}
