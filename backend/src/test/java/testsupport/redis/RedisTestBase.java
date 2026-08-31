package testsupport.redis;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Redis 를 건드리는 {@code @SpringBootTest} 의 공통 베이스 — 전용 컨테이너로 {@code spring.data.
 * redis.*} 를 덮어써 공유 Docker 컨테이너({@code school-bus-redis-1}) 를 건드리지 않는다.
 *
 * <p>이 저장소에 Redis 시험 전례가 없어({@code src/test/java} 어디에도 {@code redis} 설정이 없다)
 * 전용 타입 컨테이너({@code testcontainers-redis} 류) 의존성도 없다 — {@code build.gradle} 이 이미
 * 끌어오는 {@code testcontainers-postgresql} 이 코어 {@code testcontainers} 아티팩트를 전이
 * 의존성으로 들여오므로, 그 코어의 {@link GenericContainer} 로 충분하다({@code redis:7-alpine}).
 *
 * <p>{@code testsupport.db.MigratedPostgresTestBase} 와 달리 Spring 컨텍스트를 그대로 띄운다
 * ({@code @DynamicPropertySource} 로 호스트·포트만 주입) — 검증 대상이 마이그레이션 SQL 이 아니라
 * {@code RunPositionReader}·{@code StringRedisTemplate} 빈을 포함한 실제 빈 그래프이기 때문이다.
 */
@Testcontainers
public abstract class RedisTestBase {

    @Container
    protected static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
