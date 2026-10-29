package src.backend.location.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.auth.TestTokens.토큰;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.global.websocket.WebSocketBroadcastGateway;
import src.backend.location.infrastructure.RunPositionStore;
import src.backend.run.controller.DriverRunFixtures;
import testsupport.clock.AdvanceableClock;
import testsupport.clock.AdvanceableClockConfig;

/**
 * 위치 송신의 중복·과다 수신 차단 — 마지막으로 받아들인 위치보다 새롭지 않은 {@code recorded_at} 이거나 마지막 수신 뒤
 * 1초가 안 지났으면 {@code 204} 로 답하되 이력 행·Redis 쓰기·방송을 하지 않고 {@code schoolbus.location.position.dropped}
 * 로 센다. 마지막으로 받아들인 값은 Redis 최신 좌표 키에서 읽는다. 시계는 시험이 앞으로 밀 수 있는 것을 쓴다.
 *
 * <p>실제 커밋을 남기므로 {@code @Transactional} 을 쓰지 않고 만든 행을 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdvanceableClockConfig.class)
class RunPositionDedupeTest {

    private static final String DROPPED_METER = "schoolbus.location.position.dropped";

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdvanceableClock clock;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private RunPositionStore runPositionStore;

    @MockitoSpyBean
    private WebSocketBroadcastGateway gateway;

    private final List<Long> academyIds = new ArrayList<>();

    @MockitoSpyBean
    private StringRedisTemplate redis;

    @BeforeEach
    void 방송_기록을_비운다() {
        reset(gateway, redis);
    }

    @AfterEach
    void 뒷정리한다() {
        reset(redis);
        clock.reset();
        if (academyIds.isEmpty()) {
            return;
        }
        Object[] ids = { academyIds.toArray(new Long[0]) };
        jdbcTemplate.update("DELETE FROM run_position WHERE run_id IN (SELECT id FROM run WHERE academy_id = ANY(?))", ids);
        jdbcTemplate.update("DELETE FROM run WHERE academy_id = ANY(?)", ids);
        jdbcTemplate.update("DELETE FROM manager WHERE academy_id = ANY(?)", ids);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id = ANY(?)", ids);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id = ANY(?)", ids);
        jdbcTemplate.update("DELETE FROM academy WHERE id = ANY(?)", ids);
        academyIds.clear();
    }

    @Test
    void 이미_받은_recorded_at_을_다시_보내면_204_이고_행도_방송도_늘지_않는다() throws Exception {
        MovingRun run = 움직이는_회차를_만든다();
        OffsetDateTime recordedAt = OffsetDateTime.now(clock).minusSeconds(3);
        위치를_보낸다(run, "37.500000", recordedAt);
        clock.advance(Duration.ofSeconds(5));
        double droppedBefore = 버린_횟수();

        위치를_보낸다(run, "37.510000", recordedAt);

        assertThat(행_수(run)).as("같은 recorded_at 은 새 이력 행을 만들지 않는다").isEqualTo(1);
        verify(gateway, times(1)).broadcast(eq("position"), eq(run.runId()), any(), any());
        assertThat(runPositionStore.find(run.runId()).orElseThrow().lat()).isEqualByComparingTo("37.500000");
        assertThat(버린_횟수() - droppedBefore).as("버린 위치는 세어야 한다").isEqualTo(1.0);
    }

    @Test
    void 마지막으로_받은_것보다_옛_recorded_at_이면_204_이고_행도_방송도_늘지_않는다() throws Exception {
        MovingRun run = 움직이는_회차를_만든다();
        위치를_보낸다(run, "37.500000", OffsetDateTime.now(clock).minusSeconds(2));
        clock.advance(Duration.ofSeconds(5));
        double droppedBefore = 버린_횟수();

        위치를_보낸다(run, "37.510000", OffsetDateTime.now(clock).minusSeconds(20));

        assertThat(행_수(run)).isEqualTo(1);
        verify(gateway, times(1)).broadcast(eq("position"), eq(run.runId()), any(), any());
        assertThat(버린_횟수() - droppedBefore).isEqualTo(1.0);
    }

    @Test
    void 새_recorded_at_이어도_마지막_수신_뒤_1초가_안_지났으면_버리고_1초_뒤에는_받는다() throws Exception {
        MovingRun run = 움직이는_회차를_만든다();
        위치를_보낸다(run, "37.500000", OffsetDateTime.now(clock).minusSeconds(3));
        double droppedBefore = 버린_횟수();

        clock.advance(Duration.ofMillis(400));
        위치를_보낸다(run, "37.510000", OffsetDateTime.now(clock).minusSeconds(2));

        assertThat(행_수(run)).as("0.4초 뒤에 온 위치는 버린다").isEqualTo(1);
        verify(gateway, times(1)).broadcast(eq("position"), eq(run.runId()), any(), any());
        assertThat(버린_횟수() - droppedBefore).isEqualTo(1.0);

        clock.advance(Duration.ofSeconds(2));
        위치를_보낸다(run, "37.520000", OffsetDateTime.now(clock).minusSeconds(1));

        assertThat(행_수(run)).as("1초가 지난 새 위치는 받는다").isEqualTo(2);
        verify(gateway, times(2)).broadcast(eq("position"), eq(run.runId()), any(), any());
        assertThat(runPositionStore.find(run.runId()).orElseThrow().lat()).isEqualByComparingTo("37.520000");
    }

    @Test
    void 마지막_위치를_Redis_에서_못_읽으면_버리지_않고_받아들인다() throws Exception {
        MovingRun run = 움직이는_회차를_만든다();
        OffsetDateTime recordedAt = OffsetDateTime.now(clock).minusSeconds(3);
        위치를_보낸다(run, "37.500000", recordedAt);
        clock.advance(Duration.ofSeconds(5));
        Mockito.doAnswer(invocation -> {
            if (Arrays.stream(Thread.currentThread().getStackTrace())
                    .anyMatch(frame -> frame.getMethodName().equals("findAcceptedInRedis"))) {
                throw new RedisConnectionFailureException("redis down");
            }
            return invocation.callRealMethod();
        }).when(redis).opsForValue();

        위치를_보낸다(run, "37.510000", recordedAt);

        assertThat(행_수(run)).as("Redis 로 판단할 수 없으면 같은 recorded_at 도 받아들인다").isEqualTo(2);
    }

    private void 위치를_보낸다(MovingRun run, String lat, OffsetDateTime recordedAt) throws Exception {
        mockMvc.perform(post("/api/v1/runs/" + run.runId() + "/position")
                .header("Authorization", 토큰(tokenProvider, run.driverAccountId(), run.academyId(), Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lat\": %s, \"lng\": 127.001000, \"recorded_at\": \"%s\"}".formatted(lat, recordedAt)))
                .andExpect(status().isNoContent());
    }

    private int 행_수(MovingRun run) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM run_position WHERE run_id = ?", Integer.class,
                run.runId());
    }

    private double 버린_횟수() {
        return Search.in(meterRegistry).name(DROPPED_METER).counters().stream()
                .mapToDouble(counter -> counter.count()).sum();
    }

    private record MovingRun(long academyId, long runId, long driverAccountId) {
    }

    private MovingRun 움직이는_회차를_만든다() {
        DriverRunFixtures fixtures = DriverRunFixtures.of(applicationContext);
        long academyId = fixtures.academy();
        academyIds.add(academyId);
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = OffsetDateTime.now(clock);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        fixtures.startRun(runId, OffsetDateTime.now(clock));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사",
                OffsetDateTime.now(clock));
        return new MovingRun(academyId, runId, driverAccountId);
    }
}
