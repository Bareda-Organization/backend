package testsupport.redis;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Redis 를 쓰는 {@code @SpringBootTest} 의 공통 베이스 — 공유 {@code school-bus-redis-1} 대신
 * 인스턴스 하나를 통째로 새로 띄운다.
 *
 * <p>Postgres 쪽 {@link testsupport.db.MigratedPostgresTestBase} 와 이유가 다르다 — Postgres 는
 * {@code @Transactional} 롤백으로 시험 간 격리가 되지만, Redis 는 트랜잭션이 없어 쓴 키가 그대로
 * 남는다. 이 저장소는 T1·T3·T4 세 좌석이 같은 시간대에 {@code run:{runId}:position} 키를 같이
 * 건드리므로, 공유 컨테이너를 그대로 쓰면 한 좌석이 심은 값을 다른 좌석의 시험이 읽어 실패가
 * 코드 결함과 구분되지 않는다({@code parallel-agents-git.md} 의 "이름공간이 없는 미들웨어" 경고).
 *
 * <p>이미지는 {@code docker-compose.yml} 의 {@code redis:7} 과 버전을 맞췄다.
 *
 * <p>쓰는 법 — 이 클래스를 상속하기만 하면 된다. {@code spring.data.redis.host}·{@code port} 를
 * 이 컨테이너 값으로 자동 등록하므로 서브클래스가 따로 {@code @DynamicPropertySource} 를 적을
 * 필요가 없다({@code MigratedPostgresTestBase} 는 소비처마다 등록할 프로퍼티 키가 달라 베이스에
 * 두지 않았지만, Redis 쪽은 소비처가 전부 {@code spring.data.redis.*} 하나뿐이라 여기 고정한다).
 */
@Testcontainers
public abstract class IsolatedRedisTestBase {

    @Container
    protected static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redis_연결정보를_격리된_컨테이너로_돌린다(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
