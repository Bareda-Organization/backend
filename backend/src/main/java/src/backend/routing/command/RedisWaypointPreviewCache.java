package src.backend.routing.command;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import src.backend.global.redis.LocalFallbackMemory;

import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link WaypointPreviewCache} 의 Redis 구현 — 미리보기를 만든 인스턴스와 배포를 처리하는 인스턴스가 달라도 같은 값을 본다(Ruling 900 C).
 *
 * <p>키는 회차당 1개라 새 미리보기가 앞 것을 덮어쓰고, 만료는 그 회차의 <b>운행일이 끝나는 시각</b>(주입된 {@link Clock} 의 시간대로 다음 날 0시)이다 — 별도 정리 배치가 필요 없다.
 * 이미 운행일이 끝난 회차는 저장하지 않는다. 시각은 UTC 로 바꾸지 않고 읽어 오프셋을 지킨다(응답에 실리는 ETA 의 표기가 그대로다). <b>Redis 오류로 배포가 5xx 가 되면 안 된다</b> — Redis 를 못 쓰는 동안은 쓰기를 이 인스턴스 안의 기억({@link LocalFallbackMemory})이 받고 읽기도 거기서 읽는다. 그 기억에도 없으면 빈 값이라
 * 배포가 기존 {@code 409 PREVIEW_STALE} 로 답하고, 지우기는 둘 다 지운다.
 */
@Component
@Slf4j
public class RedisWaypointPreviewCache implements WaypointPreviewCache {

    private static final String KEY_PREFIX = "schoolbus:waypoint-preview:";

    /** Redis 를 못 쓰는 동안 이 인스턴스가 미리보기를 들고 있는 시간의 상한 — 운행일이 먼저 끝나면 그때까지다. */
    private static final Duration FALLBACK_TTL = Duration.ofMinutes(30);

    private static final int FALLBACK_MAX_ENTRIES = 1_000;

    private final StringRedisTemplate redis;
    private final JsonMapper mapper;
    private final ObjectReader reader;
    private final Clock clock;
    private final LocalFallbackMemory<WaypointPreview> whileRedisIsDown;

    public RedisWaypointPreviewCache(StringRedisTemplate redis, JsonMapper mapper, Clock clock) {
        this.redis = redis;
        this.mapper = mapper;
        this.reader = mapper.readerFor(WaypointPreview.class).without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
        this.clock = clock;
        this.whileRedisIsDown = new LocalFallbackMemory<>(clock, FALLBACK_MAX_ENTRIES);
    }

    @Override
    public Optional<WaypointPreview> find(Long runId) {
        try {
            String json = redis.opsForValue().get(keyOf(runId));
            return json == null ? Optional.empty() : Optional.of(reader.readValue(json));
        } catch (RuntimeException e) {
            log.warn("경유 지점 미리보기 읽기 실패 — 이 인스턴스 안의 기억을 본다. runId={}", runId, e);
            return whileRedisIsDown.get(keyOf(runId));
        }
    }

    @Override
    public void put(Long runId, LocalDate serviceDate, WaypointPreview preview) {
        Duration untilServiceDayEnds = Duration.between(clock.instant(),
                serviceDate.plusDays(1).atStartOfDay(clock.getZone()).toInstant());
        if (!untilServiceDayEnds.isPositive()) {
            return;
        }
        try {
            redis.opsForValue().set(keyOf(runId), mapper.writeValueAsString(preview), untilServiceDayEnds);
            whileRedisIsDown.remove(keyOf(runId));
        } catch (RuntimeException e) {
            log.warn("경유 지점 미리보기 저장 실패 — 이 인스턴스 안에 들고 있는다. runId={}", runId, e);
            whileRedisIsDown.put(keyOf(runId), preview, untilServiceDayEnds.compareTo(FALLBACK_TTL) < 0 ? untilServiceDayEnds : FALLBACK_TTL);
        }
    }

    @Override
    public void evict(Long runId) {
        whileRedisIsDown.remove(keyOf(runId));
        try {
            redis.delete(keyOf(runId));
        } catch (RuntimeException e) {
            log.warn("경유 지점 미리보기 삭제 실패 — 만료가 지운다. runId={}", runId, e);
        }
    }

    private static String keyOf(Long runId) {
        return KEY_PREFIX + runId;
    }
}
