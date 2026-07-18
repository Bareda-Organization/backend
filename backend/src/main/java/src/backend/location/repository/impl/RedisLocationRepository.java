package src.backend.location.repository.impl;

import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import src.backend.location.dto.LocationPing;
import src.backend.location.repository.spec.LocationRepository;

/**
 * {@link LocationRepository}의 Redis 구현 — 다중 서버 인스턴스에서도 학생의 최신 좌표를 공유하고,
 * TTL로 오래된 좌표를 자동 만료시킨다. {@code InMemoryLocationRepository}(단일 프로세스·무제한 보관)를
 * 대체하는 게 목표라 {@link Primary}로 등록한다 — Port(LocationRepository) 시그니처는 그대로라
 * 서비스 계층은 이 교체를 전혀 모른다.
 *
 * <p>TTL은 tick 주기({@code app.location.tick-ms}, 기본 3초)의 3배로 둬, 정상 주기보다
 * 살짝 여유를 줘서 순간 지연만으로 좌표가 사라지는 오탐을 막는다.
 */
@Repository
@Primary
public class RedisLocationRepository implements LocationRepository {

    private static final String KEY_PREFIX = "loc:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final Duration ttl;

    public RedisLocationRepository(RedisTemplate<String, Object> redisTemplate,
                                   @Value("${app.location.tick-ms:3000}") long tickMs) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofMillis(tickMs * 3);
    }

    @Override
    public void save(LocationPing ping) {
        redisTemplate.opsForValue().set(KEY_PREFIX + ping.studentId(), ping, ttl);
    }

    @Override
    public Optional<LocationPing> findLatest(Long studentId) {
        return Optional.ofNullable((LocationPing) redisTemplate.opsForValue().get(KEY_PREFIX + studentId));
    }
}
