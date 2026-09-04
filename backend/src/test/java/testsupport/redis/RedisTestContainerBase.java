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
 * 자동 구성되므로 그 두 값만 갈아 끼우면 충분하다. ⚠ 이 저장소에는 Redis 설정 클래스가 없다 —
 * 예전에 있던 {@code RedisConfig} 는 다형 직렬화 빈을 두었다가, 그 형식이 소비자 두 곳의 평문
 * 파서와 어긋나 위치 조회가 500 을 내는 결함을 만들어 삭제됐다. 되살리지 마라 — 값 형식은
 * {@code RunPositionRedisValue} 자바독이 계약으로 명시한다.
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

    /**
     * 상속한 테스트 클래스마다 새로 생기지 않는다 — {@code static} 이라 같은 JVM 포크 안에서는
     * <b>이 컨테이너 하나를 모든 하위 테스트 클래스가 나눠 쓴다</b>(Testcontainers 의 재사용 규약).
     * 그래도 안전한 이유는 이 클래스가 격리하는 대상이 "컨테이너 인스턴스" 가 아니라 "키 이름"
     * 이기 때문이다 — 각 테스트가 쓰는 키는 {@code runId} 등 단조 증가 식별자를 접두사로 붙여
     * 만들어지므로(예: {@code RunPositionRedisValue} 사용부), 컨테이너를 공유해도 서로 다른 테스트가
     * 같은 키를 밟지 않는다. 위 클래스 자바독의 "전용 컨테이너를 매번 새로 올린다" 는 <b>테스트
     * 실행마다</b>가 아니라 <b>공유 {@code school-bus-redis-1} 대비 이 클래스 트리 전용</b>이라는
     * 뜻이다.
     */
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
