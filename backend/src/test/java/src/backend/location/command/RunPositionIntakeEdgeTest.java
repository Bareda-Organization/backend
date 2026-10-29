package src.backend.location.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.auth.TestTokens.토큰;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.global.websocket.WebSocketBroadcastGateway;
import src.backend.location.infrastructure.RunPositionStore;
import src.backend.run.controller.DriverRunFixtures;

/**
 * 위치 송신 경계 — 커밋 뒤 방송이 실패해도 송신은 성공으로 답하고, 겹쳐 온 두 송신은 이력 행을 한 건만 남기며, 판정 뒤 끝난 회차에는 행을
 * 남기지 않는다. 실제 커밋을 남기므로 {@code @Transactional} 을 쓰지 않고 만든 행을 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RunPositionIntakeEdgeTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RunPositionStore runPositionStore;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoSpyBean
    private WebSocketBroadcastGateway gateway;

    @MockitoSpyBean
    private PositionIntakeGuard guard;

    private final List<Long> academyIds = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void 기록을_비운다() {
        reset(gateway, guard);
    }

    @AfterEach
    void 뒷정리한다() {
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
    void 커밋_뒤_방송이_던져도_송신은_204_이고_이력_행은_남는다() throws Exception {
        MovingRun run = 움직이는_회차를_만든다();
        doThrow(new IllegalStateException("broker down")).when(gateway).broadcast(eq("position"), eq(run.runId()),
                any(), any());

        위치를_보낸다(run, "37.500000", OffsetDateTime.now().minusSeconds(3)).andExpect(status().isNoContent());

        assertThat(행_수(run)).isEqualTo(1);
    }

    @Test
    void 겹쳐_온_두_송신은_둘_다_204_이고_이력_행과_방송은_먼저_커밋된_한_건만_남고_나머지는_too_frequent_로_센다() throws Exception {
        MovingRun run = 움직이는_회차를_만든다();
        CountDownLatch firstPassedGuard = new CountDownLatch(1);
        CountDownLatch firstDone = new CountDownLatch(1);
        AtomicInteger guardCalls = new AtomicInteger();
        // 두 요청이 모두 중복 판정을 통과한 뒤 적재로 넘어가게 한다 — 두 번째 요청은 첫 요청이 커밋될 때까지 판정 직후에 선다.
        // 두 번째 요청은 첫 요청이 판정을 통과한 뒤에 들어오므로 수신 시각이 같거나 더 늦다
        doAnswer(invocation -> {
            Object dropped = invocation.callRealMethod();
            if (guardCalls.incrementAndGet() == 1) {
                firstPassedGuard.countDown();
            } else {
                assertThat(firstDone.await(15, TimeUnit.SECONDS)).isTrue();
            }
            return dropped;
        }).when(guard).shouldDrop(eq(run.runId()), any(), any());
        double droppedBefore = 버린_횟수("too_frequent");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> {
                보낸다(run, "37.500000", OffsetDateTime.now().minusSeconds(3));
                firstDone.countDown();
            });
            Future<?> second = pool.submit(() -> {
                assertThat(firstPassedGuard.await(15, TimeUnit.SECONDS)).isTrue();
                보낸다(run, "37.510000", OffsetDateTime.now().minusSeconds(2));
                return null;
            });
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(행_수(run)).as("겹친 두 송신 중 먼저 커밋된 하나만 이력 행을 남긴다").isEqualTo(1);
        verify(gateway, times(1)).broadcast(eq("position"), eq(run.runId()), any(), any());
        assertThat(버린_횟수("too_frequent") - droppedBefore).as("적재하지 않은 송신은 too_frequent 로 센다").isEqualTo(1.0);
    }

    @Test
    void 수신_시각이_더_늦은_행이_이미_있어도_다음_위치는_적재되고_Redis_와_방송이_따라온다() throws Exception {
        MovingRun run = 움직이는_회차를_만든다();
        // 시계가 빠른 다른 인스턴스가 남긴 행 — 이 요청의 수신 시각보다 30초 뒤다
        jdbcTemplate.update("INSERT INTO run_position (run_id, lat, lng, recorded_at, received_at) "
                + "VALUES (?, 37.4, 127.0, now(), now() + interval '30 seconds')", run.runId());

        위치를_보낸다(run, "37.500000", OffsetDateTime.now().minusSeconds(1)).andExpect(status().isNoContent());

        assertThat(행_수(run)).as("수신 시각이 더 늦은 행은 이 위치를 막지 못한다").isEqualTo(2);
        verify(gateway, times(1)).broadcast(eq("position"), eq(run.runId()), any(), any());
        assertThat(runPositionStore.find(run.runId())).as("Redis 최신 좌표도 갱신된다").isPresent();
    }

    @Test
    void 중복_판정_뒤_끝난_회차에는_행도_방송도_남기지_않는다() throws Exception {
        MovingRun run = 움직이는_회차를_만든다();
        DriverRunFixtures fixtures = DriverRunFixtures.of(applicationContext);
        doAnswer(invocation -> {
            Object dropped = invocation.callRealMethod();
            fixtures.finishRun(run.runId(), OffsetDateTime.now());
            return dropped;
        }).when(guard).shouldDrop(eq(run.runId()), any(), any());

        위치를_보낸다(run, "37.500000", OffsetDateTime.now().minusSeconds(3))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_MOVING"));

        assertThat(행_수(run)).isZero();
        verify(gateway, never()).broadcast(eq("position"), eq(run.runId()),
                any(), any());
    }

    private void 보낸다(MovingRun run, String lat, OffsetDateTime recordedAt) {
        try {
            위치를_보낸다(run, lat, recordedAt);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private org.springframework.test.web.servlet.ResultActions 위치를_보낸다(MovingRun run, String lat,
            OffsetDateTime recordedAt) throws Exception {
        return mockMvc.perform(post("/api/v1/runs/" + run.runId() + "/position")
                .header("Authorization", 토큰(tokenProvider, run.driverAccountId(), run.academyId(), Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lat\": %s, \"lng\": 127.001000, \"recorded_at\": \"%s\"}".formatted(lat, recordedAt)));
    }

    private double 버린_횟수(String reason) {
        Counter counter = meterRegistry.find("schoolbus.location.position.dropped").tag("reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private int 행_수(MovingRun run) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM run_position WHERE run_id = ?", Integer.class,
                run.runId());
    }

    private record MovingRun(long academyId, long runId, long driverAccountId) {
    }

    private MovingRun 움직이는_회차를_만든다() {
        DriverRunFixtures fixtures = DriverRunFixtures.of(applicationContext);
        long academyId = fixtures.academy();
        academyIds.add(academyId);
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = OffsetDateTime.now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        fixtures.startRun(runId, OffsetDateTime.now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사",
                OffsetDateTime.now());
        return new MovingRun(academyId, runId, driverAccountId);
    }
}
