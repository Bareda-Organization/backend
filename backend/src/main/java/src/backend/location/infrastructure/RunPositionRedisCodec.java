package src.backend.location.infrastructure;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.json.JsonMapper;

import src.backend.location.dto.RunPositionRedisValue;

/**
 * 최신 좌표 Redis 키 이름과 값 JSON 변환 — {@link RunPositionStore} 와 그 쓰기 보조가 같은 키·형식을 쓰게 한 곳에 둔다.
 * 로그는 저장소와 같은 로거 이름({@link RunPositionStore})으로 남긴다.
 */
final class RunPositionRedisCodec {

    private static final Logger log = LoggerFactory.getLogger(RunPositionStore.class);

    private static final String KEY_FORMAT = "run:%d:position";

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private RunPositionRedisCodec() {
    }

    /** 회차의 최신 좌표 Redis 키. */
    static String keyOf(Long runId) {
        return KEY_FORMAT.formatted(runId);
    }

    /** 값을 Redis 에 저장할 평문 JSON 으로 바꾼다. */
    static String toJson(RunPositionRedisValue value) {
        return JSON_MAPPER.writeValueAsString(value);
    }

    /** 형식이 틀린 값은 없는 것으로 본다 — 다음 송신이 덮어쓴다. */
    static Optional<RunPositionRedisValue> parse(Long runId, String raw) {
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
}
