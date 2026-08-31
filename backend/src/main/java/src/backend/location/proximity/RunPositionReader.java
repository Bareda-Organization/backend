package src.backend.location.proximity;

import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import tools.jackson.databind.ObjectMapper;

/**
 * 회차의 최신 위치를 Redis 에서 읽는다(T1 소유 계약, 키 {@code run:{runId}:position}) — 이 클래스는
 * 그 키를 <b>읽기만</b> 하고 쓰지 않는다.
 *
 * <p>{@link src.backend.global.config.RedisConfig} 의 {@code RedisTemplate<String,Object>} 를 쓰지
 * 않고 별도로 자동 구성된 {@link StringRedisTemplate} 을 쓴다 — 그 빈은 값 직렬화에
 * {@code @class} 다형 타입 정보를 함께 저장하는데, 허용 목록이 비어 있어(RedisConfig 주석) T1 이
 * 넣을 값 타입이 아직 그 목록에 없으면 역직렬화가 거부된다. 원시 JSON 문자열을 받아 이 모듈이
 * 필요한 필드만 직접 파싱하면 그 화이트리스트에 얽매이지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class RunPositionReader {

    private static final String KEY_FORMAT = "run:%d:position";

    private final StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper;

    /** 회차의 최신 위치 — 키가 없으면(아직 위치 수신 전) 빈 값이다. */
    Optional<RunPositionSnapshot> read(Long runId) {
        String raw = stringRedisTemplate.opsForValue().get(KEY_FORMAT.formatted(runId));
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, RunPositionSnapshot.class));
        } catch (RuntimeException e) {
            log.warn("회차 {} 위치 값 파싱 실패 — 이번 틱은 건너뛴다", runId, e);
            return Optional.empty();
        }
    }
}
