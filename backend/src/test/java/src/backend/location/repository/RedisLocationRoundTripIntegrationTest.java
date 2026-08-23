package src.backend.location.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import src.backend.location.dto.BusLocationPing;
import src.backend.location.dto.LocationOrigin;
import src.backend.location.dto.LocationPing;

/**
 * {@code RedisConfig} 가 등록한 값 직렬화기가 실제 Redis 서버를 상대로도 {@code @class} 타입 정보를
 * 왕복시키는지 확인한다(BOOT-6). 단위 테스트({@code RedisBusLocationRepositoryTest})는 Mock
 * {@code RedisConnectionFactory} 로 직렬화기 자체만 검증할 뿐 네트워크 왕복은 거치지 않는다 — 이
 * 테스트는 Docker Redis 컨테이너에 실제로 쓰고 읽어, {@code PolymorphicTypeValidator} 로 좁힌
 * 허용 타입({@link LocationPing}·{@link BusLocationPing}) 이 조회 시 {@code Map} 이 아니라
 * 원래 도메인 타입으로 복원되는지를 증명한다.
 */
@SpringBootTest
class RedisLocationRoundTripIntegrationTest {

    private static final String LOCATION_KEY = "test:loc:roundtrip";
    private static final String BUS_LOCATION_KEY = "test:busloc:roundtrip";
    private static final String DISALLOWED_KEY = "test:disallowed:roundtrip";

    private record DisallowedPayload(String value) {
    }

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(LOCATION_KEY);
        redisTemplate.delete(BUS_LOCATION_KEY);
        redisTemplate.delete(DISALLOWED_KEY);
    }

    @Test
    void locationPing_roundtrips_through_real_redis_as_original_type() {
        LocationPing ping = new LocationPing(1L, 2L, 37.5, 127.0,
                LocalDateTime.of(2026, 8, 23, 9, 0), LocationOrigin.GPS);

        redisTemplate.opsForValue().set(LOCATION_KEY, ping);
        Object restored = redisTemplate.opsForValue().get(LOCATION_KEY);

        assertThat(restored)
                .as("Map 이 아니라 LocationPing 타입 자체로 복원돼야 한다 — @class 허용 목록이 걸려 있음을 증명한다")
                .isInstanceOf(LocationPing.class)
                .isEqualTo(ping);
    }

    @Test
    void busLocationPing_roundtrips_through_real_redis_as_original_type() {
        BusLocationPing ping = new BusLocationPing(7L, 2L, 37.6, 127.1,
                LocalDateTime.of(2026, 8, 23, 9, 5), LocationOrigin.MOCK);

        redisTemplate.opsForValue().set(BUS_LOCATION_KEY, ping);
        Object restored = redisTemplate.opsForValue().get(BUS_LOCATION_KEY);

        assertThat(restored)
                .as("Map 이 아니라 BusLocationPing 타입 자체로 복원돼야 한다 — @class 허용 목록이 걸려 있음을 증명한다")
                .isInstanceOf(BusLocationPing.class)
                .isEqualTo(ping);
    }

    @Test
    void typeOutsideAllowlist_isRejectedAtReadTime() {
        // write(set) 는 @class 타입 문자열을 검증 없이 그대로 저장한다 — 거부는 read(get) 시점,
        // PolymorphicTypeValidator 가 역직렬화 대상 타입을 허용 목록과 대조할 때 일어난다.
        // 누군가 RedisConfig 를 enableUnsafeDefaultTyping() 으로 되돌리면 이 테스트가 실패해 잡아낸다.
        redisTemplate.opsForValue().set(DISALLOWED_KEY, new DisallowedPayload("x"));

        assertThatThrownBy(() -> redisTemplate.opsForValue().get(DISALLOWED_KEY))
                .as("허용 목록(LocationPing·BusLocationPing) 밖 타입은 read 시점에 거부돼야 한다")
                .hasMessageContaining("PolymorphicTypeValidator")
                .hasMessageContaining("denied resolution");
    }
}
