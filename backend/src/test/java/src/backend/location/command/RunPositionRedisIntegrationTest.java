package src.backend.location.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

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
import src.backend.location.dto.RunPositionRedisValue;
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
 * 목표 3(Phase 10 T1) — 위치 송신 한 번으로 {@code run_position} 적재와 Redis 최신 좌표 갱신이 함께
 * 일어나는지 검증한다.
 *
 * <p>{@code @Transactional} 을 <b>의도적으로 쓰지 않는다</b> — Redis 갱신은
 * {@link RunPositionRedisListener} 가 {@code @TransactionalEventListener(AFTER_COMMIT)} 으로 도는데,
 * 테스트가 트랜잭션을 열고 끝에 롤백하면 실제 커밋이 한 번도 일어나지 않아 그 리스너가 영원히 안
 * 돈다({@code DriverRunControllerTest} 와 다른 지점 — 그 쪽은 리스너 발화 여부를 보지 않는다).
 * 대신 {@code BusRegistrationConcurrencyTest} 와 같은 형태로 만든 행을 {@link #뒷정리한다()} 가
 * 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RunPositionRedisIntegrationTest extends RedisTestContainerBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

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

    /** 이 클래스가 만든 학원 id — 뒷정리가 이 값으로만 지운다(다른 좌석·다른 테스트의 행을 건드리지 않는다). */
    private final List<Long> academyIds = new ArrayList<>();

    private DriverRunFixtures fixtures() {
        return new DriverRunFixtures(academyRepository, busRepository, stopRepository, studentRepository,
                accountRepository, managerRepository, assignmentRepository, runRepository, confirmedRouteRepository,
                routeVersionRepository, runStopRepository, runRiderRepository, academyStaffRepository,
                guardianRepository, guardianStudentRepository, changeRequestRepository);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    /** 이 클래스는 실제 커밋을 남기므로 지우는 것도 직접 한다 — FK 순서: run_position → run(계단식 assignment) → manager → account → bus → academy. */
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
        jdbcTemplate.update("DELETE FROM account WHERE academy_id = ANY(?)",
                (Object) academyIds.toArray(new Long[0]));
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id = ANY(?)", (Object) academyIds.toArray(new Long[0]));
        jdbcTemplate.update("DELETE FROM academy WHERE id = ANY(?)", (Object) academyIds.toArray(new Long[0]));
        academyIds.clear();
    }

    @Test
    @DisplayName("목표3 — 위치 송신 한 번으로 run_position 적재와 Redis 최신 좌표 갱신이 함께 일어난다")
    void 위치_송신_한_번으로_DB_적재와_Redis_갱신이_함께_일어난다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        academyIds.add(academyId);
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        fixtures.startRun(runId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());

        BigDecimal lat = new BigDecimal("37.501000");
        BigDecimal lng = new BigDecimal("127.001000");
        OffsetDateTime recordedAt = now().minusSeconds(2);
        String body = """
                {"lat": %s, "lng": %s, "recorded_at": "%s"}
                """.formatted(lat, lng, recordedAt);

        mockMvc.perform(post("/api/v1/runs/" + runId + "/position")
                .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isNoContent());

        // DB 적재 — 이력은 별도 보존 주기라 FK 가 없다(RunPosition 자바독 참고).
        Integer dbCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM run_position WHERE run_id = ? AND lat = ? AND lng = ?", Integer.class, runId,
                lat, lng);
        assertThat(dbCount).as("run_position 이력 행이 함께 적재돼야 한다").isEqualTo(1);

        // Redis 갱신 — AFTER_COMMIT 이 비동기가 아니라 같은 스레드에서 커밋 직후 동기로 돌므로
        // MockMvc 호출이 끝난 시점에는 이미 반영돼 있어야 한다(폴링 불필요).
        Object raw = redisTemplate.opsForValue().get("run:" + runId + ":position");
        assertThat(raw).as("같은 송신에서 Redis 최신 좌표도 갱신돼야 한다").isInstanceOf(RunPositionRedisValue.class);
        RunPositionRedisValue value = (RunPositionRedisValue) raw;
        assertThat(value.lat()).isEqualByComparingTo(lat);
        assertThat(value.lng()).isEqualByComparingTo(lng);
        assertThat(value.recordedAt()).isEqualTo(recordedAt);
    }

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }
}
