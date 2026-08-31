package testsupport.redis;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 전용 Redis 컨테이너로 {@code spring.data.redis.*} 를 갈아 끼우는 테스트 베이스(목표 3, Phase 10
 * T1) — 공유 컨테이너 {@code school-bus-redis-1} 은 병렬 좌석(T1·T3·T4)이 동시에 건드리므로 여기서는
 * 쓰지 않는다.
 *
 * <p>패키지를 {@code src.backend} 트리 밖({@code testsupport}) 에 둔 이유는
 * {@code testsupport.db.MigratedPostgresTestBase} 와 같다 — {@code BackendApplication} 의 기본
 * 컴포넌트 스캔 범위에 테스트 전용 클래스가 섞여 들어가는 것을 막기 위함이다.
 *
 * <p>Testcontainers 2.0.5 BOM 에는 Redis 전용 모듈이 없다(build.gradle 의 testImplementation 목록
 * 옆 주석 참고) — 그래서 전용 컨테이너 클래스 대신 {@link GenericContainer} 로 redis 이미지를 직접
 * 띄운다. {@code RedisConnectionFactory} 는 {@code spring.data.redis.host}·{@code port} 만 보고
 * 자동 구성되므로({@code RedisConfig} 자바독 참고) 그 두 값만 갈아 끼우면 충분하다.
 *
 * <p>공유 컨테이너를 못 쓰는 이유가 하나 더 있다 — Postgres 는 {@code @Transactional} 롤백으로
 * 시험 간 격리되지만, Redis 는 트랜잭션이 없어 한 시험이 쓴 키가 다음 시험에 그대로 남는다. 전용
 * 컨테이너를 매번 새로 올리는 것이 그 격리를 대신한다.
 *
 * <p>이미지 태그는 {@code redis:7}(alpine 아님) — {@code docker-compose.yml} 의 운영 컨테이너와
 * 같은 태그다. 시험과 운영이 다른 이미지를 쓰면 버전 차이로 나는 결함을 시험이 못 본다.
 *
 * <p>쓰는 법 — 이 클래스를 상속하면 {@code @Testcontainers}·{@code @DynamicPropertySource} 가 함께
 * 상속돼, 그 테스트의 {@code @SpringBootTest} 컨텍스트가 이 컨테이너에 연결된다.
 */
@Testcontainers
public abstract class RedisTestContainerBase {

    @Container
    protected static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
