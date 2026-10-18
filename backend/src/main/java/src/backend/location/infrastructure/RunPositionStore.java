package src.backend.location.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import tools.jackson.databind.json.JsonMapper;

import src.backend.global.common.logging.RateLimitedWarn;
import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.entity.RunPosition;
import src.backend.location.repository.RunPositionRepository;

/**
 * 회차 최신 좌표 저장소 — Redis 키 {@code run:{runId}:position} 을 읽고 쓰는 유일한 곳이다(BR-098 · BR-099).
 * 위치 수신 리스너가 쓰고, 근접 판정·학부모 위치·관제·비상 신고가 읽는다. 전에는 3개 모듈 4개 클래스가 키·형식을
 * 각자 알아서, 한쪽만 형식이 바뀐 사고가 있었다({@link RunPositionRedisValue} 자바독).
 *
 * <p><b>Redis 장애 대체(BR-167, TECH_DECISIONS §14.2)</b> — {@link #find}·{@link #findAll} 은 Redis 가 실패하면
 * (연결 실패·시간 상한 초과) {@code run_position} 의 회차별 최신 행으로 같은 형태를 돌려준다. 여러 회차는 조회
 * 한 번이다(관제가 회차 수만큼 쿼리를 내지 않게). 대체 값의 {@code currentStopName} 은 {@code null} — 이력 행에
 * 없는 값이다. <b>근접 판정도 대체한다</b>(Ruling 624) — 건너뛰면 Redis 가 죽은 동안 기사가 도착 처리해 "다음 미도착" 이 넘어간
 * 정차지는 선점 한 번 못 해 도착 임박 알림이 영구히 빠진다. 대체 위치는 {@code recorded_at} 이 지연될 수 있어 300m 안 진입을
 * 늦게 알아챌 수는 있지만 알림이 빠지는 것보다 낫다.
 *
 * <p>인터페이스(spec)로 가르지 않는다 — 최신 좌표를 Redis 에 두는 것은 인스턴스 간 공유 때문이고
 * (ARCHITECTURE §9.5) 바꿀 구현 후보가 없다. 교체 축이 없는 곳에 포트를 씌우지 않는다(ARCHITECTURE §3.2.1).
 * 호출자는 Redis 를 모르고 이 클래스만 안다(CODE_CONVENTIONS 의 Service·Infrastructure 규칙).
 *
 * <p>⚠ 값 형식은 평문 camelCase JSON — 전역 {@code ObjectMapper}(SNAKE_CASE)를 쓰지 않고 이 클래스 전용
 * {@link JsonMapper} 로 읽고 쓴다. 다형 직렬화기({@code RedisTemplate<String,Object>})로 되돌리지 마라.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunPositionStore {

    private static final String KEY_FORMAT = "run:%d:position";

    /** {@link #KEY_FORMAT} 전체 — 개발용 초기화만 쓴다. */
    private static final String KEY_PATTERN = "run:*:position";

    /** 운행 종료 후 자연 소멸 — 이력은 {@code run_position} 이 따로 갖는다. */
    private static final Duration TTL = Duration.ofMinutes(30);

    /** Redis 대체 조회가 거슬러 올라가는 한계 — 이보다 오래된 위치는 운행 중 회차의 "최신" 이 아니고, 구간이 있어야 읽는 일 파티션이 2개로 묶인다(DA-2). */
    private static final String FALLBACK_METRIC = "schoolbus.position.fallback";

    private static final Duration FALLBACK_LOOKBACK = Duration.ofHours(24);

    /** 단말 시계 오차 허용(5분, BR-243)을 덮는 상한 여유 — 이보다 미래의 기록 시각은 수신 시각으로 바뀌어 저장된다. */
    private static final Duration FALLBACK_LOOKAHEAD = Duration.ofMinutes(10);

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final StringRedisTemplate stringRedisTemplate;

    private final RunPositionRepository runPositionRepository;

    private final MeterRegistry meterRegistry;

    private final Clock clock;

    /** Redis 가 죽으면 폴링마다 같은 실패가 난다 — 분당 한 번만 스택과 함께 남긴다. */
    private final RateLimitedWarn redisReadFailure = RateLimitedWarn.perMinute(
            org.slf4j.LoggerFactory.getLogger(RunPositionStore.class));

    /**
     * Redis 읽기 · 쓰기가 마지막으로 실패한 시각 — 이 시각 이전에 받은 좌표는 그 뒤의 쓰기가 Redis 에 닿지 못해 낡았을 수 있다(얼렸다 풀린 Redis 에는 멈추기 전 값이 그대로 남는다).
     * 그런 값은 읽을 때 DB 최신 행으로 바꿔 복귀 직후 몇 초 동안 멈춘 시간만큼 오래된 좌표가 나가지 않게 하고, 다음 송신이 값을 다시 쓰면 Redis 값으로 돌아온다.
     */
    private volatile Instant lastRedisFailureAt = Instant.MIN;

    /** 최신 좌표를 덮어쓴다 — 실패는 호출자에게 던진다(위치 수신 리스너가 삼키고 다음 송신에 맡긴다). */
    public void save(Long runId, RunPositionRedisValue value) {
        try {
            stringRedisTemplate.opsForValue().set(keyOf(runId), JSON_MAPPER.writeValueAsString(value), TTL);
        } catch (DataAccessException e) {
            lastRedisFailureAt = clock.instant();
            throw e;
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
            raws = stringRedisTemplate.opsForValue().multiGet(ids.stream().map(this::keyOf).toList());
        } catch (DataAccessException e) {
            lastRedisFailureAt = clock.instant();
            redisReadFailure.warn(e, "[location] Redis 최신 좌표 읽기 실패 — run_position 최신 행으로 대체한다. runIds={}", ids);
            return fromHistory(ids);
        }
        Map<Long, RunPositionRedisValue> positions = new HashMap<>();
        List<Long> possiblyStale = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            Long runId = ids.get(i);
            parse(runId, raws.get(i)).ifPresent(position -> {
                if (position.receivedAt().toInstant().isBefore(lastRedisFailureAt)) {
                    possiblyStale.add(runId);
                } else {
                    positions.put(runId, position);
                }
            });
        }
        if (!possiblyStale.isEmpty()) {
            positions.putAll(fromHistory(possiblyStale));
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

    private Map<Long, RunPositionRedisValue> fromHistory(List<Long> runIds) {
        meterRegistry.counter(FALLBACK_METRIC).increment();
        OffsetDateTime now = OffsetDateTime.now(clock);
        Map<Long, RunPositionRedisValue> positions = new HashMap<>();
        for (RunPosition row : runPositionRepository.findLatestByRunIdIn(runIds, now.minus(FALLBACK_LOOKBACK),
                now.plus(FALLBACK_LOOKAHEAD))) {
            positions.put(row.getRunId(), new RunPositionRedisValue(row.getLat(), row.getLng(), row.getRecordedAt(),
                    row.getReceivedAt(), null));
        }
        return positions;
    }

    /** 형식이 틀린 값은 없는 것으로 본다 — 다음 송신이 덮어쓴다. */
    private Optional<RunPositionRedisValue> parse(Long runId, String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON_MAPPER.readValue(raw, RunPositionRedisValue.class));
        } catch (RuntimeException e) {
            log.warn("[location] 회차 {} 최신 좌표 값 파싱 실패 — 없는 것으로 본다", runId, e);
            return Optional.empty();
        }
    }

    private String keyOf(Long runId) {
        return KEY_FORMAT.formatted(runId);
    }

    /** 대체가 한 번도 없는 기동 직후에도 이름이 0 으로 노출되도록 미리 등록한다(BR-167, TECH_DECISIONS §14.2). */
    @PostConstruct
    void registerMetrics() {
        Counter.builder(FALLBACK_METRIC).description("Redis 장애로 최신 좌표를 DB 이력 최신 행으로 대체 조회한 횟수")
                .register(meterRegistry);
    }
}
