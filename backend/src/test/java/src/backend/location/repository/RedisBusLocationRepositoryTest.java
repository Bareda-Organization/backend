package src.backend.location.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.RedisSerializer;

import src.backend.global.config.RedisConfig;
import src.backend.location.dto.BusLocationPing;
import src.backend.location.dto.LocationOrigin;
import src.backend.location.repository.impl.RedisBusLocationRepository;

/**
 * 버스 좌표 Redis 저장소 단위 테스트(MON-1) — Redis 서버 없이 세 가지를 본다.
 * (1) 저장 시 붙는 키와 TTL, (2) TTL 이 설정값을 따르는지, (3) 저장한 값의 복원.
 */
class RedisBusLocationRepositoryTest {

    private static final Long BUS_ID = 7L;

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);

    private RedisBusLocationRepository repositoryWithTtlSeconds(long ttlSeconds) {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        return new RedisBusLocationRepository(redisTemplate, ttlSeconds);
    }

    @Test
    void save_uses_busloc_key_and_default_ttl_of_15_seconds() {
        BusLocationPing ping = ping();

        repositoryWithTtlSeconds(15).save(ping);

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq("busloc:7"), eq(ping), ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofSeconds(15));
    }

    /**
     * TTL 을 상수로 굳히지 않았는지 확인 — 주입값을 바꾸면 저장 TTL 도 따라 바뀌어야 한다.
     * {@code app.location.bus-ttl-seconds} 를 읽지 않고 값을 코드에 박으면 이 테스트가 실패한다.
     */
    @Test
    void save_ttl_follows_injected_property() {
        repositoryWithTtlSeconds(30).save(ping());

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq("busloc:7"), eq(ping()), ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void findLatest_returns_stored_ping() {
        BusLocationPing ping = ping();
        given(valueOperations.get("busloc:7")).willReturn(ping);

        assertThat(repositoryWithTtlSeconds(15).findLatest(BUS_ID)).contains(ping);
    }

    /** TTL 만료·미보고 양쪽 모두 Redis 에서는 "키 없음"으로 같다 — 조회는 empty 를 돌려줘야 한다. */
    @Test
    void findLatest_absent_returns_empty() {
        given(valueOperations.get("busloc:7")).willReturn(null);

        assertThat(repositoryWithTtlSeconds(15).findLatest(BUS_ID)).isEmpty();
    }

    /**
     * 직렬화 왕복 — {@code RedisConfig} 가 등록한 값 직렬화기로 {@link BusLocationPing} 을 저장 형태로
     * 바꿨다가 되돌린다. enum({@code LocationOrigin})·{@code LocalDateTime} 필드가 타입 정보를 잃으면
     * 복원 결과가 원본과 달라 이 테스트가 실패한다.
     */
    @Test
    @SuppressWarnings("unchecked")
    void redisConfig_serializer_roundtrips_ping_with_enum_and_localDateTime() {
        RedisSerializer<Object> serializer = (RedisSerializer<Object>) new RedisConfig()
                .redisTemplate(mock(RedisConnectionFactory.class))
                .getValueSerializer();
        BusLocationPing ping = ping();

        Object restored = serializer.deserialize(serializer.serialize(ping));

        assertThat(restored).isEqualTo(ping);
    }

    private BusLocationPing ping() {
        return new BusLocationPing(BUS_ID, 1L, 37.5, 127.0,
                LocalDateTime.of(2026, 8, 21, 8, 30), LocationOrigin.GPS);
    }
}
