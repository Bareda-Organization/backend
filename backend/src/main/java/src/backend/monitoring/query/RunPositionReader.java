package src.backend.monitoring.query;

import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import tools.jackson.databind.json.JsonMapper;

/**
 * 회차의 최신 위치를 Redis 에서 읽는다(T1 소유 계약, 키 {@code run:{runId}:position}) — 관제 모듈 전용
 * 소비자이며 이 클래스는 그 키를 <b>읽기만</b> 하고 쓰지 않는다(구조는 {@code location.proximity
 * .RunPositionReader} 본보기).
 *
 * <p>⚠ <b>와이어 포맷 — 다형 타입 태그 없는 평문 camelCase JSON.</b> 그래서 다형 타입 정보를 함께
 * 심는 공유 {@code RedisTemplate<String,Object>} 대신 {@link StringRedisTemplate} 으로 원시 JSON
 * 문자열을 받는다.
 *
 * <p>{@link JsonMapper} 를 전역 빈으로 주입받지 않고 <b>이 클래스가 직접 만든다</b>({@code
 * student.query.RunPositionCache} 와 같은 판단) — 전역 HTTP 메시지 컨버터 빈은 API 응답 규약인
 * {@code SNAKE_CASE} 네이밍 전략이 걸려 있어, 그대로 쓰면 {@code recordedAt} 같은 리터럴 camelCase
 * 키를 {@code recorded_at} 으로 오인해 조용히 역직렬화가 빈다(Phase 10 게이트 리뷰 R1 Critical).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class RunPositionReader {

    private static final String KEY_FORMAT = "run:%d:position";

    /** 전역 SNAKE_CASE 네이밍 전략과 무관한, 이 클래스 전용 인스턴스 — 위 자바독 참고. */
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final StringRedisTemplate stringRedisTemplate;

    /** 회차의 최신 위치 — 키가 없거나(아직 위치 수신 전) 파싱에 실패하면 빈 값이다. */
    Optional<RunPositionSnapshot> read(Long runId) {
        String raw = stringRedisTemplate.opsForValue().get(KEY_FORMAT.formatted(runId));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON_MAPPER.readValue(raw, RunPositionSnapshot.class));
        } catch (RuntimeException e) {
            log.warn("회차 {} 위치 값 파싱 실패 — 이번 조회는 유실로 본다", runId, e);
            return Optional.empty();
        }
    }
}
