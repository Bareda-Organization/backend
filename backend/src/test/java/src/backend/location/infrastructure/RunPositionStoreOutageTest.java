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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.entity.RunPosition;
import src.backend.location.repository.RunPositionRepository;
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

    private static RunPositionStore storeWithHistoryRow(StringRedisTemplate template) {
        RunPosition row = mock(RunPosition.class);
        given(row.getRunId()).willReturn(RUN_ID);
        given(row.getLat()).willReturn(new BigDecimal("37.5"));
        given(row.getLng()).willReturn(new BigDecimal("127.5"));
        given(row.getRecordedAt()).willReturn(OffsetDateTime.now());
        given(row.getReceivedAt()).willReturn(OffsetDateTime.now());
        RunPositionRepository repository = mock(RunPositionRepository.class);
        given(repository.findLatestByRunIdIn(any(), any(), any())).willReturn(List.of(row));
        return new RunPositionStore(template, repository, new SimpleMeterRegistry(), Clock.systemUTC());
    }
}
