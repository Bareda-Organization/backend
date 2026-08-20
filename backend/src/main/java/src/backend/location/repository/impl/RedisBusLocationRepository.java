package src.backend.location.repository.impl;

import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import src.backend.location.dto.BusLocationPing;
import src.backend.location.repository.spec.BusLocationRepository;

/**
 * {@link BusLocationRepository}의 Redis 구현 — 버스의 최신 좌표를 프로세스 밖에 두어 백엔드 재시작
 * 후에도 유지하고, TTL로 오래된 좌표를 자동 만료시킨다. {@link InMemoryBusLocationRepository}
 * (단일 프로세스·무제한 보관)를 대체하는 게 목표라 {@link Primary}로 등록한다 —
 * Port(BusLocationRepository) 시그니처는 그대로라 서비스 계층은 이 교체를 전혀 모른다.
 *
 * <p>TTL은 학생 좌표({@code RedisLocationRepository})처럼 tick 주기에서 파생시키지 않고 독립 설정
 * ({@code app.location.bus-ttl-seconds}, 기본 15초)으로 둔다 — 버스는 기사 앱이 5초 주기로
 * {@code POST /api/locations/bus}에 보고해 학생 tick(3초)과 근거가 다르다.
 *
 * <p>만료된 버스는 {@link #findLatest(Long)}가 empty를 돌려주고, 조회 서비스가 그 버스를 결과에서
 * 제외한다 — 관제 지도가 보고를 멈춘 버스를 계속 "여기 있음"으로 표시하던 동작이 사라진다.
 */
@Repository
@Primary
public class RedisBusLocationRepository implements BusLocationRepository {

    private static final String KEY_PREFIX = "busloc:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final Duration ttl;

    public RedisBusLocationRepository(RedisTemplate<String, Object> redisTemplate,
                                      @Value("${app.location.bus-ttl-seconds:15}") long busTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofSeconds(busTtlSeconds);
    }

    @Override
    public void save(BusLocationPing ping) {
        redisTemplate.opsForValue().set(KEY_PREFIX + ping.busId(), ping, ttl);
    }

    @Override
    public Optional<BusLocationPing> findLatest(Long busId) {
        return Optional.ofNullable((BusLocationPing) redisTemplate.opsForValue().get(KEY_PREFIX + busId));
    }
}
