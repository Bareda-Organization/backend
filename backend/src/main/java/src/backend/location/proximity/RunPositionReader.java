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
 * <p>⚠ <b>와이어 포맷 — 다형 타입 태그 없는 평문 camelCase JSON.</b> T1
 * ({@code src.backend.location.command.RunPositionRedisListener})이 이 형식으로 쓴다(계약·사고
 * 이력은 {@code RunPositionRedisValue} 자바독을 본다). 그래서 이 클래스는 {@code @class} 다형 타입
 * 정보를 함께 심는 공유 {@code RedisTemplate<String,Object>} 대신 {@link StringRedisTemplate} 으로
 * 원시 JSON 문자열을 받아 이 모듈이 필요한 필드만 직접 파싱한다.
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
