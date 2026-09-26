package src.backend.location.infrastructure;

import java.time.Duration;
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

import tools.jackson.databind.json.JsonMapper;

import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.entity.RunPosition;
import src.backend.location.repository.RunPositionRepository;
import src.backend.observability.metrics.RunPositionFallbackMetrics;

/**
 * 회차 최신 좌표 저장소 — Redis 키 {@code run:{runId}:position} 을 읽고 쓰는 유일한 곳이다(BR-098 · BR-099).
 * 위치 수신 리스너가 쓰고, 근접 판정·학부모 위치·관제·비상 신고가 읽는다. 전에는 3개 모듈 4개 클래스가 키·형식을
 * 각자 알아서, 한쪽만 형식이 바뀐 사고가 있었다({@link RunPositionRedisValue} 자바독).
 *
 * <p><b>Redis 장애 대체(BR-167, TECH_DECISIONS §14.2)</b> — {@link #find}·{@link #findAll} 은 Redis 가 실패하면
 * (연결 실패·시간 상한 초과) {@code run_position} 의 회차별 최신 행으로 같은 형태를 돌려준다. 여러 회차는 조회
 * 한 번이다(관제가 회차 수만큼 쿼리를 내지 않게). 대체 값의 {@code currentStopName} 은 {@code null} — 이력 행에
 * 없는 값이다. 근접 판정은 대체하지 않는다({@link #findCached}) — 그 틱을 건너뛰고 다음 틱(10초)에 다시 본다.
 *
 * <p>인터페이스(spec)로 가르지 않는다 — 최신 좌표를 Redis 에 두는 것은 인스턴스 간 공유 때문이고(ARCHITECTURE
 * §9.5) 바꿀 구현 후보가 없다. 교체 축이 없는 곳에 포트를 씌우지 않는다(ARCHITECTURE §3.2.1). 호출자는 Redis 를
 * 모르고 이 클래스만 안다(CODE_CONVENTIONS §12·§13).
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

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final StringRedisTemplate stringRedisTemplate;

    private final RunPositionRepository runPositionRepository;

    private final RunPositionFallbackMetrics fallbackMetrics;

    /** 최신 좌표를 덮어쓴다 — 실패는 호출자에게 던진다(위치 수신 리스너가 삼키고 다음 송신에 맡긴다). */
    public void save(Long runId, RunPositionRedisValue value) {
        stringRedisTemplate.opsForValue().set(keyOf(runId), JSON_MAPPER.writeValueAsString(value), TTL);
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
            log.warn("[location] Redis 최신 좌표 읽기 실패 — run_position 최신 행으로 대체한다. runIds={}", ids, e);
            return fromHistory(ids);
        }
        Map<Long, RunPositionRedisValue> positions = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            Long runId = ids.get(i);
            parse(runId, raws.get(i)).ifPresent(position -> positions.put(runId, position));
        }
        return positions;
    }

    /** Redis 에서만 읽는다 — 실패는 그대로 던진다(근접 판정은 그 틱을 건너뛴다, 클래스 자바독). */
    public Optional<RunPositionRedisValue> findCached(Long runId) {
        return parse(runId, stringRedisTemplate.opsForValue().get(keyOf(runId)));
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
        fallbackMetrics.recordFallback();
        Map<Long, RunPositionRedisValue> positions = new HashMap<>();
        for (RunPosition row : runPositionRepository.findLatestByRunIdIn(runIds)) {
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
}
