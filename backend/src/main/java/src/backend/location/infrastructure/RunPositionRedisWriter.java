package src.backend.location.infrastructure;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import src.backend.global.common.logging.RateLimitedWarn;
import src.backend.location.dto.RunPositionRedisValue;

/**
 * 최신 좌표의 Redis 비교 쓰기 — {@link RunPositionStore#save} 가 위임한다. Redis 실패는 호출자에게 던지고,
 * 실패 시각 기록은 호출한 저장소가 맡는다.
 */
final class RunPositionRedisWriter {

    /** 운행 종료 후 자연 소멸 — 이력은 {@code run_position} 이 따로 갖는다. */
    private static final Duration TTL = Duration.ofMinutes(30);

    /** 비교 쓰기가 상한만큼 밀려 쓰지 못한 위치의 {@code reason} 값. */
    private static final String WRITE_CONTENDED = "write_contended";

    /** 쓰기 경합으로 비교 쓰기가 밀릴 때 읽기부터 다시 하는 상한 — 이 안에서 못 쓰면 더 새로운 값이 계속 들어오는 것이라 쓰지 않는다. */
    private static final int WRITE_ATTEMPTS = 5;

    /** 읽어 둔 값 그대로일 때만 덮어쓴다 — 읽기와 쓰기 사이에 다른 송신이 쓴 값을 지우지 않는다. 1 이면 썼다. */
    private static final DefaultRedisScript<Long> REPLACE_IF_UNCHANGED = new DefaultRedisScript<>(
            "local current = redis.call('GET', KEYS[1]) "
                    + "if current == false then current = '' end "
                    + "if current ~= ARGV[1] then return 0 end "
                    + "redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3]) "
                    + "return 1",
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    private final MeterRegistry meterRegistry;

    /** 경합이 이어지면 송신마다 같은 일이 난다 — 분당 한 번만 남긴다. */
    private final RateLimitedWarn writeContention = RateLimitedWarn.perMinute(
            org.slf4j.LoggerFactory.getLogger(RunPositionStore.class));

    RunPositionRedisWriter(StringRedisTemplate stringRedisTemplate, MeterRegistry meterRegistry) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.meterRegistry = meterRegistry;
    }

    /** 이미 있는 값의 {@code recorded_at} 이 더 새로우면 쓰지 않고 {@code false}, 비교 쓰기가 상한만큼 밀려도 {@code false} 다. */
    boolean writeIfNewest(Long runId, RunPositionRedisValue value) {
        String key = RunPositionRedisCodec.keyOf(runId);
        String json = RunPositionRedisCodec.toJson(value);
        for (int attempt = 0; attempt < WRITE_ATTEMPTS; attempt++) {
            String current = stringRedisTemplate.opsForValue().get(key);
            if (isNewerThan(RunPositionRedisCodec.parse(runId, current), value)) {
                return false;
            }
            Long replaced = stringRedisTemplate.execute(REPLACE_IF_UNCHANGED, List.of(key),
                    current == null ? "" : current, json, String.valueOf(TTL.toSeconds()));
            if (Long.valueOf(1).equals(replaced)) {
                return true;
            }
        }
        meterRegistry.counter(RunPositionStore.DROPPED_METRIC, "reason", WRITE_CONTENDED).increment();
        writeContention.warn(null, "[location] Redis 최신 좌표 비교 쓰기가 {}번 밀려 이 위치를 쓰지 않는다(방송도 건너뛴다). runId={}",
                WRITE_ATTEMPTS, runId);
        return false;
    }

    private static boolean isNewerThan(Optional<RunPositionRedisValue> current, RunPositionRedisValue value) {
        return current.isPresent() && current.get().recordedAt().toInstant().isAfter(value.recordedAt().toInstant());
    }

    /** 쓰기 경합 지표를 기동 직후에도 0 으로 노출되도록 미리 등록한다. */
    void registerMetrics() {
        Counter.builder(RunPositionStore.DROPPED_METRIC).tag("reason", WRITE_CONTENDED)
                .description("받아들이지 못한 위치 수 — 이유별(중복·과다 수신, 비교 쓰기 경합)").register(meterRegistry);
    }
}
