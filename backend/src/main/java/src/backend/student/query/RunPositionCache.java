package src.backend.student.query;

import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * T1 이 쓰는 {@code run:{runId}:position} Redis 키를 읽기만 하는 소비자(LOC-02, Ruling 208).
 *
 * <p>공유 {@code RedisConfig.redisTemplate} 빈을 쓰지 않는다. 그 빈은 {@code @class} 타입 태그를 심는
 * 다형 직렬화({@code GenericJacksonJsonRedisSerializer})라 T1 이 <b>평문 JSON</b>(타입 태그 없는
 * {@code lat}·{@code lng}·{@code recordedAt}·{@code receivedAt}·{@code currentStopName})으로 쓴 값을
 * 그 역직렬화기로 읽으면 {@code @class} 필드 부재로 실패한다. 그래서 {@link StringRedisTemplate}(스프링
 * 부트가 {@code RedisConfig} 에 값이 없어 자동 구성해 주는 빈)으로 원문 문자열만 받아 별도
 * {@link JsonMapper} 로 수동 역직렬화한다.
 *
 * <p>그 {@link JsonMapper} 도 전역 빈을 주입받지 않고 <b>이 클래스가 직접 만든다</b> — 전역 빈은
 * {@code application.yml} 의 {@code SNAKE_CASE} 네이밍 전략이 걸려 있는데, 그건 HTTP 응답(API_SPEC)
 * 규약이지 T1 계약 문서가 적은 리터럴 camelCase 필드 이름과는 무관하다. 전역 빈을 그대로 쓰면
 * {@code recordedAt} 같은 키를 {@code recorded_at} 으로 오인해 조용히 역직렬화가 비게 된다.
 *
 * <p>⚠ {@code RedisConfig} 자바독은 "새 좌표 DTO 는 그 빈의 허용 목록에 추가한다" 를 제안한다 — 이
 * 클래스의 선택과 반대 방향이다. T1 이 실제 기록기를 아직 만들지 않아 어느 쪽이 맞을지 이 태스크
 * 시점에서는 확정할 수 없다 — 계약 문서에 적힌 필드 이름을 문자 그대로 신뢰하는 쪽을 택했고, 이
 * 모순은 보고서에 남긴다.
 */
@Component
public class RunPositionCache {

    private static final String KEY_FORMAT = "run:%d:position";

    /** 전역 SNAKE_CASE 네이밍 전략과 무관한, 이 클래스 전용 인스턴스 — 위 자바독 참고. */
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final StringRedisTemplate stringRedisTemplate;

    public RunPositionCache(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public Optional<RunPositionSnapshot> find(Long runId) {
        String raw = stringRedisTemplate.opsForValue().get(KEY_FORMAT.formatted(runId));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(JSON_MAPPER.readValue(raw, RunPositionSnapshot.class));
    }
}
