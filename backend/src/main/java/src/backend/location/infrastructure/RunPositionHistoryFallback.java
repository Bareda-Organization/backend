package src.backend.location.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.entity.RunPosition;
import src.backend.location.repository.RunPositionRepository;
import src.backend.routing.dto.RunStopNameView;
import src.backend.routing.query.CurrentRunStopResolver;
import src.backend.routing.repository.RunStopRepository;

/**
 * Redis 대신 {@code run_position} 이력의 회차별 최신 행으로 최신 좌표를 채우는 보조 — {@link RunPositionStore} 가
 * Redis 장애(BR-167)나 낡은 값·빈 키를 만났을 때 부른다. 여러 회차는 조회 한 번이다.
 */
final class RunPositionHistoryFallback {

    /** Redis 장애로 최신 좌표를 {@code run_position} 최신 행으로 대체 조회한 횟수를 세는 지표 이름. */
    private static final String FALLBACK_METRIC = "schoolbus.position.fallback";

    /** Redis 대체 조회가 거슬러 올라가는 한계 — 이보다 오래된 위치는 운행 중 회차의 "최신" 이 아니고, 구간이 있어야 읽는 일 파티션이 2개로 묶인다(DA-2). */
    private static final Duration FALLBACK_LOOKBACK = Duration.ofHours(24);

    /** Redis 키가 없을 때 DB 를 읽는 범위 — 위치가 낡았다고 보는 기준과 같다({@code StudentBusPositionQueryService.STALE_THRESHOLD}, 같은 값임을 시험이 본다). 이보다 오래된 행은 어차피 낡은 위치다. */
    static final Duration MISS_LOOKBACK = Duration.ofMinutes(2);

    /** Redis 는 살아 있는데 운행 중 회차의 키가 비어 DB 최신 행으로 채운 읽기 횟수를 세는 지표 이름. */
    private static final String MISS_FALLBACK_METRIC = "schoolbus.position.miss.fallback";

    /** 단말 시계 오차 허용(5분, BR-243)을 덮는 상한 여유 — 이보다 미래의 기록 시각은 수신 시각으로 바뀌어 저장된다. */
    private static final Duration FALLBACK_LOOKAHEAD = Duration.ofMinutes(10);

    private final RunPositionRepository runPositionRepository;

    private final RunStopRepository runStopRepository;

    private final MeterRegistry meterRegistry;

    private final Clock clock;

    RunPositionHistoryFallback(RunPositionRepository runPositionRepository, RunStopRepository runStopRepository,
            MeterRegistry meterRegistry, Clock clock) {
        this.runPositionRepository = runPositionRepository;
        this.runStopRepository = runStopRepository;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * Redis 는 살아 있는데 키가 없다 — 저장하지 않는 Redis 가 다시 시작하면 다음 송신이 키를 다시 쓸 때까지 운행 중 회차의 최신 좌표가 없다. 운행 중 회차의 최신 행을
     * 위치가 낡았다고 보는 기준({@link #MISS_LOOKBACK}) 안에서만 DB 에서 읽는다. 키가 없는 회차가 있을 때만 한 번(회차 수와 무관) 불리고, 행이 없으면 빈 값 그대로다.
     */
    Map<Long, RunPositionRedisValue> fromHistoryForMissing(List<Long> runIds) {
        meterRegistry.counter(MISS_FALLBACK_METRIC).increment();
        OffsetDateTime now = OffsetDateTime.now(clock);
        return withCurrentStopNames(runPositionRepository.findLatestMovingByRunIdIn(runIds, now.minus(MISS_LOOKBACK),
                now.plus(FALLBACK_LOOKAHEAD)));
    }

    /** Redis 실패나 낡은 값 때문에 {@code run_position} 최신 행으로 대체한다. */
    Map<Long, RunPositionRedisValue> fromHistory(List<Long> runIds) {
        meterRegistry.counter(FALLBACK_METRIC).increment();
        OffsetDateTime now = OffsetDateTime.now(clock);
        return withCurrentStopNames(runPositionRepository.findLatestByRunIdIn(runIds, now.minus(FALLBACK_LOOKBACK),
                now.plus(FALLBACK_LOOKAHEAD)));
    }

    /**
     * 이력 행을 Redis 값과 같은 형태로 바꾼다. 현재 정차지 이름은 이력 행에 없어 정차 항목에서 구한다 — 위치 송신이 Redis 값을 쓸 때와
     * 같은 규칙({@link CurrentRunStopResolver})이고, 회차 수와 무관하게 조회 한 번이다. 행이 없으면 읽지 않는다.
     */
    private Map<Long, RunPositionRedisValue> withCurrentStopNames(List<RunPosition> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<RunStopNameView>> stopsByRunId = runStopRepository
                .findStopNamesByRunIdIn(rows.stream().map(RunPosition::getRunId).toList()).stream()
                .collect(Collectors.groupingBy(RunStopNameView::runId));
        Map<Long, RunPositionRedisValue> positions = new HashMap<>();
        for (RunPosition row : rows) {
            String currentStopName = CurrentRunStopResolver.resolve(
                            stopsByRunId.getOrDefault(row.getRunId(), List.of()), RunStopNameView::seq, RunStopNameView::arrivedAt)
                    .map(RunStopNameView::name).orElse(null);
            positions.put(row.getRunId(), new RunPositionRedisValue(row.getLat(), row.getLng(), row.getRecordedAt(),
                    row.getReceivedAt(), currentStopName));
        }
        return positions;
    }

    /** 대체가 한 번도 없는 기동 직후에도 이름이 0 으로 노출되도록 미리 등록한다(BR-167, TECH_DECISIONS §14.2). */
    void registerMetrics() {
        Counter.builder(FALLBACK_METRIC).description("Redis 장애로 최신 좌표를 DB 이력 최신 행으로 대체 조회한 횟수")
                .register(meterRegistry);
        Counter.builder(MISS_FALLBACK_METRIC).description("Redis 는 살아 있는데 운행 중 회차의 키가 비어 DB 최신 행으로 채운 읽기 횟수")
                .register(meterRegistry);
    }
}
