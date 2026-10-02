package src.backend.location.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import src.backend.routing.entity.RunStop;
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
import testsupport.clock.FixedClock20300401Config;

/**
 * 기사 단말의 위치 송신 API(§4.12, 목표 1·2) — {@code moving} 회차만 성공하고, 인가·상태 판정을
 * 어긴 호출은 저장소를 건드리지 않는다.
 *
 * <p>{@code @Transactional} 을 쓴다({@code DriverRunControllerTest} 와 같은 근거 — 서비스가 쓰는
 * {@code TransactionTemplate} 이 기본 전파라 테스트 트랜잭션에 합류해 끝나면 함께 롤백된다). 그래서 이
 * 클래스는 {@link RunPositionReceivedEvent} 가 실제로 커밋되는지(목표 3, Redis 갱신)와, 트랜잭션 없이 발행하는
 * {@code fallbackExecution} 경로 · "DB 연결을 반납한 뒤 발행" 을 보지 않는다
 * — 그건 커밋이 필요 없는 이벤트 발행 자체(인자 순서)와, 실제 커밋이 필요한 Redis 갱신을 각각
 * {@code RunPositionReceivedEventTest}·{@code RunPositionRedisIntegrationTest}(T-1) 로 나눠 검사한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FixedClock20300401Config.class)
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

    /** 기록 빈은 컨텍스트 하나를 공유하는 시험 전체에서 같은 인스턴스다 — 시험마다 비워야 건수 단언이 순서에 기대지 않는다. */
    @BeforeEach
    void 이벤트_기록을_비운다() {
        capturedEvents.events().clear();
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

    /**
     * BR-243 · Ruling 379 ② — 단말 시계가 서버와 5분 넘게 어긋난 recorded_at 은 거절하지 않고 서버 수신 시각으로 바꿔
     * 저장한다. 거절하면 시계가 틀어진 기사의 위치가 전부 사라지고, 그대로 두면 대체 조회·보존 정리 기준이 틀어진다.
     * 저장 행 · 방송·Redis 갱신이 읽는 이벤트 세 곳이 모두 바뀐 값을 써야 한다.
     */
    @Test
    @DisplayName("BR-243 — recorded_at 이 서버 시각에서 5분 넘게 미래·과거로 어긋나면 204 이고 수신 시각으로 바꿔 저장한다")
    void 단말_시각이_크게_어긋난_위치는_수신_시각으로_저장한다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        fixtures.startRun(runId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());

        List<OffsetDateTime> skewedValues = List.of(now().plusDays(1), now().plusMinutes(6), now().minusMinutes(6));
        for (OffsetDateTime skewed : skewedValues) {
            mockMvc.perform(post("/api/v1/runs/" + runId + "/position")
                    .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"lat": 37.501000, "lng": 127.001000, "recorded_at": "%s"}
                            """.formatted(skewed)))
                    .andExpect(status().isNoContent());
        }

        List<OffsetDateTime> stored = jdbcTemplate.queryForList(
                "SELECT recorded_at FROM run_position WHERE run_id = ?", OffsetDateTime.class, runId);
        assertThat(stored).as("어긋난 시각이어도 위치는 적재돼야 한다").hasSize(skewedValues.size());
        assertThat(stored).as("저장된 recorded_at 은 전부 서버 수신 시각이어야 한다")
                .allSatisfy(each -> assertThat(each.toInstant()).isEqualTo(now().toInstant()));
        assertThat(capturedEvents.events()).as("Redis 갱신·방송이 읽는 이벤트도 바뀐 값을 실어야 한다")
                .hasSize(skewedValues.size())
                .allSatisfy(event -> assertThat(event.recordedAt().toInstant()).isEqualTo(now().toInstant()));
    }

    /** 위 시험의 짝 — 경계 안쪽(±4분)은 통과한다("항상 거절" 로 구현해도 통과하는 함정 방지). */
    @Test
    @DisplayName("BR-243 — recorded_at 이 서버 시각 ±4분 안이면 원래 값을 그대로 저장한다")
    void 단말_시각이_허용_오차_안이면_원래_값을_저장한다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        fixtures.startRun(runId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());

        List<OffsetDateTime> nearbyValues = List.of(now().plusMinutes(4), now().minusMinutes(4));
        for (OffsetDateTime nearby : nearbyValues) {
            mockMvc.perform(post("/api/v1/runs/" + runId + "/position")
                    .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"lat": 37.501000, "lng": 127.001000, "recorded_at": "%s"}
                            """.formatted(nearby)))
                    .andExpect(status().isNoContent());
        }

        List<OffsetDateTime> stored = jdbcTemplate.queryForList(
                "SELECT recorded_at FROM run_position WHERE run_id = ? ORDER BY id", OffsetDateTime.class, runId);
        assertThat(stored.stream().map(OffsetDateTime::toInstant))
                .as("허용 오차 안의 단말 시각은 그대로 저장돼야 한다")
                .containsExactlyElementsOf(nearbyValues.stream().map(OffsetDateTime::toInstant).toList());
    }

    /**
     * BR-100 — 현재 정차지 이름·다음 ETA 는 위치를 저장한 트랜잭션이 한 번 계산해 이벤트에 싣는다. 이름은
     * 마지막으로 도착한 항목, ETA 는 그 뒤 첫 항목의 계획값이다 — 그 사이 경유 지점이 미도착으로 남아 있어도
     * 이미 지난 것이다(BR-015).
     */
    @Test
    @DisplayName("BR-100 — 이벤트가 현재 정차지 이름과 다음 정차 항목의 ETA 를 싣는다")
    void 이벤트가_현재_정차지_이름과_다음_ETA_를_싣는다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        fixtures.startRun(runId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long versionId = fixtures.confirmedRouteWithVersion(runId, now());
        long firstStopId = fixtures.stop(academyId, "37.500000", "127.000000");
        long firstRunStopId = fixtures.runStopForStop(versionId, firstStopId, 1, now().minusMinutes(5));
        long secondStopId = fixtures.stop(academyId, "37.510000", "127.010000");
        long secondRunStopId = fixtures.runStopForStop(versionId, secondStopId, 2, now().minusMinutes(2));
        OffsetDateTime nextEta = now().plusMinutes(7);
        fixtures.runStopForStop(versionId, fixtures.stop(academyId, "37.520000", "127.020000"), 3, nextEta);
        fixtures.runStopForDestination(versionId, 4);
        // 엔티티로 도착 처리한다 — JDBC 로 바꾸면 같은 트랜잭션의 영속성 컨텍스트가 들고 있는 옛 엔티티가 그대로 읽힌다.
        // 도착한 정차를 둘로 둔다 — "도착 정차 중 seq 최댓값" 이 현재 정차지이고 다음 ETA 는 그 뒤 첫 정차의 것이다(BR-341)
        for (long runStopId : new long[] { firstRunStopId, secondRunStopId }) {
            RunStop arrived = runStopRepository.findById(runStopId).orElseThrow();
            arrived.markArrived(now());
            runStopRepository.saveAndFlush(arrived);
        }
        String secondStopName = jdbcTemplate.queryForObject("SELECT name FROM stop WHERE id = ?", String.class,
                secondStopId);

        mockMvc.perform(post("/api/v1/runs/" + runId + "/position")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"lat": 37.501000, "lng": 127.001000, "recorded_at": "%s"}
                        """.formatted(now().minusSeconds(3))))
                .andExpect(status().isNoContent());

        RunPositionReceivedEvent event = capturedEvents.events().get(0);
        assertThat(event.academyId()).isEqualTo(academyId);
        assertThat(event.currentStopName()).isEqualTo(secondStopName);
        assertThat(event.nextEta()).isEqualTo(nextEta);
    }

    @Test
    @DisplayName("BR-115 — 요청의 speed·heading 이 이력에 저장되고, 컬럼 범위 밖 값은 500 이 아니라 422 다")
    void speed_heading_을_저장하고_범위_밖은_422다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        fixtures.startRun(runId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        String template = """
                {"lat": 37.501000, "lng": 127.001000, "recorded_at": "%s", "speed": %s, "heading": %s}
                """;

        mockMvc.perform(post("/api/v1/runs/" + runId + "/position")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(template.formatted(now().minusSeconds(3), "32.5", "270.0")))
                .andExpect(status().isNoContent());
        java.util.Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT speed, heading FROM run_position WHERE run_id = ?", runId);
        assertThat((java.math.BigDecimal) row.get("speed")).isEqualByComparingTo("32.5");
        assertThat((java.math.BigDecimal) row.get("heading")).isEqualByComparingTo("270.0");

        mockMvc.perform(post("/api/v1/runs/" + runId + "/position")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(template.formatted(now().minusSeconds(1), "1000", "361")))
                .andExpect(status().isUnprocessableEntity());
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
