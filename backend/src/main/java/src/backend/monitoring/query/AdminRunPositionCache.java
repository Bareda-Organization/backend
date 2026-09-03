package src.backend.monitoring.query;

import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Admin 접두 임시본 — {@code student.query.RunPositionCache} 와 같은 이유로 {@link
 * StringRedisTemplate} + 로컬 {@link JsonMapper} 를 쓴다(전역 SNAKE_CASE 빈·다형 RedisTemplate 은
 * 평문 계약과 어긋나 역직렬화가 실패한다 — Phase 10 게이트 리뷰 R1 Critical). §6.8 전용이며 T1 이
 * 공용 클래스를 만들어 이름을 전파하면 그때 교체한다(p13-task-t2.md §3).
 */
@Component
public class AdminRunPositionCache {

    private static final String KEY_FORMAT = "run:%d:position";

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final StringRedisTemplate stringRedisTemplate;

    public AdminRunPositionCache(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public Optional<AdminRunPositionSnapshot> find(Long runId) {
        String raw = stringRedisTemplate.opsForValue().get(KEY_FORMAT.formatted(runId));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(JSON_MAPPER.readValue(raw, AdminRunPositionSnapshot.class));
    }
}
