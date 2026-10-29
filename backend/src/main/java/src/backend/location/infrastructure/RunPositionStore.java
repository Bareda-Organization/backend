package src.backend.location.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;

import src.backend.global.common.logging.RateLimitedWarn;
import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.repository.RunPositionRepository;
import src.backend.routing.repository.RunStopRepository;

/**
 * 회차 최신 좌표 저장소 — Redis 키 {@code run:{runId}:position} 을 읽고 쓰는 유일한 곳이다(BR-098 · BR-099).
 * 위치 수신 리스너가 쓰고, 근접 판정·학부모 위치·관제·비상 신고가 읽는다. 3개 모듈 4개 클래스가 키·형식을 각자 알면
 * 한쪽만 형식이 바뀌는 사고가 난다({@link RunPositionRedisValue} 자바독).
 *
 * <p><b>Redis 장애 대체(BR-167, TECH_DECISIONS §14.2)</b> — {@link #find}·{@link #findAll} 은 Redis 가 실패하면
 * (연결 실패·시간 상한 초과) {@code run_position} 의 회차별 최신 행으로 같은 형태를 돌려준다. 여러 회차는 조회
 * 한 번이다(관제가 회차 수만큼 쿼리를 내지 않게). 대체 값의 {@code currentStopName} 은 이력 행에 없는 값이라 현재 판본 정차 항목에서
 * 위치 송신과 같은 규칙으로 구한다(조회 한 번 더, 회차 수와 무관). <b>근접 판정도 대체한다</b>(Ruling 624) — 건너뛰면 Redis 가 죽은 동안 기사가 도착 처리해 "다음 미도착" 이 넘어간
 * 정차지는 선점 한 번 못 해 도착 임박 알림이 영구히 빠진다. 대체 위치는 {@code recorded_at} 이 지연될 수 있어 300m 안 진입을
 * 늦게 알아챌 수는 있지만 알림이 빠지는 것보다 낫다.
 *
 * <p>인터페이스(spec)로 가르지 않는다 — 최신 좌표를 Redis 에 두는 것은 인스턴스 간 공유 때문이고
 * (ARCHITECTURE §9.5) 바꿀 구현 후보가 없다. 교체 축이 없는 곳에 포트를 씌우지 않는다(ARCHITECTURE §3.2.1).
 * 호출자는 Redis 를 모르고 이 클래스만 안다(CODE_CONVENTIONS 의 Service·Infrastructure 규칙).
 *
 * <p>⚠ 값 형식은 평문 camelCase JSON — 전역 {@code ObjectMapper}(SNAKE_CASE)를 쓰지 않고 {@code RunPositionRedisCodec} 의
 * 전용 {@code JsonMapper} 로 읽고 쓴다. 다형 직렬화기({@code RedisTemplate<String,Object>})로 되돌리지 마라.
 */
@Component
public class RunPositionStore {

    /** {@code run:{runId}:position} 전체 — 개발용 초기화만 쓴다. */
    private static final String KEY_PATTERN = "run:*:position";

    /** Redis 키가 없을 때 DB 를 읽는 범위 — 위치가 낡았다고 보는 기준과 같다({@code StudentBusPositionQueryService.STALE_THRESHOLD}, 같은 값임을 시험이 본다). 이보다 오래된 행은 어차피 낡은 위치다. */
    static final Duration MISS_LOOKBACK = RunPositionHistoryFallback.MISS_LOOKBACK;

    /** 받아들이지 못한 위치를 이유별로 세는 지표 이름 — 중복·과다 수신 차단({@code PositionIntakeGuard})도 같은 이름에 다른 이유로 센다. */
    public static final String DROPPED_METRIC = "schoolbus.location.position.dropped";

    private final StringRedisTemplate stringRedisTemplate;

    private final Clock clock;

    private final RunPositionRedisWriter redisWriter;

    private final RunPositionHistoryFallback historyFallback;

    /** Redis 가 죽으면 폴링마다 같은 실패가 난다 — 분당 한 번만 스택과 함께 남긴다. */
    private final RateLimitedWarn redisReadFailure = RateLimitedWarn.perMinute(
            org.slf4j.LoggerFactory.getLogger(RunPositionStore.class));

    /**
     * Redis 읽기 · 쓰기가 마지막으로 실패한 시각 — 이 시각 이전에 받은 좌표는 그 뒤의 쓰기가 Redis 에 닿지 못해 낡았을 수 있다(얼렸다 풀린 Redis 에는 멈추기 전 값이 그대로 남는다).
     * 그런 값은 읽을 때 DB 최신 행으로 바꿔 복귀 직후 몇 초 동안 멈춘 시간만큼 오래된 좌표가 나가지 않게 하고, 다음 송신이 값을 다시 쓰면 Redis 값으로 돌아온다.
     */
    private volatile Instant lastRedisFailureAt = Instant.MIN;

    /** 스프링과 시험이 쓰는 생성자 — Redis 쓰기와 이력 대체는 같은 인자로 만든 보조 객체가 맡는다. */
    public RunPositionStore(StringRedisTemplate stringRedisTemplate, RunPositionRepository runPositionRepository,
            RunStopRepository runStopRepository, MeterRegistry meterRegistry, Clock clock) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.clock = clock;
        this.redisWriter = new RunPositionRedisWriter(stringRedisTemplate, meterRegistry);
        this.historyFallback = new RunPositionHistoryFallback(runPositionRepository, runStopRepository,
                meterRegistry, clock);
    }

    /**
     * 최신 좌표를 쓴다 — 이미 있는 값의 {@code recorded_at} 이 더 새로우면 쓰지 않고 {@code false} 다(늦게 커밋된 옛 위치가 버스를 되돌리지
     * 않게). 실패는 호출자에게 던진다(위치 수신 리스너가 삼키고 다음 송신에 맡긴다).
     */
    public boolean save(Long runId, RunPositionRedisValue value) {
        try {
            return redisWriter.writeIfNewest(runId, value);
        } catch (DataAccessException e) {
            lastRedisFailureAt = clock.instant();
            throw e;
        }
    }

    /**
     * 회차가 마지막으로 받아들인 최신 좌표 — Redis 키만 읽는다(키가 없으면 빈 값, DB 대체 없음). 중복·과다 수신 판정이 쓰는 값이라 Redis 가
     * 응답하지 않으면 빈 값이고 판정은 받아들이는 쪽으로 기운다.
     */
    public Optional<RunPositionRedisValue> findAcceptedInRedis(Long runId) {
        try {
            return RunPositionRedisCodec.parse(runId, stringRedisTemplate.opsForValue().get(RunPositionRedisCodec.keyOf(runId)));
        } catch (DataAccessException e) {
            lastRedisFailureAt = clock.instant();
            redisReadFailure.warn(e, "[location] Redis 최신 좌표 읽기 실패 — 중복 판정 없이 받아들인다. runId={}", runId);
            return Optional.empty();
        }
    }

    /** 회차 1건의 최신 좌표 — 아직 없으면 빈 값. Redis 가 실패하면 DB 최신 행이다. */
    public Optional<RunPositionRedisValue> find(Long runId) {
        return Optional.ofNullable(findAll(List.of(runId)).get(runId));
    }

    /**
     * 여러 회차의 최신 좌표를 한 번에 — 값이 없는 회차는 맵에 없다. Redis 는 {@code MGET} 한 번, 실패하면 DB 조회
     * 한 번이다.
     */
    public Map<Long, RunPositionRedisValue> findAll(Collection<Long> runIds) {
        if (runIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = List.copyOf(runIds);
        List<String> raws;
        try {
            raws = stringRedisTemplate.opsForValue().multiGet(ids.stream().map(RunPositionRedisCodec::keyOf).toList());
        } catch (DataAccessException e) {
            lastRedisFailureAt = clock.instant();
            redisReadFailure.warn(e, "[location] Redis 최신 좌표 읽기 실패 — run_position 최신 행으로 대체한다. runIds={}", ids);
            return historyFallback.fromHistory(ids);
        }
        return mergeWithHistory(classify(ids, raws));
    }

    /** Redis 에서 읽은 값을 그대로 쓸 것 · 낡았을 수 있어 이력으로 바꿀 것 · 키가 없는 것으로 가른다. */
    private ReadResult classify(List<Long> ids, List<String> raws) {
        ReadResult result = new ReadResult(new HashMap<>(), new ArrayList<>(), new ArrayList<>());
        for (int i = 0; i < ids.size(); i++) {
            Long runId = ids.get(i);
            if (raws.get(i) == null) {
                result.missing().add(runId);
            }
            RunPositionRedisCodec.parse(runId, raws.get(i)).ifPresent(position -> {
                if (position.receivedAt().toInstant().isBefore(lastRedisFailureAt)) {
                    result.possiblyStale().add(runId);
                } else {
                    result.positions().put(runId, position);
                }
            });
        }
        return result;
    }

    /** 낡았을 수 있는 회차와 키가 없는 회차를 이력 최신 행으로 채워 합친다. */
    private Map<Long, RunPositionRedisValue> mergeWithHistory(ReadResult result) {
        Map<Long, RunPositionRedisValue> positions = result.positions();
        if (!result.possiblyStale().isEmpty()) {
            positions.putAll(historyFallback.fromHistory(result.possiblyStale()));
        }
        if (!result.missing().isEmpty()) {
            positions.putAll(historyFallback.fromHistoryForMissing(result.missing()));
        }
        return positions;
    }

    /** 최신 좌표 키를 전부 지운다(개발용 초기화) — 지운 키 개수. */
    public int deleteAll() {
        Set<String> keys = stringRedisTemplate.keys(KEY_PATTERN);
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        stringRedisTemplate.delete(keys);
        return keys.size();
    }

    /** 지표 이름을 기동 직후에도 0 으로 노출되도록 미리 등록한다(BR-167, TECH_DECISIONS §14.2). */
    @PostConstruct
    void registerMetrics() {
        historyFallback.registerMetrics();
        redisWriter.registerMetrics();
    }

    /** {@link #classify} 결과 — 읽은 값 그대로 쓸 위치 · 낡았을 수 있는 회차 · 키가 없는 회차. */
    private record ReadResult(Map<Long, RunPositionRedisValue> positions, List<Long> possiblyStale, List<Long> missing) {
    }
}
