package src.backend.monitoring.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.json.JsonMapper;

import com.jayway.jsonpath.JsonPath;

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
import testsupport.redis.RedisTestContainerBase;

/**
 * §5.18 {@code GET /staff/runs/live} — Phase 13 T1 목표 6·7.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — {@code RunPositionRedisIntegrationTest} 와 같은 이유로,
 * 실제 {@code POST /runs/{runId}/position} 이 {@code AFTER_COMMIT} 리스너로 Redis 를 갱신하려면
 * 실제 커밋이 나야 한다. 뒷정리는 {@link #뒷정리한다()} 가 학원 id 기준으로 직접 지운다 — {@code run}
 * 삭제가 {@code confirmed_route·route_version·run_stop·run_rider·assignment} 전부를
 * {@code ON DELETE CASCADE} 로 함께 지운다({@code V1__init_schema.sql} 직접 확인).
 */
@SpringBootTest
@AutoConfigureMockMvc
class StaffRunLiveControllerTest extends RedisTestContainerBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

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

        // DriverRunFixtures.confirmedRun·idleRun 이 serviceDate 를 2030-04-01 로 고정해 두므로
        // (departTime 인자와 무관) 그 날짜와 맞춰야 한다 — 다른 날짜를 쓰면 조회가 항상 빈 목록이다.
        private static final Instant FIXED = Instant.parse("2030-04-01T03:00:00Z");

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }
    }

    private final List<Long> academyIds = new ArrayList<>();

    /** 정확히 이 클래스가 만든 Redis 키만 지운다 — {@code run:*:position} 전체를 긁으면 동시에 도는
     * 다른 테스트의 최신 좌표까지 지운다(§0 병렬 실행 시 공유 자원 오염 방지). */
    private final List<Long> runIdsForRedisCleanup = new ArrayList<>();

    private DriverRunFixtures fixtures() {
        return new DriverRunFixtures(academyRepository, busRepository, stopRepository, studentRepository,
                accountRepository, managerRepository, assignmentRepository, runRepository, confirmedRouteRepository,
                routeVersionRepository, runStopRepository, runRiderRepository, academyStaffRepository,
                guardianRepository, guardianStudentRepository, changeRequestRepository);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    @AfterEach
    void 뒷정리한다() {
        if (academyIds.isEmpty()) {
            return;
        }
        jdbcTemplate.update("DELETE FROM run_position WHERE run_id IN "
                + "(SELECT id FROM run WHERE academy_id = ANY(?))", (Object) academyIds.toArray(new Long[0]));
        jdbcTemplate.update("DELETE FROM run WHERE academy_id = ANY(?)", (Object) academyIds.toArray(new Long[0]));
        jdbcTemplate.update("DELETE FROM manager WHERE academy_id = ANY(?)",
                (Object) academyIds.toArray(new Long[0]));
        // academy_staff.account_id 는 ON DELETE RESTRICT — account 를 지우기 전에 먼저 지워야 한다
        // (fx.staffAccount 가 만드는 행, V1__init_schema.sql:112).
        jdbcTemplate.update("DELETE FROM academy_staff WHERE academy_id = ANY(?)",
                (Object) academyIds.toArray(new Long[0]));
        jdbcTemplate.update("DELETE FROM account WHERE academy_id = ANY(?)",
                (Object) academyIds.toArray(new Long[0]));
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id = ANY(?)", (Object) academyIds.toArray(new Long[0]));
        // fk_stop_academy 도 ON DELETE RESTRICT — fx.stop 이 만드는 행을 academy 보다 먼저 지운다.
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id = ANY(?)", (Object) academyIds.toArray(new Long[0]));
        jdbcTemplate.update("DELETE FROM academy WHERE id = ANY(?)", (Object) academyIds.toArray(new Long[0]));
        for (Long runId : runIdsForRedisCleanup) {
            stringRedisTemplate.delete("run:" + runId + ":position");
        }
        runIdsForRedisCleanup.clear();
        academyIds.clear();
    }

    /**
     * 목표 6 — {@code moving} 상태 회차만 담고, 정차 중(§5.10 이 다루는 목록 상태)인 회차는 빠지며,
     * §5.18 필드가 전부 채워진다. 실제 {@code POST /runs/{runId}/position} 으로 Redis 최신 좌표를
     * 만든다.
     */
    @Test
    @DisplayName("목표6 — moving 회차만 담고 §5.18 필드가 전부 채워진다")
    void moving_회차만_담고_필드가_전부_채워진다() throws Exception {
        DriverRunFixtures fx = fixtures();
        long academyId = fx.academy();
        academyIds.add(academyId);
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");

        OffsetDateTime departTime = now();
        long movingRunId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        runIdsForRedisCleanup.add(movingRunId);
        fx.startRun(movingRunId, now());
        long versionId = fx.confirmedRouteWithVersion(movingRunId, departTime.minusMinutes(40));
        fx.runStopForStop(versionId, stopId, 1, departTime);
        long driverAccountId = fx.assignedManager(academyId, movingRunId, ManagerRole.DRIVER, "기사1", now());
        fx.assignedManager(academyId, movingRunId, ManagerRole.ESCORT, "동승자1", now());
        long staffAccountId = fx.staffAccount(academyId, "관계자1");

        // 정차 중(idle) 회차 — moving 필터에서 반드시 빠져야 한다(목표 6).
        long idleBusId = fx.bus(academyId);
        fx.idleRun(academyId, idleBusId, Direction.TO_ACADEMY, departTime.plusHours(1));

        BigDecimal lat = new BigDecimal("37.501000");
        BigDecimal lng = new BigDecimal("127.001000");
        String body = """
                {"lat": %s, "lng": %s, "recorded_at": "%s"}
                """.formatted(lat, lng, now().minusSeconds(2));
        mockMvc.perform(post("/api/v1/runs/" + movingRunId + "/position")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isNoContent());

        MvcResult result = mockMvc
                .perform(get("/api/v1/staff/runs/live").header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data.runs[0].run_id").value(movingRunId))
                .andExpect(jsonPath("$.data.runs[0].bus_no").exists())
                .andExpect(jsonPath("$.data.runs[0].direction").exists())
                .andExpect(jsonPath("$.data.runs[0].status").value("moving"))
                .andExpect(jsonPath("$.data.runs[0].position.lat").value(37.501))
                .andExpect(jsonPath("$.data.runs[0].position.lng").value(127.001))
                .andExpect(jsonPath("$.data.runs[0].position.recorded_at").exists())
                .andExpect(jsonPath("$.data.runs[0].current_stop").doesNotExist())
                .andExpect(jsonPath("$.data.runs[0].next_stop").exists())
                .andExpect(jsonPath("$.data.runs[0].progress.done").value(0))
                .andExpect(jsonPath("$.data.runs[0].progress.total").value(1))
                .andExpect(jsonPath("$.data.runs[0].delay_minutes").exists())
                .andExpect(jsonPath("$.data.runs[0].driver_name").value("기사1"))
                .andExpect(jsonPath("$.data.runs[0].escort_name").value("동승자1"))
                .andExpect(jsonPath("$.data.runs[0].last_seen_at").doesNotExist())
                .andReturn();

        // JsonPath 는 숫자를 Integer 로 읽어 Long 인 movingRunId 와 equals 가 어긋난다
        // (StaffDashboardControllerTest 목표5 와 같은 원인) — Number 로 받아 longValue() 로 맞춘다.
        List<Number> rawRunIds = JsonPath.read(본문(result), "$.data.runs[*].run_id");
        List<Long> runIds = rawRunIds.stream().map(Number::longValue).toList();
        assertThat(runIds).hasSize(1).containsExactly(movingRunId);
    }

    /**
     * 목표 7 — 유실(수신 후 {@code STALE_THRESHOLD}=2분 이상 경과) 이면 {@code position=null} 이고
     * {@code last_seen_at} 이 채워진다.
     *
     * <p>{@code POST /runs/{runId}/position} 은 {@code received_at} 을 서버가 호출 시각으로 항상
     * "지금" 으로 찍어 과거로 되돌릴 방법이 HTTP 경로에 없다 — 그래서 Redis 키를 직접 써서 오래된
     * {@code receivedAt} 을 만든다. DB 행이 아니라 Redis 캐시 값이고, 이 시나리오를 프로덕션
     * 코드로는 재현할 수단이 아예 없어 부득이한 선택이다(확신 없는 지점, 보고서 §2).
     */
    @Test
    @DisplayName("목표7 — 2분 이상 유실이면 position=null, last_seen_at 은 채워진다")
    void 유실_2분_이상이면_position_이_null_이고_last_seen_at_은_채워진다() throws Exception {
        DriverRunFixtures fx = fixtures();
        long academyId = fx.academy();
        academyIds.add(academyId);
        long busId = fx.bus(academyId);
        OffsetDateTime departTime = now();
        long runId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        runIdsForRedisCleanup.add(runId);
        fx.startRun(runId, now());
        fx.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사1", now());
        long staffAccountId = fx.staffAccount(academyId, "관계자1");

        OffsetDateTime staleReceivedAt = now().minusMinutes(3);
        String staleValue = JSON_MAPPER.writeValueAsString(new src.backend.location.dto.RunPositionRedisValue(
                new BigDecimal("37.500000"), new BigDecimal("127.000000"), now().minusMinutes(3), staleReceivedAt,
                null));
        stringRedisTemplate.opsForValue().set("run:" + runId + ":position", staleValue);

        mockMvc.perform(get("/api/v1/staff/runs/live").header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs[0].position").doesNotExist())
                .andExpect(jsonPath("$.data.runs[0].last_seen_at").exists());
    }

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
