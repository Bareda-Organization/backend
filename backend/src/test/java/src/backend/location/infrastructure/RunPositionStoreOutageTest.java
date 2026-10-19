package src.backend.location.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.entity.RunPosition;
import src.backend.location.repository.RunPositionRepository;
import src.backend.routing.repository.RunStopRepository;
import testsupport.redis.Outage;
import testsupport.redis.OutageRedis;
import testsupport.redis.OutageRedisContext;

/**
 * 최신 좌표 보관소가 멈추거나 답하지 않는 동안 — 읽기는 {@code run_position} 최신 행으로 대체해 성공하고(TECH_DECISIONS §14.2), 쓰기는 호출자(위치 수신 리스너)가 삼켜
 * 다음 송신에 맡기도록 예외로 알린다. 서킷이 열린 뒤에는 Redis 를 기다리지 않으므로 호출이 곧바로 끝난다(Ruling 919).
 */
class RunPositionStoreOutageTest {

    private static final long RUN_ID = 7L;

    private static OutageRedis redis;

    @BeforeAll
    static void startRedis() {
        redis = OutageRedis.start();
    }

    @AfterAll
    static void stopRedis() {
        redis.close();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Outage.class)
    @DisplayName("Redis 장애 중 최신 좌표 읽기는 DB 최신 행으로 대체되고, 서킷이 열린 뒤에는 Redis 를 기다리지 않는다")
    void 장애_중_읽기는_DB_로_대체된다(Outage outage) {
        OutageRedisContext.runner(redis).run(context -> {
            RunPositionStore store = storeWithHistoryRow(context.getBean(StringRedisTemplate.class));
            outage.begin(redis);
            try {
                for (int i = 1; i <= OutageRedisContext.CALLS_TO_OPEN + 3; i++) {
                    AtomicReference<RunPositionRedisValue> found = new AtomicReference<>();
                    Duration elapsed = OutageRedisContext.timeOf(() -> found.set(store.find(RUN_ID).orElse(null)));

                    assertThat(found.get()).as("%d번째 읽기가 DB 대체 값을 돌려준다".formatted(i)).isNotNull();
                    assertThat(found.get().lat()).isEqualByComparingTo("37.5");
                    assertThat(elapsed).as("%d번째 읽기 시간".formatted(i))
                            .isLessThan(i <= OutageRedisContext.CALLS_TO_OPEN ? OutageRedisContext.BOUNDED_CALL
                                    : OutageRedisContext.SKIPPED_CALL);
                }
            } finally {
                outage.end(redis);
            }
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Outage.class)
    @DisplayName("Redis 장애 중 최신 좌표 쓰기는 시간 상한 안에 예외로 끝난다 — 위치 수신 리스너가 삼키고 다음 송신에 맡긴다")
    void 장애_중_쓰기는_예외로_끝난다(Outage outage) {
        OutageRedisContext.runner(redis).run(context -> {
            RunPositionStore store = storeWithHistoryRow(context.getBean(StringRedisTemplate.class));
            RunPositionRedisValue value = new RunPositionRedisValue(new BigDecimal("37.6"), new BigDecimal("127.6"),
                    OffsetDateTime.now(), OffsetDateTime.now(), null);
            outage.begin(redis);
            try {
                for (int i = 1; i <= OutageRedisContext.CALLS_TO_OPEN + 3; i++) {
                    long start = System.nanoTime();
                    assertThatThrownBy(() -> store.save(RUN_ID, value)).isInstanceOf(DataAccessException.class);
                    assertThat(Duration.ofNanos(System.nanoTime() - start)).as("%d번째 쓰기 시간".formatted(i))
                            .isLessThan(i <= OutageRedisContext.CALLS_TO_OPEN ? OutageRedisContext.BOUNDED_CALL
                                    : OutageRedisContext.SKIPPED_CALL);
                }
            } finally {
                outage.end(redis);
            }
        });
    }

    /**
     * 얼렸던 Redis 가 풀린 뒤 — 얼린 동안의 쓰기는 Redis 에 닿지 못했으므로 풀린 Redis 에 남은 멈추기 전 값은 낡았을 수 있다. 그 값을 그대로 돌려주면 복귀 직후 몇 초 동안
     * 클라이언트가 멈춘 시간만큼 오래된 좌표를 받는다. 실패한 시각 이전에 쓴 값은 DB 최신 행으로 읽고, 다음 송신이 값을 다시 쓰면 Redis 값으로 돌아온다.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Outage.class, names = "PAUSED")
    @DisplayName("얼렸다 풀린 Redis 가 돌려주는 멈추기 전 좌표는 DB 최신 행으로 대체하고, 다음 송신이 쓴 값부터 Redis 값을 쓴다")
    void 풀린_직후_멈추기_전_값은_DB_최신_행으로_읽는다(Outage outage) {
        OutageRedisContext.runner(redis).run(context -> {
            RunPositionStore store = storeWithHistoryRow(context.getBean(StringRedisTemplate.class));
            CircuitBreaker breaker = context.getBean(CircuitBreakerRegistry.class).circuitBreaker("redis");
            OffsetDateTime beforeOutage = OffsetDateTime.now().minusSeconds(10);
            store.save(RUN_ID, new RunPositionRedisValue(new BigDecimal("38.0"), new BigDecimal("128.0"), beforeOutage,
                    beforeOutage, "멈추기 전 정차지"));
            outage.begin(redis);
            for (int i = 0; i < OutageRedisContext.CALLS_TO_OPEN; i++) {
                OutageRedisContext.timeOf(() -> store.save(RUN_ID, new RunPositionRedisValue(new BigDecimal("38.1"),
                        new BigDecimal("128.1"), OffsetDateTime.now(), OffsetDateTime.now(), null)));
            }
            outage.end(redis);
            awaitClosed(breaker, store);

            assertThat(store.find(RUN_ID).orElseThrow().lat()).as("풀린 직후 읽기는 낡은 Redis 값이 아니라 DB 최신 행").isEqualByComparingTo("37.5");

            OffsetDateTime afterOutage = OffsetDateTime.now();
            store.save(RUN_ID, new RunPositionRedisValue(new BigDecimal("38.2"), new BigDecimal("128.2"), afterOutage,
                    afterOutage, "복귀 뒤 정차지"));
            assertThat(store.find(RUN_ID).orElseThrow().lat()).as("복귀 뒤 송신이 쓴 값부터는 Redis 값").isEqualByComparingTo("38.2");
        });
    }

    private static void awaitClosed(CircuitBreaker breaker, RunPositionStore store) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (breaker.getState() != CircuitBreaker.State.CLOSED && System.nanoTime() < deadline) {
            OutageRedisContext.timeOf(() -> store.find(RUN_ID));
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        assertThat(breaker.getState()).as("서킷이 닫힌 뒤").isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private static RunPositionStore storeWithHistoryRow(StringRedisTemplate template) {
        RunPosition row = mock(RunPosition.class);
        given(row.getRunId()).willReturn(RUN_ID);
        given(row.getLat()).willReturn(new BigDecimal("37.5"));
        given(row.getLng()).willReturn(new BigDecimal("127.5"));
        given(row.getRecordedAt()).willReturn(OffsetDateTime.now());
        given(row.getReceivedAt()).willReturn(OffsetDateTime.now());
        RunPositionRepository repository = mock(RunPositionRepository.class);
        given(repository.findLatestByRunIdIn(any(), any(), any())).willReturn(List.of(row));
        return new RunPositionStore(template, repository, mock(RunStopRepository.class), new SimpleMeterRegistry(), Clock.systemUTC());
    }
}
